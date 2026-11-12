package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.jacoco.core.tools.ExecFileLoader;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.testing.TestRun;
import io.codiqo.api.testing.TestRuns;
import io.codiqo.core.ClassGraphWrapper;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.gradle.model.ModuleData;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.outcome.BuildVerdict;
import io.codiqo.submit.outcome.CommitBuild;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * The commit's build as Gradle ran it: Gradle builds and tests the commit before the analysis worker starts, so every
 * step only reports what the build did, and none of them waits.
 */
public final class GradleCommitBuild implements CommitBuild {
    private static final String BUILD_FAILED = "build failed";

    private final AnalysisRequest request;
    private final RunArgs args;
    private final LogFactory logFactory;
    private final Log log;

    public GradleCommitBuild(AnalysisRequest request, RunArgs args, LogFactory logFactory) {
        this.request = Objects.requireNonNull(request);
        this.args = Objects.requireNonNull(args);
        this.logFactory = Objects.requireNonNull(logFactory);
        this.log = logFactory.getLogger(GradleCommitBuild.class);
    }
    /** Gradle analyses the working tree its build ran in, which the run leaves as it is */
    @Override
    public AutoCloseable checkout() {
        return () -> { };
    }
    /**
     * Whose failure a failed build is was decided where the failed tasks were read ({@link BuildFailures}), by the rules
     * Maven's output is read by: a download or a dependency that failed is the environment's, and an Error Prone crash is
     * the toolchain's and halts the run. Gradle reports no timeout apart from a failure, and javac's own crash reaches only
     * its console, so both count as the author's. A build that passed is checked, before any review is paid for, for what
     * the full analysis needs: the language server's lombok agent, and coverage the tests recorded. Coverage is merged only here, after the exclusion
     * gates: merging is pointless work for an excluded commit, and the merge throws when it cannot stamp the merged file,
     * which would fail a run that should have ended in an exclusion.
     */
    @Override
    public BuildVerdict compile() throws IOException {
        if (StringUtils.isNotBlank(request.getBuildFailureDetail())) {
            if (StringUtils.isNotBlank(request.getCompilerCrash())) {
                throw new IllegalStateException("the compiler crashed in the analysis build, which is not the commit's doing: " + request.getCompilerCrash());
            }
            return new BuildVerdict.Failed(BUILD_FAILED, Objects.requireNonNull(request.getBuildFailureCategory(), "a failed build's category"), request.getBuildFailureDetail(), false);
        }

        if (StringUtils.isNotBlank(request.getLombokAgentError())) {
            throw new IllegalStateException(request.getLombokAgentError());
        }

        if (BooleanUtils.negate(request.isIgnoreCoverage())) {
            for (ModuleData module : request.getModules()) {
                mergeCoverageParts(module, log);
            }
            if (args.isFailOnUninstrumentedModule()) {
                requireCoverage(request.getModules(), log);
            }
        }
        return BuildVerdict.passed(Optional.empty());
    }
    /** the tests ran in Gradle's build; their reports are read only when the run measures coverage */
    @Override
    public BuildVerdict test() {
        if (request.isIgnoreCoverage()) {
            return BuildVerdict.passed(Optional.empty());
        }
        List<File> reports = request.getModules().stream().flatMap(module -> module.getTestReportDirectories().stream()).map(File::new).distinct().toList();
        return BuildVerdict.tested(TestRuns.read(reports, log), Optional.empty());
    }
    /**
     * The modules the collector read from Gradle's model before the build, as projects whose class output and classpath
     * the build produced, and the class graph scanned over them.
     */
    @Override
    public ClassGraphSpec projects() {
        Set<URI> jars = Sets.newLinkedHashSet();
        for (ModuleData module : request.getModules()) {
            if (args.isExcludedProject(module.getGroupId(), module.getArtifactId())) {
                log.info("excluding module %s:%s (codiqo.excludeProjects)", module.getGroupId(), module.getArtifactId());
                args.getExcludedProjectDirs().add(new File(module.getBaseDirectory()));
                continue;
            }

            GradleProjectWrapper wrapper = new GradleProjectWrapper();
            wrapper.setId(module.getId());
            wrapper.setGroupId(module.getGroupId());
            wrapper.setArtifactId(module.getArtifactId());
            wrapper.setName(module.getArtifactId());
            wrapper.setVersion(module.getVersion());
            wrapper.setPackaging(module.getPackaging());
            wrapper.setDescription(module.getDescription());
            wrapper.setBaseDirectory(new File(module.getBaseDirectory()));

            File classesDir = new File(module.getOutputDirectory());
            wrapper.setOutputDirectory(classesDir);
            if (classesDir.exists()) {
                jars.add(classesDir.toURI());
            }

            File coverage = new File(module.getCoveragePath());
            if (coverage.exists()) {
                wrapper.setCoverage(Optional.of(coverage));
            }

            for (String path : module.getCompileSourceRoots()) {
                File dir = new File(path);
                wrapper.getDeclaredSourceRoots().add(dir);
                if (dir.exists()) {
                    wrapper.getCompileSourceRoots().add(dir);
                }
            }
            for (String path : module.getTestCompileSourceRoots()) {
                File dir = new File(path);
                wrapper.getDeclaredTestSourceRoots().add(dir);
                if (dir.exists()) {
                    wrapper.getTestCompileSourceRoots().add(dir);
                }
            }
            for (String path : module.getTestReportDirectories()) {
                wrapper.getTestReportDirectories().add(new File(path));
            }
            for (String path : module.getCompileClasspathElements()) {
                File file = new File(path);
                if (file.exists()) {
                    wrapper.getCompileClasspathElements().add(file);
                    jars.add(file.toURI());
                }
            }
            for (String path : module.getTestClasspathElements()) {
                File file = new File(path);
                if (file.exists()) {
                    wrapper.getTestClasspathElements().add(file);
                    jars.add(file.toURI());
                }
            }
            wrapper.setDependencies(module.getDependencies());

            args.getProjects().add(wrapper);
        }

        ClassGraph classGraph = new ClassGraph().enableAllInfo();
        jars.forEach(classGraph::overrideClasspath);
        classGraph.enableSystemJarsAndModules();
        ScanResult scanResult = classGraph.scan();

        ClassGraphSpec graphSpec = new ClassGraphWrapper(scanResult);
        args.getProjects().forEach(spec -> {
            if (spec instanceof GradleProjectWrapper wrapper) {
                wrapper.setScan(graphSpec);
            }
        });
        return graphSpec;
    }
    @Override
    public ProjectSpec sourceOnlyProject() {
        return GradleSourceOnlyProjectSpec.forWorkTree(args.getGit().getWorkTree(), request);
    }
    @Override
    public void populateProjectModel(SubmissionContext ctx) {
        new GradleProjectModelPopulator(logFactory.getLogger(GradleProjectModelPopulator.class)).accept(ctx);
    }
    /** Gradle's worker prints no summary: its output is the analysis it writes beside the build */
    @Override
    public void printSummary(SubmissionContext ctx) {
    }
    /**
     * Folds every Test task's exec part into the single per-module file the analysis reads. Gradle deletes a Test
     * task's jacoco destination file before the task runs, so the parts cannot share one path (see
     * {@link GradleBuildSupport#jacocoExecPart}); a shared path would keep only the last Test task's coverage.
     */
    @VisibleForTesting
    public static void mergeCoverageParts(ModuleData module, Log log) throws IOException {
        File merged = new File(module.getCoveragePath());
        File[] parts = merged.getParentFile().listFiles(file -> file.getName().startsWith(AnalysisEngine.EXEC_PART_PREFIX));
        if (ArrayUtils.isNotEmpty(parts)) {
            ExecFileLoader loader = new ExecFileLoader();
            long oldestPart = Long.MAX_VALUE;
            for (File part : parts) {
                loader.load(part);
                oldestPart = Math.min(oldestPart, part.lastModified());
            }
            loader.save(merged, false);

            /**
             * The merged file carries the OLDEST contributing part's timestamp, not the merge's own. A part left
             * behind by a PREVIOUS checkout is stale and only its age can say so, while a part whose Test task was
             * up-to-date this build is still valid. A fresh timestamp would hand
             * JavaLanguageSpec.captureJacocoCoverage a file that always looks newer than the sources, permanently
             * disarming its staleness guard and letting one commit be scored with another commit's coverage.
             */
            if (BooleanUtils.negate(merged.setLastModified(oldestPart))) {
                throw new IOException(String.format(Locale.ROOT,
                        "could not stamp %s with the oldest contributing exec part's time (%d); refusing to continue because the coverage staleness guard would be bypassed",
                        merged.getAbsolutePath(), oldestPart));
            }

            log.info("merged %d jacoco exec part(s) for %s into %s (stamped %s from the oldest part)",
                    parts.length,
                    module.getArtifactId(),
                    merged.getAbsolutePath(),
                    Instant.ofEpochMilli(oldestPart));
        }
    }
    /**
     * A module whose tests ran but which has no merged coverage: the agent did not attach. Coverage capture finds the
     * same thing later, after the review has started; found here, the run fails before the review is paid for.
     */
    private static void requireCoverage(List<ModuleData> modules, Log log) throws IOException {
        List<String> uninstrumented = Lists.newArrayList();
        for (ModuleData module : modules) {
            TestRun moduleRun = TestRuns.read(module.getTestReportDirectories().stream().map(File::new).toList(), log);
            boolean testsRan = moduleRun.getTests() > moduleRun.getSkipped();
            if (BooleanUtils.and(new boolean[] { testsRan, BooleanUtils.negate(new File(module.getCoveragePath()).isFile()) })) {
                uninstrumented.add(module.getGroupId() + ":" + module.getArtifactId());
            }
        }
        if (CollectionUtils.isNotEmpty(uninstrumented)) {
            throw new IOException(String.format("coverage is required (codiqo.failOnUninstrumentedModule) but the tests of %s ran without the JaCoCo agent writing coverage",
                    Joiner.on(", ").join(uninstrumented)));
        }
    }
}
