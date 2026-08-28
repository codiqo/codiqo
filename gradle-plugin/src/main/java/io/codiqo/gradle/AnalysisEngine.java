package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.Constants;
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
import io.codiqo.client.ApiException;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisBuildFailureModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.ClientInfoModel.BuildToolEnum;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.core.ClassGraphWrapper;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.gradle.model.ModuleData;
import io.codiqo.submit.AnalysisSubmitter;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.CommitExclusions.Exclusion;
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
 * Runs the shared analysis engine against a collected {@link AnalysisRequest}. Operates only on plain data (no Gradle
 * types), so it runs inside the isolated analysis worker.
 */
@UtilityClass
public class AnalysisEngine {
    /**
     * declared on the worker side, not on the Gradle-typed helper that also uses it: the worker runs on a bare
     * classpath with no gradle-api, so it must not reference a class that imports Gradle types. Reading the constant
     * from {@code GradleBuildSupport} only appeared to work because javac folds a compile-time String constant.
     */
    public static final String EXEC_PART_PREFIX = "codiqo-";

    public void run(AnalysisRequest request, LogFactory logFactory) throws Exception {
        RunArgs args = new RunArgs();
        args.setDumpAnalysis(true);
        args.setIgnoreCoverage(request.isIgnoreCoverage());
        args.setIgnoreCpd(request.isIgnoreCpd());
        args.setExcludeProjects(request.getExcludeProjects());
        args.setExcludePaths(request.getExcludePaths());
        args.setBuildTool(BuildTool.GRADLE);
        args.setSkipOnBuildFailure(request.isSkipOnBuildFailure());
        args.setScoreOnBuildFailure(request.isScoreOnBuildFailure());
        args.setFirstParentOnly(request.isFirstParentOnly());
        args.setExcludeRevertedCommits(request.isExcludeRevertedCommits());
        Optional.ofNullable(request.getIncludeBranches()).ifPresent(args::setIncludeBranches);
        Optional.ofNullable(request.getIncludeAuthorEmails()).ifPresent(args::setIncludeAuthorEmails);
        Optional.ofNullable(request.getExcludeAuthorEmails()).ifPresent(args::setExcludeAuthorEmails);
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

        Log log = logFactory.getLogger(AnalysisEngine.class);
        try (Repository git = JGit.openRepository(new File(request.getRootDir()))) {
            args.setGit(git);
            args.setDefaultBranch(JGit.currentBranchOrDefault(git));
            args.setCommitId(resolveCommitId(request, git));

            // gate before the ClassGraph scan and the JDT import: an excluded commit costs a git walk, not an analysis
            Optional<Exclusion> excluded = CommitExclusions.beforeAnalysis(args);
            if (excluded.isPresent()) {
                reportExclusion(request, args, args.getCommitId(), excluded.get(), List.of(), log);
                return;
            }

            /**
             * a failed build leaves no trustworthy class output, so the normal pipeline cannot run: no ClassGraph
             * scan, no JDT import, no coverage. The same fork in the road the Maven side takes.
             */
            if (StringUtils.isNotBlank(request.getBuildFailureDetail())) {
                runDegraded(request, args, logFactory, log);
                return;
            }

            /**
             * after the gate: merging is pointless work for an excluded commit, and its staleness stamp can fail the
             * run outright
             */
            if (BooleanUtils.negate(request.isIgnoreCoverage())) {
                for (ModuleData module : request.getModules()) {
                    mergeCoverageParts(module, log);
                }
            }

            try (ClassGraphSpec scan = buildProjects(request, args, log)) {
                runEngine(request, args, logFactory);
            } finally {
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
     * an excluded commit is reported to the backend rather than scored, so its files are still recorded and it is not
     * re-offered as missing on the next run. A dump-only caller has nowhere to put an exclusion, so the reason is
     * logged and the run ends.
     */
    private static void reportExclusion(AnalysisRequest request, RunArgs args, String commitSha, Exclusion exclusion, List<FileChangeModel> files, Log log) throws Exception {
        reportExclusion(request, args, commitSha, exclusion, files, log, null, null);
    }
    private static void reportExclusion(AnalysisRequest request, RunArgs args, String commitSha, Exclusion exclusion, List<FileChangeModel> files, Log log, String detail, ProjectMetricsModel projectMetrics) throws Exception {
        log.warn("commit %s skipped: %s", commitSha, exclusion.getReason());
        if (request.isSubmit()) {
            AnalysisSubmitter.exclude(
                    request.getApiUrl(),
                    request.getApiKey(),
                    request.getConnectTimeoutSeconds(),
                    request.getReadTimeoutSeconds(),
                    commitSha,
                    exclusion.getReason(),
                    exclusion.getCategory(),
                    detail,
                    files,
                    projectMetrics,
                    log);
        }
    }
    /**
     * fold every Test task's exec part into the single per-module file the analysis reads. Gradle deletes a Test
     * task's jacoco destination file before the task runs, so the parts cannot share one path (see
     * {@link GradleBuildSupport#jacocoExecPart}).
     */
    static void mergeCoverageParts(ModuleData module, Log log) throws IOException {
        File merged = new File(module.getCoveragePath());
        File[] parts = merged.getParentFile().listFiles(file -> file.getName().startsWith(EXEC_PART_PREFIX));
        if (ArrayUtils.isNotEmpty(parts)) {
            ExecFileLoader loader = new ExecFileLoader();
            long oldestPart = Long.MAX_VALUE;
            for (File part : parts) {
                loader.load(part);
                oldestPart = Math.min(oldestPart, part.lastModified());
            }
            loader.save(merged, false);

            /**
             * the merged file carries the OLDEST contributing part's timestamp, not the merge's own. A part left
             * behind by a PREVIOUS checkout is stale and only its age can say so, while a part whose Test task was
             * up-to-date this build is still valid. A fresh timestamp would hand
             * JavaLanguageSpec.captureJacocoCoverage a file that always looks newer than the sources, permanently
             * disarming its staleness guard and letting one commit be scored with another commit's coverage.
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
    }
    /**
     * The build-failed commit. By default it is reported excluded with the failure detail attached; with
     * codiqo.scoreOnBuildFailure it is scored instead, from a source-only index that runs PMD over the work tree but
     * never starts the language server — genuine code volume, rather than a score derived from config lines alone.
     */
    private static void runDegraded(AnalysisRequest request, RunArgs args, LogFactory logFactory, Log log) throws Exception {
        String reason = "build failed";

        /**
         * skipOnBuildFailure=false asks for a hard error instead of an exclusion. Throwing here fails the worker, so
         * javaexec fails the task and a pipeline that opted out of tolerating build failures does not go green on
         * one. A Gradle exception type cannot be used: the worker runs on a bare classpath with no gradle-api.
         */
        if (BooleanUtils.negate(args.isSkipOnBuildFailure())) {
            throw new IllegalStateException(String.format("commit %s: %s, and codiqo.skipOnBuildFailure is false%n%s",
                    args.getCommitId(), reason, request.getBuildFailureDetail()));
        }

        args.getProjects().add(GradleSourceOnlyProjectSpec.forWorkTree(args.getGit().getWorkTree(), request));
        SubmissionContext ctx = degradedSubmission(request, args, logFactory, log);

        AnalysisBuildFailureModel buildFailure = new AnalysisBuildFailureModel();
        buildFailure.setReason(reason);
        buildFailure.setCategory(AnalysisExcludeCategory.BUILD_FAILURE);
        buildFailure.setDetail(request.getBuildFailureDetail());
        ctx.getSubmissionModel().setScoringConfig(ScoringConfigs.map(args));
        ctx.getSubmissionModel().setBuildFailure(buildFailure);

        List<FileChangeModel> files = ctx.getSubmissionModel().getFiles();
        if (BooleanUtils.and(new boolean[] { args.isScoreOnBuildFailure(), CollectionUtils.isNotEmpty(files) })) {
            if (ctx.getAnalysis().isRevertCommit()) {
                reportRevert(request, args, ctx, log);
                return;
            }
            log.warn("commit %s: build failed — running degraded analysis", args.getCommitId());
            new OutputSerializer(true, logFactory.getLogger(OutputSerializer.class)).accept(ctx);
            if (request.isSubmit()) {
                AnalysisAcceptedModel accepted = AnalysisSubmitter.submit(
                        request.getApiUrl(), request.getApiKey(), request.getConnectTimeoutSeconds(),
                        request.getReadTimeoutSeconds(), ctx.getSubmissionModel(), log);
                log.info("accepted degraded analysis id: %s status: %s", accepted.getAnalysisId(), accepted.getStatus());
            }
            return;
        }

        reportExclusion(request, args, args.getCommitId(),
                new Exclusion(reason, AnalysisExcludeCategory.BUILD_FAILURE), files, log,
                request.getBuildFailureDetail(), ctx.getSubmissionModel().getProjectMetrics());
    }
    /**
     * The best-effort source index over an unbuilt work tree, falling back to the raw diff when it cannot be read.
     * PMD parsing a tree whose build just failed is exactly where an I/O failure is expected, and the Maven side
     * degrades the same way rather than losing the commit: without the fallback the exception escapes the worker, no
     * exclusion is ever submitted, and the sha is re-offered as missing on every subsequent run.
     */
    private static SubmissionContext degradedSubmission(AnalysisRequest request, RunArgs args, LogFactory logFactory, Log log) throws Exception {
        try {
            return sourceOnlySubmission(request, args, logFactory);
        } catch (IOException err) {
            log.warn("commit %s: source-only degraded index failed (%s) — falling back to diff-only scoring",
                    args.getCommitId(), err.getMessage());
            return diffOnlySubmission(request, args, logFactory);
        }
    }
    private static SubmissionContext sourceOnlySubmission(AnalysisRequest request, RunArgs args, LogFactory logFactory) throws Exception {
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        CommitAnalysis analysis = new JGitDeltaAnalyzer(logFactory, args).analyze();
        IndexingSummary index = DefaultLanguageProcessors.sourceOnlyIndex(args, analysis, logFactory);

        SubmissionContext toReturn = SubmissionContext.create(
                args, index, analysis, workTree, logFactory, request.getRootCode(), request.getRootName(), clientInfo(request));

        new GradleProjectModelPopulator(logFactory.getLogger(GradleProjectModelPopulator.class)).accept(toReturn);
        new CommitModelPopulator().accept(toReturn);
        new ModuleLevelMetricsPopulator().accept(toReturn);
        new FileAnalysisPopulator().accept(toReturn);
        new EffectiveChangePopulator().accept(toReturn);

        /**
         * only the driver-score statistics are populated: no coverage, diagnostics or CPD is captured for a failed
         * build, so those aggregates stay absent rather than being fabricated as zeros
         */
        MetricsAggregator.populateDriverMetrics(toReturn);
        return toReturn;
    }
    /**
     * git diff and commit metadata only: no code units, no metrics. The degraded score is derived from the diff alone.
     */
    private static SubmissionContext diffOnlySubmission(AnalysisRequest request, RunArgs args, LogFactory logFactory) throws Exception {
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        CommitAnalysis analysis = new JGitDeltaAnalyzer(logFactory, args).analyze();

        SubmissionContext toReturn = SubmissionContext.create(
                args, null, analysis, workTree, logFactory, request.getRootCode(), request.getRootName(), clientInfo(request));

        new CommitModelPopulator().accept(toReturn);
        new FileAnalysisPopulator().accept(toReturn);
        return toReturn;
    }
    private static ClientInfoModel clientInfo(AnalysisRequest request) {
        ClientInfoModel toReturn = new ClientInfoModel();
        toReturn.setBuildTool(BuildToolEnum.GRADLE);
        toReturn.setVersion(request.getGradleVersion());
        toReturn.setName("codiqo-gradle-plugin");
        return toReturn;
    }
    /**
     * the revert itself is excluded unconditionally, and with excludeRevertedCommits the original is retroactively
     * excluded too, so reverted work stops counting. 404 means the original predates the indexing window.
     */
    private static void reportRevert(AnalysisRequest request, RunArgs args, SubmissionContext ctx, Log log) throws Exception {
        reportExclusion(request, args, args.getCommitId(),
                new Exclusion("revert commit (no LLM scoring performed)", AnalysisExcludeCategory.REVERT_COMMIT),
                ctx.getSubmissionModel().getFiles(), log);

        if (args.isExcludeRevertedCommits()) {
            String revertedSha = ctx.getAnalysis().getRevertedCommitId();
            try {
                reportExclusion(request, args, revertedSha,
                        new Exclusion(String.format("reverted by commit %s", JGit.shortSha(args.getCommitId())), AnalysisExcludeCategory.REVERTED),
                        List.of(), log);
            } catch (ApiException err) {
                if (err.getCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                    log.warn("reverted commit %s not known to backend (outside indexing window?) — skipping its exclusion", revertedSha);
                } else {
                    throw err;
                }
            }
        }
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
        return git.resolve(Constants.HEAD).name();
    }
    private static void runEngine(AnalysisRequest request, RunArgs args, LogFactory logFactory) throws Exception {
        Log log = logFactory.getLogger(AnalysisEngine.class);
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                registry.load();

                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, args);
                CommitAnalysis analysis = analyzer.analyze();

                List<String> changedFiles = new ArrayList<>();
                analysis.forEach(diff -> changedFiles.add(diff.getFile().getName()));

                ClientInfoModel clientInfo = clientInfo(request);

                Optional<Exclusion> excluded = CommitExclusions.afterDelta(args, analysis, registry.extensions(), changedFiles);
                if (excluded.isPresent()) {
                    /**
                     * an excluded commit still carries the raw git diff so the backend persists per-file changes;
                     * indexing has not run, so the populator emits diff-only file models with no code units
                     */
                    SubmissionContext excludeCtx = SubmissionContext.create(
                            args, null, analysis, workTree, logFactory, request.getRootCode(), request.getRootName(), clientInfo);
                    new FileAnalysisPopulator().accept(excludeCtx);
                    reportExclusion(request, args, args.getCommitId(), excluded.get(), excludeCtx.getSubmissionModel().getFiles(), log);
                    return;
                }

                IndexingSummary index = registry.index(analysis);
                registry.identifyAffectedSymbols(index, analysis);
                registry.collectAndCapture(index, analysis);

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

                // the dump is written first, so a submission the backend rejects still leaves the YAML to inspect
                if (analysis.isRevertCommit()) {
                    reportRevert(request, args, ctx, log);
                    return;
                }

                if (request.isSubmit()) {
                    AnalysisAcceptedModel accepted = AnalysisSubmitter.submit(
                            request.getApiUrl(),
                            request.getApiKey(),
                            request.getConnectTimeoutSeconds(),
                            request.getReadTimeoutSeconds(),
                            ctx.getSubmissionModel(),
                            log);
                    log.info("accepted analysis id: %s status: %s", accepted.getAnalysisId(), accepted.getStatus());
                }
            }
        }
    }
}
