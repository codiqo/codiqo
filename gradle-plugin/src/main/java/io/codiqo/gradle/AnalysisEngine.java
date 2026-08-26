package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.jacoco.core.tools.ExecFileLoader;


import io.codiqo.api.BuildTool;
import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.ClientInfoModel.BuildToolEnum;
import io.codiqo.core.ClassGraphWrapper;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.gradle.model.ModuleData;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.submit.CommitModelPopulator;
import io.codiqo.submit.DuplicationReportPopulator;
import io.codiqo.submit.EffectiveChangePopulator;
import io.codiqo.submit.FileAnalysisPopulator;
import io.codiqo.submit.IndexModelPopulator;
import io.codiqo.submit.MetricsAggregator;
import io.codiqo.submit.ModuleLevelMetricsPopulator;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.ScoringConfigs;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.util.Fetch;
import io.codiqo.util.JGit;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import lombok.experimental.UtilityClass;

/**
 * Runs the shared analysis engine against a collected {@link AnalysisRequest}. Operates only on
 * plain data (no Gradle types), so it runs inside the isolated analysis worker.
 */
@UtilityClass
public class AnalysisEngine {
    public void run(AnalysisRequest request, LogFactory logFactory) throws Exception {
        RunArgs args = new RunArgs();
        args.setDumpAnalysis(true);
        args.setIgnoreCoverage(request.isIgnoreCoverage());
        args.setIgnoreCpd(request.isIgnoreCpd());
        args.setExcludeProjects(request.getExcludeProjects());
        args.setExcludePaths(request.getExcludePaths());
        args.setBuildTool(BuildTool.GRADLE);
        args.setIgnoreDiagnostics(request.isIgnoreDiagnostics());
        args.setIgnoreComplexity(request.isIgnoreComplexity());
        args.setFailOnJdtlsError(request.isFailOnJdtlsError());
        args.setFailOnUninstrumentedModule(request.isFailOnUninstrumentedModule());
        args.setJavaHome(new File(request.getJavaHome()));
        args.setOutputDirectory(new File(request.getOutputDirectory()));

        args.setJdtlsVersion(request.getJdtlsVersion());
        args.setJdtlsUseSnapshot(request.isJdtlsUseSnapshot());
        args.setJdtUseSharedIndex(request.isJdtUseSharedIndex());
        args.setJdtIncludeDecompiledSources(request.isJdtIncludeDecompiledSources());
        args.setImportTimeout(Duration.ofMinutes(request.getImportTimeoutMinutes()));
        args.setLspQueryTimeout(Duration.ofSeconds(request.getLspQueryTimeoutSeconds()));

        args.validate();

        if (BooleanUtils.negate(request.isIgnoreCoverage())) {
            Log mergeLog = logFactory.getLogger(AnalysisEngine.class);
            for (ModuleData module : request.getModules()) {
                mergeCoverageParts(module, mergeLog);
            }
        }

        try (ClassGraphSpec scan = buildProjects(request, args, logFactory.getLogger(AnalysisEngine.class))) {
            try (Repository git = JGit.openRepository(new File(request.getRootDir()))) {
                args.setGit(git);
                args.setDefaultBranch(JGit.currentBranchOrDefault(git));
                args.setCommitId(resolveCommitId(request, git));

                runEngine(request, args, logFactory);
            } finally {
                Log log = logFactory.getLogger(AnalysisEngine.class);
                args.getProjects().forEach(spec -> {
                    try {
                        spec.close();
                    } catch (Exception err) {
                        log.warn("failed to close project spec %s: %s", spec, err.getMessage());
                    }
                });
            }
        }
    }
    /**
     * fold every Test task's exec part into the single per-module file the analysis reads. Gradle deletes a Test task's
     * jacoco destination file before the task runs, so the parts cannot share one path (see
     * {@link GradleBuildSupport#jacocoExecPart}); merging here keeps the one-exec-per-module contract the Maven side
     * gets for free from surefire's sequential executions.
     */
    static void mergeCoverageParts(ModuleData module, Log log) throws IOException {
        File merged = new File(module.getCoveragePath());
        File[] parts = merged.getParentFile().listFiles(file -> file.getName().startsWith(GradleBuildSupport.EXEC_PART_PREFIX));
        if (ArrayUtils.isEmpty(parts)) {
            return;
        }

        ExecFileLoader loader = new ExecFileLoader();
        long oldestPart = Long.MAX_VALUE;
        for (File part : parts) {
            loader.load(part);
            oldestPart = Math.min(oldestPart, part.lastModified());
        }
        loader.save(merged, false);

        /**
         * the merged file carries the OLDEST contributing part's timestamp, not the merge's own. parts are deliberately
         * kept even when their Test task did not run this build — an up-to-date task's part is still valid for unchanged
         * inputs, so pruning it would under-report coverage — but a part left behind by a PREVIOUS checkout is not, and
         * the only thing that can tell those apart is age. writing a fresh timestamp here would hand
         * JavaLanguageSpec.captureJacocoCoverage a file that always looks newer than the sources and permanently disarm
         * its exec-vs-latestModified staleness guard, letting one commit be scored with another commit's coverage.
         */
        if (BooleanUtils.negate(merged.setLastModified(oldestPart))) {
            throw new IOException(String.format(
                    "could not stamp %s with the oldest contributing exec part's time (%d); refusing to continue because the coverage staleness guard would be bypassed",
                    merged.getAbsolutePath(), oldestPart));
        }

        log.info("merged %d jacoco exec part(s) for %s into %s (stamped %s from the oldest part)",
                parts.length,
                module.getArtifactId(),
                merged.getAbsolutePath(),
                Instant.ofEpochMilli(oldestPart));
    }
    private static ClassGraphSpec buildProjects(AnalysisRequest request, RunArgs args, Log log) {
        Set<URI> jars = new LinkedHashSet<>();
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
                if (dir.exists()) {
                    wrapper.getCompileSourceRoots().add(dir);
                }
            }
            for (String path : module.getTestCompileSourceRoots()) {
                File dir = new File(path);
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
    private static String resolveCommitId(AnalysisRequest request, Repository git) throws Exception {
        if (Objects.nonNull(request.getCommitId())) {
            return request.getCommitId();
        }
        ObjectId head = git.resolve("HEAD");
        return head.name();
    }
    private static void runEngine(AnalysisRequest request, RunArgs args, LogFactory logFactory) throws Exception {
        Log log = logFactory.getLogger(AnalysisEngine.class);
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                registry.load();

                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, args);
                CommitAnalysis analysis = analyzer.analyze();

                MutableBoolean toApply = new MutableBoolean();
                analysis.forEach(diff -> {
                    String name = diff.getFile().getName();
                    if (BooleanUtils.or(new boolean[] {
                            FilenameUtils.isExtension(name, registry.extensions()),
                            ConfigFiles.isConfigFile(name)
                    })) {
                        toApply.setTrue();
                    }
                });
                if (toApply.isFalse()) {
                    log.log(org.slf4j.event.Level.WARN, "commit %s: no diff files match registered languages %s — nothing to dump",
                            args.getCommitId(),
                            registry.extensions());
                    return;
                }

                IndexingSummary index = registry.index(analysis);
                registry.identifyAffectedSymbols(index, analysis);
                registry.collectAndCapture(index, analysis);

                ClientInfoModel clientInfo = new ClientInfoModel();
                clientInfo.setBuildTool(BuildToolEnum.GRADLE);
                clientInfo.setVersion(request.getGradleVersion());
                clientInfo.setName("codiqo-gradle-plugin");

                SubmissionContext ctx = SubmissionContext.create(
                        args,
                        index,
                        analysis,
                        workTree,
                        logFactory,
                        request.getRootCode(),
                        request.getRootName(),
                        clientInfo);

                new GradleProjectModelPopulator(logFactory.getLogger(GradleProjectModelPopulator.class)).accept(ctx);
                new CommitModelPopulator().accept(ctx);
                new ModuleLevelMetricsPopulator().accept(ctx);
                new FileAnalysisPopulator().accept(ctx);
                new EffectiveChangePopulator().accept(ctx);
                new IndexModelPopulator().accept(ctx);

                new DuplicationReportPopulator().accept(ctx);
                new MetricsAggregator().accept(ctx);

                ctx.getSubmissionModel().setScoringConfig(ScoringConfigs.map(args));
                new OutputSerializer(true, logFactory.getLogger(OutputSerializer.class)).accept(ctx);
            }
        }
    }
}
