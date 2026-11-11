package io.codiqo.submit;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import io.codiqo.api.RunArgs;
import io.codiqo.api.testing.TestRun;
import io.codiqo.client.model.TestRunModel;
import lombok.experimental.UtilityClass;

/**
 * The populator sequence every submission runs, in one place. Each build tool contributes its own project-model
 * populator first and then hands the context here.
 */
@UtilityClass
public class SubmissionAssembly {
    /**
     * Grep marker for the one transition that silently changes the scoring regime. It is written into the persisted
     * build-failure detail as well as the log, because a log line outlives nothing: retention is finite, and the
     * question of why an analysis scored the way it did outlives it.
     */
    public static final String DIFF_ONLY_FALLBACK_MARKER = "[codiqo] source-only degraded index failed";

    /** a fully indexed submission: coverage, static analysis and duplication were all captured. */
    public void full(SubmissionContext ctx) {
        full(ctx, unused -> { });
    }
    /** as {@link #full(SubmissionContext)}, with a hook between the populators and {@link #finish}. */
    public void full(SubmissionContext ctx, Consumer<SubmissionContext> afterPopulators) {
        new CommitModelPopulator().accept(ctx);
        new ModuleLevelMetricsPopulator().accept(ctx);
        new FileAnalysisPopulator().accept(ctx);
        new EffectiveChangePopulator().accept(ctx);
        new IndexModelPopulator().accept(ctx);

        new DuplicationReportPopulator().accept(ctx);
        new MetricsAggregator().accept(ctx);
        new ExcludedCoverageClassPopulator().accept(ctx);

        afterPopulators.accept(ctx);
        finish(ctx);
    }
    /**
     * the source-only degraded submission for a failed build. Only the driver-score statistics are populated, so the
     * quality and coverage aggregates stay absent rather than being fabricated as zeros.
     */
    public void degraded(SubmissionContext ctx) {
        new CommitModelPopulator().accept(ctx);
        new ModuleLevelMetricsPopulator().accept(ctx);
        new FileAnalysisPopulator().accept(ctx);
        new EffectiveChangePopulator().accept(ctx);

        MetricsAggregator.populateDriverMetrics(ctx);

        finish(ctx);
    }
    /** git diff and commit metadata only: no code units, no coverage, no project-level metrics. */
    public void diffOnly(SubmissionContext ctx) {
        new CommitModelPopulator().accept(ctx);
        new FileAnalysisPopulator().accept(ctx);

        finish(ctx);
    }
    /**
     * Records why a degraded analysis fell back to {@link #diffOnly}, in the build-failure detail every degraded row
     * already persists. A diff-only submission carries no project metrics, so blocks are priced on raw line counts
     * instead of the project's own distribution, and with a zero baseline the global cap cannot bind either: a
     * materially different scoring regime from a source-only degraded run, which both build tools must record alike.
     */
    public String diffOnlyFallbackDetail(String detail, IOException err) {
        String note = String.format("%s (%s) — scored diff-only: no project metrics, so driver scores are raw line counts and the global cap does not bind",
                DIFF_ONLY_FALLBACK_MARKER,
                ExceptionUtils.getRootCauseMessage(err));
        if (StringUtils.isBlank(detail)) {
            return note;
        }
        return detail + StringUtils.repeat(CharUtils.LF, 2) + note;
    }
    /**
     * an excluded commit, submitted as an exclusion rather than scored. Deliberately does not call {@link #finish}:
     * nothing scores this submission.
     */
    public void excluded(SubmissionContext ctx) {
        new FileAnalysisPopulator().accept(ctx);
    }
    private static TestRunModel testRun(RunArgs args, TestRun run) {
        TestRunModel toReturn = new TestRunModel()
                .tests(run.getTests())
                .failures(run.getFailures())
                .errors(run.getErrors())
                .skipped(run.getSkipped())
                .testTimeMs(run.getTestTime().toMillis())
                .skippedTests(run.getSkippedTests());

        Optional.ofNullable(args.getCompilePhaseTook()).map(Duration::toMillis).ifPresent(toReturn::compilePhaseMs);
        Optional.ofNullable(args.getTestPhaseTook()).map(Duration::toMillis).ifPresent(toReturn::testPhaseMs);
        Optional.ofNullable(args.getTestRunHeldReason()).ifPresent(toReturn::heldReason);
        return toReturn;
    }
    /**
     * The whole analysis's time, stamped right before the submission is sent: assembly runs before the review is
     * joined, and the review is usually the longest part of the analysis.
     */
    public void stampAnalysisTime(SubmissionContext ctx) {
        TestRunModel testRun = ctx.getSubmissionModel().getTestRun();
        Instant started = ctx.getArgs().getAnalysisStartedAt();
        if (BooleanUtils.and(new boolean[] { Objects.nonNull(testRun), Objects.nonNull(started) })) {
            testRun.setAnalysisMs(Duration.between(started, Instant.now()).toMillis());
        }
    }
    /** Scoring runs server-side, so the effective config and the test run travel with the submission. */
    private void finish(SubmissionContext ctx) {
        RunArgs args = ctx.getArgs();

        ctx.getSubmissionModel().setScoringConfig(ScoringConfigs.map(args));
        Optional.ofNullable(args.getTestRun()).map(run -> testRun(args, run)).ifPresent(ctx.getSubmissionModel()::setTestRun);
    }
}
