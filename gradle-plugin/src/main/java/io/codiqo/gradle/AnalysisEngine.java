package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.jacoco.core.tools.ExecFileLoader;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

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
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.core.ClassGraphWrapper;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.gradle.model.ModuleData;
import io.codiqo.submit.AnalysisSubmitter;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.CommitExclusions.Exclusion;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.SubmissionAssembly;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.submit.auth.CodiqoCredentials;
import io.codiqo.submit.hotspots.FixCommits;
import io.codiqo.submit.hotspots.HotspotSnapshots;
import io.codiqo.util.Fetch;
import io.codiqo.util.JGit;
import io.codiqo.util.ProgressStage;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import lombok.experimental.UtilityClass;

/**
 * Runs the shared analysis engine against a collected {@link AnalysisRequest}. Operates only on plain data (no Gradle
 * types), so it runs inside the isolated analysis worker.
 */
@UtilityClass
public class AnalysisEngine {
    private static final String ERR_NO_CREDENTIAL = "no codiqo.apiKey configured and no browser login stored; "
            + "run 'gradle codiqoLogin' once on this machine, or set codiqo.apiKey";
    /**
     * Declared on the worker side, not on the Gradle-typed helper that also uses it. The worker runs on a bare
     * classpath with no gradle-api, so it must not reference a class that imports Gradle types. Reading the constant
     * from {@code GradleBuildSupport} only appeared to work because javac folds a compile-time String constant; moving
     * it there, or making it non-constant, would load a Gradle-typed class in the worker and fail it.
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
        args.setHotspotsEnabled(request.isHotspots());
        args.setFailOnJdtlsError(request.isFailOnJdtlsError());
        args.setFailOnUninstrumentedModule(request.isFailOnUninstrumentedModule());
        args.setJavaHome(new File(request.getJavaHome()));
        args.setOutputDirectory(new File(request.getOutputDirectory()));
        Optional.ofNullable(request.getBuildProgressFile()).map(File::new).ifPresent(args::setBuildProgressFile);

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
            JGit.currentBranchOrDefault(git).ifPresent(args::setDefaultBranch);
            args.setCommitId(resolveCommitId(request, git));
            args.setHotspotsCommitId(JGit.resolveCommit(git, Optional.ofNullable(request.getHotspotsCommitId()).orElse(Constants.HEAD)));

            /**
             * The exclusion gate runs before the ClassGraph scan and the JDT import, so an excluded commit costs a git
             * walk rather than a full analysis.
             */
            Optional<Exclusion> excluded = CommitExclusions.beforeAnalysis(args);
            if (excluded.isPresent()) {
                reportExclusion(request, args, args.getCommitId(), excluded.get(), List.of(), log);
                return;
            }

            /**
             * A failed build leaves no trustworthy class output, so the normal pipeline cannot run: no ClassGraph
             * scan, no JDT import, no coverage. The Maven side takes the same fork, and both must stay aligned so a
             * build-failed commit is treated the same whichever plugin analysed it.
             */
            if (StringUtils.isNotBlank(request.getBuildFailureDetail())) {
                runDegraded(request, args, logFactory, log);
                return;
            }

