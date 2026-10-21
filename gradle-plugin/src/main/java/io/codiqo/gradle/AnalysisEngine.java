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
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.jacoco.core.tools.ExecFileLoader;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.api.BuildTool;
import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.testing.TestRun;
import io.codiqo.api.testing.TestRuns;
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
import io.codiqo.submit.CommitBuildFailedException;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.CommitIndexPublisher;
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
import io.codiqo.submit.review.LocalReviewException;
import io.codiqo.submit.review.LocalReviewRun;
import io.codiqo.llm.ConventionGuidance;
import io.codiqo.llm.review.ReviewTrace;
import io.codiqo.submit.ProjectHalts;
import io.codiqo.submit.SourceHiding;
import io.codiqo.submit.ProjectTags;
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
        Optional.ofNullable(request.getTestFailureHoldShare()).filter(StringUtils::isNotBlank).map(String::trim).map(Double::parseDouble).ifPresent(args::setTestFailureHoldShare);
        Optional.ofNullable(request.getTestFailureHoldMinTests()).filter(StringUtils::isNotBlank).map(String::trim).map(Integer::parseInt).ifPresent(args::setTestFailureHoldMinTests);
        args.setJavaHome(new File(request.getJavaHome()));
        args.setOutputDirectory(new File(request.getOutputDirectory()));
        Optional.ofNullable(request.getBuildProgressFile()).map(File::new).ifPresent(args::setBuildProgressFile);

        args.setJdtlsVersion(request.getJdtlsVersion());
        args.setJdtlsUseSnapshot(request.isJdtlsUseSnapshot());
        args.setJdtUseSharedIndex(request.isJdtUseSharedIndex());
        args.setJdtIncludeDecompiledSources(request.isJdtIncludeDecompiledSources());
        args.setJdtLombokAgent(request.isJdtLombokAgent());
        Optional.ofNullable(request.getLombokAgentJar()).map(File::new).ifPresent(args.getAgents()::add);
        args.setImportTimeout(Duration.ofMinutes(request.getImportTimeoutMinutes()));
        args.setLspQueryTimeout(Duration.ofSeconds(request.getLspQueryTimeoutSeconds()));
        applyReviewSettings(request, args);

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
            /** a hidden-source run without a usable key fails before anything is analysed, sent or paid for */
            hiding(request);
            Optional<Exclusion> excluded = CommitExclusions.beforeAnalysis(args);
            if (excluded.isPresent()) {
                reportExclusion(request, args, args.getCommitId(), excluded.get(), List.of(), log);
                return;
            }

            /** before the review starts: a halted project must not pay for another review the same failure would waste */
            CommitIndexApi client = CommitIndexPublisher.buildClient(request.getApiUrl(), credential(request, log), request.getConnectTimeoutSeconds(), request.getReadTimeoutSeconds());
            ProjectHalts.requireRunning(log, client, request.getApiUrl(), request.getRootCode());
            try {
                analyse(request, args, logFactory, log);
            } catch (Exception err) {
                /**
                 * A run that fails here submits nothing, so the next scheduled run would analyse the same commit and pay
                 * for its review again, every hour; the project is halted until an administrator resumes it, unless the
                 * failure halts nothing ({@link ProjectHalts#haltsNothing}: a stopped run, the author's broken build, a
                 * failed download), the same rule as on Maven. A run that fails after its submission was accepted has
                 * scored its commit, so it halts nothing either.
                 */
                if (BooleanUtils.negate(BooleanUtils.or(new boolean[] { ProjectHalts.haltsNothing(err), args.isSubmissionAccepted() }))) {
                    ProjectHalts.halt(log, client, request.getApiUrl(), request.getRootCode(), args.getCommitId(), err, request.isSourceHidden());
                    ProgressStage.abort(args, ExceptionUtils.getRootCauseMessage(err), ProjectHalts.traceOf(args, args.getCommitId()));
                }
                throw err;
            }
        }
    }
    private static void analyse(AnalysisRequest request, RunArgs args, LogFactory logFactory, Log log) throws Exception {
        args.setAnalysisStartedAt(Instant.now());

        /**
         * A failed build leaves no trustworthy class output, so the normal pipeline cannot run: no ClassGraph scan, no
         * JDT import, no coverage. The Maven side takes the same fork, and both must stay aligned so a build-failed
         * commit is treated the same whichever plugin analysed it.
         */
        if (StringUtils.isNotBlank(request.getBuildFailureDetail())) {
            Optional<LocalReviewRun> review = startReview(request, args, logFactory, log);
            try {
                runDegraded(request, args, review, logFactory, log);
            } finally {
                if (review.isPresent()) {
                    review.get().close();
                }
            }
            return;
        }

        /**
         * Only a full analysis starts the language server, so a lombok agent the collector could not resolve fails the
         * run here, before the review is paid for, and never an excluded or build-failed commit, which still gets
         * reported. The run halts the project until the build's repositories serve the jar or the switch is off.
         */
        if (StringUtils.isNotBlank(request.getLombokAgentError())) {
            throw new IllegalStateException(request.getLombokAgentError());
        }

        /**
         * Gradle has built and tested the commit before this worker starts, so whether the run can be measured is known
         * before the review is paid for: a module whose tests ran without coverage fails here and halts the project
         * instead of starting a review the failure would waste. A run where most tests failed is reviewed and scored all
         * the same, and the server holds its score until someone accepts it (TestRuns.heldReason). Coverage is merged only
         * after the exclusion gate: merging is pointless work for an excluded commit, and mergeCoverageParts throws when
         * it cannot stamp the merged file, which would fail a run that should have ended as an exclusion.
         */
        if (BooleanUtils.negate(request.isIgnoreCoverage())) {
            for (ModuleData module : request.getModules()) {
                mergeCoverageParts(module, log);
            }
            TestRun testRun = TestRuns.read(request.getModules().stream().flatMap(module -> module.getTestReportDirectories().stream()).map(File::new).distinct().toList(), log);
            log.info("test run: %d tests, %d failed, %d errors, %d skipped in %s", testRun.getTests(), testRun.getFailures(), testRun.getErrors(), testRun.getSkipped(), testRun.getTestTime());
            TestRuns.heldReason(testRun, args.getTestFailureHoldShare(), args.getTestFailureHoldMinTests()).ifPresent(reason -> {
                log.warn("%s: the commit is scored and its score held until someone accepts it", reason);
                args.setTestRunHeldReason(reason);
            });
            args.setTestRun(testRun);
            if (args.isFailOnUninstrumentedModule()) {
                requireCoverage(request.getModules(), log);
            }
        }

        /**
         * As on Maven, the language server the full analysis needs is downloaded before the review is paid for, and a
         * failed download ends the run with nothing stored (DownloadFailedException).
         */
        try (Fetch fetch = new Fetch(args)) {
            fetch.downloadJdtls(logFactory, args);
        }

        Optional<LocalReviewRun> review = startReview(request, args, logFactory, log);
        try (ClassGraphSpec scan = buildProjects(request, args, log)) {
            runEngine(request, args, review, logFactory);
        } finally {
            args.getProjects().forEach(spec -> {
                try {
                    spec.close();
                } catch (Exception err) {
                    log.warn("failed to close project spec %s: %s", spec, err.getMessage());
                }
            });
            if (review.isPresent()) {
                review.get().close();
            }
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
    /**
     * An excluded commit is reported to the backend rather than scored, so its files are still recorded and it is not
     * re-offered as missing on the next run.
     */
    private static void reportExclusion(AnalysisRequest request, RunArgs args, String commitSha, Exclusion exclusion, List<FileChangeModel> files, Log log) throws Exception {
        reportExclusion(request, args, commitSha, exclusion, files, log, null, null);
    }
    private static void reportExclusion(AnalysisRequest request, RunArgs args, String commitSha, Exclusion exclusion, List<FileChangeModel> files, Log log, String detail, ProjectMetricsModel projectMetrics) throws Exception {
        log.warn("commit %s skipped: %s", commitSha, exclusion.getReason());
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
                hiding(request),
                log);
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
     * The build-failed commit. Without a review it is reported excluded with the failure detail attached; with one
     * it is scored, from a source-only index that runs PMD over the work tree but never starts the language server —
     * genuine code volume, rather than a score derived from config lines alone.
     */
    private static void runDegraded(AnalysisRequest request, RunArgs args, Optional<LocalReviewRun> review, LogFactory logFactory, Log log) throws Exception {
        String reason = "build failed";

        /**
         * skipOnBuildFailure=false asks for a hard error instead of an exclusion. Throwing here fails the worker, so
         * javaexec fails the task and a pipeline that opted out of tolerating build failures does not go green on
         * one. A Gradle exception type cannot be used: the worker runs on a bare classpath with no gradle-api.
         */
        if (BooleanUtils.negate(args.isSkipOnBuildFailure())) {
            throw new CommitBuildFailedException(String.format("commit %s: %s, and codiqo.skipOnBuildFailure is false%n%s",
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
        /**
         * Local mode judges a commit with its review, which reads the code through git and needs no build, so a commit
         * whose review started is scored by rules from it as a build-failed commit, as on Maven. Gradle reports no
         * timeout apart from a failure, so every failed build counts as the author's here.
         */
        if (BooleanUtils.and(new boolean[] { review.isPresent(), CollectionUtils.isNotEmpty(files) })) {
            if (ctx.getAnalysis().isRevertCommit()) {
                reportRevert(request, args, ctx, log);
                return;
            }
            log.warn("commit %s: build failed — running degraded analysis", args.getCommitId());
            new OutputSerializer(true, logFactory.getLogger(OutputSerializer.class)).accept(ctx);
            if (judged(request, args, review, ctx, log)) {
                AnalysisAcceptedModel accepted = submit(request, ctx, log);
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
                HotspotSnapshots.submit(
                        request.getApiUrl(),
                        credential(request, log),
                        request.getConnectTimeoutSeconds(),
                        request.getReadTimeoutSeconds(),
                        request.getRootCode(),
                        snapshot.get(),
                        hiding(request),
                        log);
            }
        } catch (Exception err) {
            log.warn("hotspot snapshot for %s not produced, the previous one stays: %s", ctx.getArgs().getCommitId(), err);
        }
    }
    private static FixCommits fixCommits(AnalysisRequest request, Instant since, Log log) throws Exception {
        return FixCommits.fetch(request.getApiUrl(), credential(request, log), request.getConnectTimeoutSeconds(), request.getReadTimeoutSeconds(),
                request.getRootCode(), since, log);
    }
    private static String resolveCommitId(AnalysisRequest request, Repository git) throws Exception {
        return JGit.resolveCommit(git, Optional.ofNullable(request.getCommitId()).orElse(Constants.HEAD));
    }
    private static void runEngine(AnalysisRequest request, RunArgs args, Optional<LocalReviewRun> review, LogFactory logFactory) throws Exception {
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

                /** The YAML is written first, so a submission the backend rejects still leaves it to inspect. */
                if (analysis.isRevertCommit()) {
                    reportRevert(request, args, ctx, log);
                } else if (judged(request, args, review, ctx, log)) {
                    try (ProgressStage stage = ProgressStage.start(args, "submit")) {
                        AnalysisAcceptedModel accepted = submit(request, ctx, log);
                        log.info("accepted analysis id: %s status: %s", accepted.getAnalysisId(), accepted.getStatus());
                        stage.succeeded();
                    }
                }
                reportHotspots(request, ctx, log);
            }
        }
    }
    /**
     * Local mode, as on Maven: the review of the checked-out commit runs next to the analysis. Only a commit that
     * changes a file the analysis reads is reviewed: the review spends the organization's LLM budget through the
     * proxy, which an excluded commit has no use for.
     */
    private static Optional<LocalReviewRun> startReview(AnalysisRequest request, RunArgs args, LogFactory logFactory, Log log) throws Exception {
        boolean analyzable = CommitExclusions.hasAnalyzableFile(JGit.changedFileNames(args.getGit(), args.getCommitId()), DefaultLanguageProcessors.supportedExtensions());
        if (BooleanUtils.and(new boolean[] { args.isReviewEnabled(), analyzable })) {
            CodiqoCredential credential = credential(request, log);
            LocalReviewRun toReturn = LocalReviewRun.start(
                    args,
                    args.getGit().getWorkTree().toPath(),
                    args.getCommitId(),
                    LocalReviewRun.endpoint(args, request.getResourceUrl(), request.getRootCode(), () -> credential),
                    ConventionGuidance.read(args, logFactory.getLogger(ConventionGuidance.class)),
                    () -> ProjectTags.fetch(request.getApiUrl(), credential, request.getConnectTimeoutSeconds(), request.getReadTimeoutSeconds(), request.getRootCode(), log),
                    DefaultLanguageProcessors.reviewLanguages(),
                    logFactory);
            log.info("local review of %s started next to the analysis", args.getCommitId());
            return Optional.of(toReturn);
        }
        return Optional.empty();
    }
    /**
     * Joins the review into the submission; whether the commit is to be submitted. A review that failed leaves the commit
     * unjudged, so it is excluded, as on Maven: under its build failure when its build failed too, so an operator's
     * re-inclusion survives a re-analysis, else as LOCAL_REVIEW_FAILURE. An interrupt is the run being stopped, not a
     * failed review. A commit without a review is submitted as it is.
     */
    @VisibleForTesting
    public static boolean judged(AnalysisRequest request, RunArgs args, Optional<LocalReviewRun> review, SubmissionContext ctx, Log log) throws Exception {
        if (review.isEmpty()) {
            return true;
        }
        try {
            review.get().attachTo(ctx);
            return true;
        } catch (LocalReviewException err) {
            /** as on Maven: a stopped run and a review the provider failed fail the run, which halts the project */
            if (BooleanUtils.or(new boolean[] { Thread.currentThread().isInterrupted(), err.isProviderFailure() })) {
                throw err;
            }
            log.error("commit %s excluded: %s", args.getCommitId(), err.getMessage());
            AnalysisBuildFailureModel buildFailure = ctx.getSubmissionModel().getBuildFailure();
            if (Objects.nonNull(buildFailure)) {
                reportExclusion(request, args, args.getCommitId(), new Exclusion(buildFailure.getReason(), buildFailure.getCategory()),
                        ctx.getSubmissionModel().getFiles(), log, buildFailure.getDetail(), ctx.getSubmissionModel().getProjectMetrics());
            } else {
                reportExclusion(request, args, args.getCommitId(), new Exclusion(err.getMessage(), AnalysisExcludeCategory.LOCAL_REVIEW_FAILURE),
                        List.of(), log, ExceptionUtils.getStackTrace(err), null);
            }
            return false;
        }
    }
    /** with the review's trace when it was reviewed: the trace goes with the review it explains, never alone */
    private static AnalysisAcceptedModel submit(AnalysisRequest request, SubmissionContext ctx, Log log) throws Exception {
        SubmissionAssembly.stampAnalysisTime(ctx);
        File trace = ReviewTrace.file(ctx.getArgs(), ctx.getArgs().getCommitId());
        AnalysisAcceptedModel toReturn;
        if (Objects.nonNull(ctx.getSubmissionModel().getLocalReview())) {
            toReturn = AnalysisSubmitter.submit(request.getApiUrl(), credential(request, log), request.getConnectTimeoutSeconds(),
                    request.getReadTimeoutSeconds(), ctx.getSubmissionModel(), trace, hiding(request), log);
        } else {
            toReturn = AnalysisSubmitter.submit(request.getApiUrl(), credential(request, log), request.getConnectTimeoutSeconds(),
                    request.getReadTimeoutSeconds(), ctx.getSubmissionModel(), hiding(request), log);
        }
        ctx.getArgs().setSubmissionAccepted(true);
        return toReturn;
    }
    private static SourceHiding hiding(AnalysisRequest request) {
        return SourceHiding.of(request.isSourceHidden(), Optional.ofNullable(request.getSourceHashKey()));
    }
    /** each review setting left unset keeps its {@link RunArgs} default; the trace goes beside the analysis's output */
    @VisibleForTesting
    public static void applyReviewSettings(AnalysisRequest request, RunArgs args) {
        args.setReviewEnabled(request.isReview());
        Optional.ofNullable(request.getReviewExecutable()).filter(StringUtils::isNotBlank).ifPresent(args::setReviewExecutable);
        Optional.ofNullable(request.getReviewBaseUrl()).filter(StringUtils::isNotBlank).ifPresent(args::setReviewBaseUrl);
        Optional.ofNullable(request.getReviewCoordinatorModel()).filter(StringUtils::isNotBlank).ifPresent(args::setReviewCoordinatorModel);
        Optional.ofNullable(request.getReviewReviewerModel()).filter(StringUtils::isNotBlank).ifPresent(args::setReviewReviewerModel);
        Optional.ofNullable(request.getReviewTimeoutMinutes()).filter(StringUtils::isNotBlank).map(Long::parseLong)
                .ifPresent(minutes -> args.setReviewTimeout(Duration.ofMinutes(minutes)));
        Optional.ofNullable(request.getReviewTemperature()).filter(StringUtils::isNotBlank).map(Double::parseDouble).ifPresent(args::setReviewTemperature);
        Optional.ofNullable(request.getReviewTopP()).filter(StringUtils::isNotBlank).map(Double::parseDouble).ifPresent(args::setReviewTopP);
        Optional.ofNullable(request.getReviewRelayRetries()).filter(StringUtils::isNotBlank).map(Integer::parseInt).ifPresent(args::setReviewRelayRetries);
        Optional.ofNullable(request.getReviewRelayTimeoutSeconds()).filter(StringUtils::isNotBlank).map(Long::parseLong)
                .ifPresent(seconds -> args.setReviewRelayRequestTimeout(Duration.ofSeconds(seconds)));
        args.setReviewTraceDirectory(new File(Optional.ofNullable(request.getReviewTraceDirectory()).filter(StringUtils::isNotBlank).orElse(request.getOutputDirectory())));
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
