package io.codiqo.submit.outcome;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.google.common.annotations.VisibleForTesting;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.testing.TestRun;
import io.codiqo.api.testing.TestRuns;
import io.codiqo.client.ApiException;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.llm.review.ReviewTrace;
import io.codiqo.llm.review.ReviewVerdict;
import io.codiqo.submit.CodiqoServer;
import io.codiqo.submit.CommitBuildFailedException;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.CommitExclusions.Exclusion;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.ProjectHaltedException;
import io.codiqo.submit.ProjectHalts;
import io.codiqo.submit.SubmissionAssembly;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.hotspots.HotspotSnapshots;
import io.codiqo.submit.review.LocalReviewRun;
import io.codiqo.util.Fetch;
import io.codiqo.util.Interrupts;
import io.codiqo.util.JGit;
import io.codiqo.util.MemoryReport;
import io.codiqo.util.ProgressStage;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * One commit's run, from the commit as its build tool hands it over to its commit outcome. Maven and Gradle analyse a
 * commit by these rules, so a commit cannot end one way on one build tool and another way on the other: what differs
 * between them, how the commit is checked out and built and what its projects are, sits behind {@link CommitBuild}, and
 * every call to Codiqo behind {@link CodiqoServer}.
 *
 * <p>{@link #admit} decides from git alone, before the commit is checked out or built. {@link #run} checks the commit out
 * ({@link CommitBuild#checkout}) and takes it to its outcome, and halts the project on a failure on Codiqo's or the model
 * provider's side: such a run submits nothing, so the next scheduled run would analyse the same commit and pay for its
 * review again, every hour.
 */
public final class CommitRun {
    private final RunArgs args;
    private final CodiqoServer server;
    private final CommitBuild build;
    private final AnalysedProject project;
    private final OutputSerializer output;
    private final ReviewStarter reviews;
    private final LogFactory logFactory;
    private final Log log;
    /**
     * The server accepted the commit's submission. A failure after it has scored the commit, so it halts nothing: a halt
     * resumed with "skip" would otherwise exclude a commit that was already scored.
     */
    private boolean submitted;
    /**
     * The halt this run recorded, which stays the commit's outcome: a failure closing the language servers or the review
     * after it is attached to the halt's cause rather than halting the project a second time over that failure.
     */
    private Optional<CommitOutcome.Halted> halted = Optional.empty();

    /** @param output where the analysis is written before it is sent, so a submission the server refuses can be read */
    public CommitRun(
            RunArgs args,
            CodiqoServer server,
            CommitBuild build,
            AnalysedProject project,
            OutputSerializer output,
            ReviewStarter reviews,
            LogFactory logFactory) {
        this.args = Objects.requireNonNull(args);
        this.server = Objects.requireNonNull(server);
        this.build = Objects.requireNonNull(build);
        this.project = Objects.requireNonNull(project);
        this.output = Objects.requireNonNull(output);
        this.reviews = Objects.requireNonNull(reviews);
        this.logFactory = Objects.requireNonNull(logFactory);
        this.log = logFactory.getLogger(CommitRun.class);
    }
    /**
     * The gates that need only git and the run's rules (merges, the author and include rules): the commit's outcome
     * when one of them excludes it, before anything is checked out, built or paid for. A project an administrator halted
     * throws {@link ProjectHaltedException} here, before any review starts, and the run fails without halting it again.
     */
    public Optional<CommitOutcome> admit() throws Exception {
        Optional<Exclusion> excluded = CommitExclusions.beforeAnalysis(args, server.indexedCommits());
        if (excluded.isPresent()) {
            return Optional.of(excluded(exclusion(excluded.get(), List.of())));
        }
        server.requireRunning();
        return Optional.empty();
    }
    /** the commit, checked out, to its outcome: a failure is the outcome's, never thrown */
    public CommitOutcome run() {
        try (AutoCloseable checkout = build.checkout()) {
            return analyse();
        } catch (Exception err) {
            if (halted.isPresent()) {
                log.warn("commit %s: %s after the project was halted", args.getCommitId(), ExceptionUtils.getRootCauseMessage(err));
                halted.get().getCause().addSuppressed(err);
                return halted.get();
            }
            if (BooleanUtils.or(new boolean[] { ProjectHalts.haltsNothing(err), submitted })) {
                return new CommitOutcome.Deferred(err);
            }
            return halt(ProjectHalts.reasonOf(err), err);
        }
    }
    /**
     * The best-effort source index over the work tree of a commit whose build failed, falling back to the raw diff when
     * it cannot be read. PMD parsing a tree whose build just failed is exactly where an I/O failure is expected: without
     * the fallback the commit is never reported, and it is offered as missing on every later run.
     */
    @VisibleForTesting
    public SubmissionContext degradedSubmission(BuildVerdict.Failed failed) throws Exception {
        Optional<ProjectSpec> workTree = Optional.empty();
        try {
            workTree = Optional.of(build.sourceOnlyProject());
            args.getProjects().add(workTree.get());
            return sourceOnlySubmission(failed);
        } catch (IOException err) {
            log.warn("commit %s: source-only degraded index failed (%s) — falling back to diff-only scoring", args.getCommitId(), ExceptionUtils.getRootCauseMessage(err));
            /**
             * The whole-work-tree project exists only to give the source-only index an owner for every file. The
             * diff-only submission indexes nothing, so it would be sent as a module with no source roots.
             */
            workTree.ifPresent(args.getProjects()::remove);
            return diffOnlySubmission(failed, err);
        }
    }
    private CommitOutcome analyse() throws Exception {
        args.setAnalysisStartedAt(Instant.now());

        /**
         * A commit that changes nothing analysable is excluded from git alone, before it is built: on a tree whose build
         * is broken it is NO_ANALYZABLE_DIFF, never BUILD_FAILURE, and it never fails a run told not to tolerate a broken
         * build.
         */
        Optional<Exclusion> nothingToAnalyse = CommitExclusions.beforeBuild(args.getGit(), args.getCommitId(), DefaultLanguageProcessors.supportedExtensions());
        if (nothingToAnalyse.isPresent()) {
            return excludedWithDiff(new JGitDeltaAnalyzer(logFactory, args).analyze(), nothingToAnalyse.get());
        }

        /**
         * A failed build leaves no trustworthy class output, so the full analysis cannot run: the commit is scored from
         * its source and its review, or excluded for its build. A compile the author broke is reviewed, since that review
         * is what scores it; one that failed on the environment starts none, so a build that cannot run costs no review.
         */
        if (build.compile() instanceof BuildVerdict.Failed failed) {
            if (args.isSkipOnBuildFailure()) {
                Optional<LocalReviewRun> review = Optional.empty();
                if (failed.isAuthors()) {
                    review = startReview();
                }
                try {
                    return degraded(failed, review);
                } finally {
                    close(review);
                }
            }
            throw untolerated(failed);
        }

        /** the language server the full analysis needs is downloaded before the review is paid for */
        try (Fetch fetch = new Fetch(args)) {
            fetch.downloadJdtls(logFactory, args);
        }

        Optional<LocalReviewRun> review = startReview();
        try {
            BuildVerdict tested = build.test();
            /**
             * No skipOnBuildFailure check here: Maven's test fork raises an untolerated failure itself, as it does for its
             * compile, and what fails after a test run that passed, the host building the module models, was always
             * degraded or excluded whatever the setting. Refused here, such a commit was deferred and rebuilt, and
             * its review paid for again, on every scheduled run.
             */
            if (tested instanceof BuildVerdict.Failed failed) {
                return degraded(failed, review);
            }
            if (tested instanceof BuildVerdict.Passed passed) {
                passed.getTestRun().ifPresent(this::hold);
            }
            return analyseInFull(review);
        } finally {
            close(review);
        }
    }
    private CommitOutcome analyseInFull(Optional<LocalReviewRun> review) throws Exception {
        try (ClassGraphSpec scan = build.projects()) {
            try (Fetch fetch = new Fetch(args); LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                log.info(MemoryReport.snapshot("before language server load"));
                registry.load();
                log.info(MemoryReport.snapshot("after language server load"));
                CommitAnalysis analysis = new JGitDeltaAnalyzer(logFactory, args).analyze();

                Optional<Exclusion> excluded = CommitExclusions.afterDelta(analysis, registry.extensions());
                if (excluded.isPresent()) {
                    return excludedWithDiff(analysis, excluded.get());
                }

                IndexingSummary index = registry.index(analysis);
                log.info(MemoryReport.snapshot("after index"));
                registry.identifyAffectedSymbols(index, analysis);
                /**
                 * A capture failure here comes after a build that passed: a module whose tests ran produced no coverage
                 * data, or the coverage it produced predates the sources. Both are a broken measurement on Codiqo's
                 * side, never the author's, so the run halts and is investigated: scored as the author's broken build,
                 * a commit whose agent did not attach to eight modules was scored with penalties only.
                 */
                registry.collectAndCapture(index, analysis);
                log.info(MemoryReport.snapshot("after coverage capture"));

                SubmissionContext ctx = context(index, analysis);
                build.populateProjectModel(ctx);
                SubmissionAssembly.full(ctx, build::printSummary);
                output.accept(ctx);

                CommitOutcome toReturn;
                if (analysis.isRevertCommit()) {
                    toReturn = excludedRevert(ctx);
                } else {
                    toReturn = judged(review, ctx);
                }
                /** runs once the commit's own outcome is settled, and only a failure of the run's own skips it */
                if (BooleanUtils.or(new boolean[] { toReturn instanceof CommitOutcome.Submitted, toReturn instanceof CommitOutcome.Excluded })) {
                    reportHotspots(ctx);
                }
                return toReturn;
            }
        } finally {
            closeProjects();
        }
    }
    /**
     * Local mode judges a commit with its review, which reads the code through git and needs no build, so a commit whose
     * build the author broke and whose review started is scored by rules, from the review's assessment, as a degraded
     * commit; excluding it would drop a review already paid for. Without a review, or for a failure of the environment,
     * the commit is excluded for its build, with its files and the failure's detail.
     */
    private CommitOutcome degraded(BuildVerdict.Failed failed, Optional<LocalReviewRun> review) throws Exception {
        SubmissionContext ctx = degradedSubmission(failed);

        List<FileChangeModel> files = ctx.getSubmissionModel().getFiles();
        if (BooleanUtils.and(new boolean[] { review.isPresent(), failed.isAuthors(), CollectionUtils.isNotEmpty(files) })) {
            if (ctx.getAnalysis().isRevertCommit()) {
                return excludedRevert(ctx);
            }
            log.warn("commit %s: build failed — running degraded analysis", args.getCommitId());
            output.accept(ctx);
            return judged(review, ctx);
        }

        return excluded(new AnalysisExcludeModel()
                .reason(failed.getReason())
                .category(failed.getCategory())
                .detail(failed.getDetail())
                .files(files)
                .projectMetrics(ctx.getSubmissionModel().getProjectMetrics()));
    }
    /** the review joined into the submission, and the commit submitted unless the review failed */
    private CommitOutcome judged(Optional<LocalReviewRun> review, SubmissionContext ctx) throws Exception {
        if (review.isEmpty()) {
            return submit(ctx);
        }
        ReviewVerdict verdict = review.get().attachTo(ctx);
        if (verdict instanceof ReviewVerdict.Failed failed) {
            return failedReview(failed, ctx);
        }
        return submit(ctx);
    }
    /**
     * What a failed review does to the commit: only a review that failed on the commit excludes it. One that failed on
     * the model provider or on Codiqo's side halts the project under the verdict's reason, the provider's failed answers
     * kept whole at its end; a review that was stopped, or that ended while the run was being stopped, halts nothing.
     */
    private CommitOutcome failedReview(ReviewVerdict.Failed failed, SubmissionContext ctx) throws Exception {
        if (BooleanUtils.or(new boolean[] { failed instanceof ReviewVerdict.Stopped, Interrupts.stopped(failed.getError()) })) {
            return new CommitOutcome.Deferred(failed.getError());
        }
        if (failed instanceof ReviewVerdict.CommitFailure commit) {
            return excluded(LocalReviewRun.exclusion(commit, ctx, log));
        }
        return halt(ProjectHalts.reason(ReviewVerdict.REVIEW_FAILED + failed.getFailure(), failed.getProviderErrors()),
                new IOException(ReviewVerdict.REVIEW_FAILED + failed.getReason(), failed.getError()));
    }
    /** with the review's trace when the commit was reviewed: the review wrote it before it was joined */
    private CommitOutcome submit(SubmissionContext ctx) throws Exception {
        SubmissionAssembly.stampAnalysisTime(ctx);
        try (ProgressStage stage = ProgressStage.start(args, "submit")) {
            Optional<File> trace = Optional.empty();
            if (Objects.nonNull(ctx.getSubmissionModel().getLocalReview())) {
                trace = Optional.of(ReviewTrace.file(args, args.getCommitId()));
            }
            AnalysisAcceptedModel accepted = server.submit(ctx.getSubmissionModel(), trace);
            submitted = true;
            log.info("accepted analysis id: %s status: %s", accepted.getAnalysisId(), accepted.getStatus());
            stage.succeeded();
            return new CommitOutcome.Submitted(accepted);
        }
    }
    /**
     * The revert itself is excluded unconditionally, and with excludeRevertedCommits the original is retroactively
     * excluded too, so reverted work stops counting. A 404 means the original predates the indexing window; it is logged
     * rather than thrown, so an old revert target does not fail the run.
     */
    private CommitOutcome excludedRevert(SubmissionContext ctx) throws Exception {
        CommitOutcome toReturn = excluded(new AnalysisExcludeModel()
                .reason("revert commit (no LLM scoring performed)")
                .category(AnalysisExcludeCategory.REVERT_COMMIT)
                .files(ctx.getSubmissionModel().getFiles()));

        if (args.isExcludeRevertedCommits()) {
            String revertedSha = ctx.getAnalysis().getRevertedCommitId();
            try {
                exclude(revertedSha, new AnalysisExcludeModel()
                        .reason(String.format("reverted by commit %s", JGit.shortSha(args.getCommitId())))
                        .category(AnalysisExcludeCategory.REVERTED)
                        .files(List.of()));
            } catch (ApiException err) {
                if (err.getCode() == HttpResponseStatus.NOT_FOUND.code()) {
                    log.warn("reverted commit %s not known to backend (outside indexing window?) — skipping its exclusion", revertedSha);
                } else {
                    throw err;
                }
            }
        }
        return toReturn;
    }
    /** the excluded commit still carries the raw git diff, so the server records its per-file changes */
    private CommitOutcome excludedWithDiff(CommitAnalysis analysis, Exclusion exclusion) throws Exception {
        SubmissionContext ctx = context(null, analysis);
        SubmissionAssembly.excluded(ctx);
        return excluded(exclusion(exclusion, ctx.getSubmissionModel().getFiles()));
    }
    private CommitOutcome excluded(AnalysisExcludeModel exclusion) throws Exception {
        exclude(args.getCommitId(), exclusion);
        return new CommitOutcome.Excluded(exclusion);
    }
    private void exclude(String commitSha, AnalysisExcludeModel exclusion) throws Exception {
        log.warn("commit %s skipped: %s", commitSha, exclusion.getReason());
        server.exclude(commitSha, exclusion);
    }
    /**
     * Records the halt and tells the run's watcher to stop the whole run (ProgressStage.abort): every further commit of
     * the run would fail the same way.
     */
    private CommitOutcome halt(String reason, Exception cause) {
        boolean recorded = server.halt(args.getCommitId(), reason, ExceptionUtils.getStackTrace(cause));
        ProgressStage.abort(args, reason, ProjectHalts.traceOf(args, args.getCommitId()));
        halted = Optional.of(new CommitOutcome.Halted(cause, recorded));
        return halted.get();
    }
    /**
     * Only a commit that changes a file the analysis reads is reviewed: the review spends the organization's model
     * budget through the proxy, which a commit the analysis then excludes has no use for.
     */
    private Optional<LocalReviewRun> startReview() throws Exception {
        boolean analyzable = CommitExclusions.hasAnalyzableFile(JGit.changedFileNames(args.getGit(), args.getCommitId()), DefaultLanguageProcessors.supportedExtensions());
        Optional<LocalReviewRun> toReturn = Optional.empty();
        if (BooleanUtils.and(new boolean[] { args.isReviewEnabled(), analyzable })) {
            toReturn = Optional.of(reviews.start(args, server, logFactory));
            log.info("local review of %s started next to the analysis", args.getCommitId());
        }
        return toReturn;
    }
    /**
     * A run where most tests failed is reviewed and scored all the same, and the server holds its score until someone
     * accepts it (TestRuns.heldReason): such a run cannot tell a commit that broke what its tests share from an
     * environment the analysis cannot rebuild, and halting stopped the whole project on one commit's own defect.
     */
    private void hold(TestRun testRun) {
        log.info("test run: %d tests, %d failed, %d errors, %d skipped in %s", testRun.getTests(), testRun.getFailures(), testRun.getErrors(), testRun.getSkipped(), testRun.getTestTime());
        TestRuns.heldReason(testRun, args.getTestFailureHoldShare(), args.getTestFailureHoldMinTests()).ifPresent(reason -> {
            log.warn("%s: the commit is scored and its score held until someone accepts it", reason);
            args.setTestRunHeldReason(reason);
        });
        args.setTestRun(testRun);
    }
    /**
     * codiqo.skipOnBuildFailure=false asks for a hard error instead of an exclusion: a pipeline that opted out of
     * tolerating build failures does not go green on one. A compile that failed raises it before any review starts. It
     * is the author's failure and halts nothing: halting would hold the commit that fixes it.
     */
    private CommitBuildFailedException untolerated(BuildVerdict.Failed failed) {
        return new CommitBuildFailedException(String.format("commit %s: %s, and codiqo.skipOnBuildFailure is false%n%s",
                args.getCommitId(), failed.getReason(), failed.getDetail()));
    }
    /** a failure costs only the snapshot, the previous one stays, never the analysis */
    private void reportHotspots(SubmissionContext ctx) {
        try {
            Optional<HotspotSnapshotModel> snapshot = HotspotSnapshots.build(ctx, server::fixCommits, log);
            if (snapshot.isPresent()) {
                HotspotSnapshots.write(ctx, snapshot.get(), log);
                server.submitHotspots(snapshot.get());
            }
        } catch (Exception err) {
            log.warn("hotspot snapshot for %s not produced, the previous one stays: %s", args.getCommitId(), err);
        }
    }
    private SubmissionContext sourceOnlySubmission(BuildVerdict.Failed failed) throws Exception {
        CommitAnalysis analysis = new JGitDeltaAnalyzer(logFactory, args).analyze();

        SubmissionContext toReturn = DefaultLanguageProcessors.sourceOnlyIndex(args, analysis, logFactory, index -> {
            SubmissionContext ctx = context(index, analysis);
            build.populateProjectModel(ctx);
            SubmissionAssembly.degraded(ctx);
            return ctx;
        });
        toReturn.getSubmissionModel().setBuildFailure(SubmissionAssembly.buildFailure(failed.getReason(), failed.getCategory(), failed.getDetail()));
        return toReturn;
    }
    /**
     * Git diff and commit metadata only: no code units, no metrics. The build-failure detail records why the source-only
     * index was given up: a diff-only score is a different scoring regime, and a log line alone would not outlive the
     * log's retention.
     */
    private SubmissionContext diffOnlySubmission(BuildVerdict.Failed failed, IOException fallbackCause) throws Exception {
        SubmissionContext toReturn = context(null, new JGitDeltaAnalyzer(logFactory, args).analyze());

        build.populateProjectModel(toReturn);
        SubmissionAssembly.diffOnly(toReturn);
        toReturn.getSubmissionModel().setBuildFailure(SubmissionAssembly.buildFailure(
                failed.getReason(), failed.getCategory(), SubmissionAssembly.diffOnlyFallbackDetail(failed.getDetail(), fallbackCause)));
        return toReturn;
    }
    private SubmissionContext context(IndexingSummary index, CommitAnalysis analysis) {
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        return SubmissionContext.create(args, index, analysis, workTree, logFactory, project.getCode(), project.getName(), project.getClientInfo());
    }
    private void closeProjects() {
        for (ProjectSpec spec : args.getProjects()) {
            try {
                spec.close();
            } catch (IOException err) {
                log.warn("failed to close project spec %s: %s", spec, err.getMessage());
            }
        }
    }
    /** cancels a review still running, whose interrupt makes it stop its OpenCode server and delete the server's home */
    private static void close(Optional<LocalReviewRun> review) throws InterruptedException {
        if (review.isPresent()) {
            review.get().close();
        }
    }
    private static AnalysisExcludeModel exclusion(Exclusion exclusion, List<FileChangeModel> files) {
        return new AnalysisExcludeModel().reason(exclusion.getReason()).category(exclusion.getCategory()).files(files);
    }
}