            /**
             * Coverage is merged only after the exclusion gate: merging is pointless work for an excluded commit, and
             * mergeCoverageParts throws when it cannot stamp the merged file, which would fail a run that should
             * have ended as an exclusion.
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
     * An excluded commit is reported to the backend rather than scored, so its files are still recorded and it is not
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
                    credential(request, log),
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
     * Folds every Test task's exec part into the single per-module file the analysis reads. Gradle deletes a Test
     * task's jacoco destination file before the task runs, so the parts cannot share one path (see
     * {@link GradleBuildSupport#jacocoExecPart}); a shared path would keep only the last Test task's coverage.
     */
    @VisibleForTesting
    public static void mergeCoverageParts(ModuleData module, Log log) throws IOException {
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
                        request.getApiUrl(), credential(request, log), request.getConnectTimeoutSeconds(),
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

        return DefaultLanguageProcessors.sourceOnlyIndex(args, analysis, logFactory, index -> {
            SubmissionContext toReturn = SubmissionContext.create(
                    args, index, analysis, workTree, logFactory, request.getRootCode(), request.getRootName(), clientInfo(request));

            new GradleProjectModelPopulator(logFactory.getLogger(GradleProjectModelPopulator.class)).accept(toReturn);
            SubmissionAssembly.degraded(toReturn);
            return toReturn;
        });
    }
    /**
     * Git diff and commit metadata only: no code units, no metrics. The degraded score is derived from the diff alone.
     */
    private static SubmissionContext diffOnlySubmission(AnalysisRequest request, RunArgs args, LogFactory logFactory) throws Exception {
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        CommitAnalysis analysis = new JGitDeltaAnalyzer(logFactory, args).analyze();

        SubmissionContext toReturn = SubmissionContext.create(
                args, null, analysis, workTree, logFactory, request.getRootCode(), request.getRootName(), clientInfo(request));

        SubmissionAssembly.diffOnly(toReturn);
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
     * The revert itself is excluded unconditionally, and with excludeRevertedCommits the original is retroactively
     * excluded too, so reverted work stops counting. A 404 means the original predates the indexing window; it is
     * logged rather than thrown so an old revert target does not fail the run.
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
                if (err.getCode() == HttpResponseStatus.NOT_FOUND.code()) {
                    log.warn("reverted commit %s not known to backend (outside indexing window?) — skipping its exclusion", revertedSha);
                } else {
                    throw err;
                }
            }
        }
    }
    private static ClassGraphSpec buildProjects(AnalysisRequest request, RunArgs args, Log log) {
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
    /**
     * Runs after the commit's own outcome is settled and swallows its failure into a warning, so a failure costs only
     * the snapshot (the previous one stays), never the analysis.
     */
    private static void reportHotspots(AnalysisRequest request, SubmissionContext ctx, Log log) {
        try {
            Optional<HotspotSnapshotModel> snapshot = HotspotSnapshots.build(ctx, since -> fixCommits(request, since, log), log);
            if (snapshot.isPresent()) {
                HotspotSnapshots.write(ctx, snapshot.get(), log);
                if (request.isSubmit()) {
                    HotspotSnapshots.submit(
                            request.getApiUrl(),
                            credential(request, log),
                            request.getConnectTimeoutSeconds(),
                            request.getReadTimeoutSeconds(),
                            request.getRootCode(),
                            snapshot.get(),
                            log);
                }
            }
        } catch (Exception err) {
            log.warn("hotspot snapshot for %s not produced, the previous one stays: %s", ctx.getArgs().getCommitId(), err);
        }
    }
    /** only a submitting run has a server to ask; any other judges every commit by its message */
    private static FixCommits fixCommits(AnalysisRequest request, Instant since, Log log) throws Exception {
        FixCommits toReturn = FixCommits.byMessage();
        if (request.isSubmit()) {
            toReturn = FixCommits.fetch(request.getApiUrl(), credential(request, log), request.getConnectTimeoutSeconds(), request.getReadTimeoutSeconds(),
                    request.getRootCode(), since, log);
        }
        return toReturn;
    }
    private static String resolveCommitId(AnalysisRequest request, Repository git) throws Exception {
        return JGit.resolveCommit(git, Optional.ofNullable(request.getCommitId()).orElse(Constants.HEAD));
    }
    private static void runEngine(AnalysisRequest request, RunArgs args, LogFactory logFactory) throws Exception {
        Log log = logFactory.getLogger(AnalysisEngine.class);
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                registry.load();

                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, args);
                CommitAnalysis analysis = analyzer.analyze();

                List<String> changedFiles = Lists.newArrayList();
                analysis.forEach(diff -> changedFiles.add(diff.getFile().getName()));

                ClientInfoModel clientInfo = clientInfo(request);

                Optional<Exclusion> excluded = CommitExclusions.afterDelta(args, analysis, registry.extensions(), changedFiles);
                if (excluded.isPresent()) {
                    /**
                     * An excluded commit still carries the raw git diff so the backend persists per-file changes.
                     * Indexing has not run, so the populator emits diff-only file models with no code units.
                     */
                    SubmissionContext excludeCtx = SubmissionContext.create(
                            args, null, analysis, workTree, logFactory, request.getRootCode(), request.getRootName(), clientInfo);
                    SubmissionAssembly.excluded(excludeCtx);
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
                SubmissionAssembly.full(ctx);

                new OutputSerializer(true, logFactory.getLogger(OutputSerializer.class)).accept(ctx);

                /** The dump is written first, so a submission the backend rejects still leaves the YAML to inspect. */
                if (analysis.isRevertCommit()) {
                    reportRevert(request, args, ctx, log);
                } else if (request.isSubmit()) {
                    try (ProgressStage stage = ProgressStage.start(args, "submit")) {
                        AnalysisAcceptedModel accepted = AnalysisSubmitter.submit(
                                request.getApiUrl(),
                                credential(request, log),
                                request.getConnectTimeoutSeconds(),
                                request.getReadTimeoutSeconds(),
                                ctx.getSubmissionModel(),
                                log);
                        log.info("accepted analysis id: %s status: %s", accepted.getAnalysisId(), accepted.getStatus());
                        stage.succeeded();
                    }
                }
                reportHotspots(request, ctx, log);
            }
        }
    }
    /**
     * A configured key, or the browser login stored on this machine. Never the browser itself: the worker has nobody
     * at its terminal, so a missing login fails here with the task that creates one.
     */
    private static CodiqoCredential credential(AnalysisRequest request, Log log) throws IOException {
        return CodiqoCredentials.resolve(request.getApiKey(), request.getAuthUrl(), request.getResourceUrl(), false, log)
                .orElseThrow(() -> new IOException(ERR_NO_CREDENTIAL));
    }
}
