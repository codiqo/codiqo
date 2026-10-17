package io.codiqo.submit;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.testing.TestRun;
import io.codiqo.client.model.TestRunModel;
import io.codiqo.llm.ConventionGuidance;
import lombok.experimental.UtilityClass;

/**
 * The populator sequence every submission runs, in one place. Each build tool contributes its own project-model
 * populator first and then hands the context here.
 */
@UtilityClass
public class SubmissionAssembly {
    /** a fully indexed submission: coverage, static analysis and duplication were all captured. */
    public void full(SubmissionContext ctx) throws IOException {
        full(ctx, unused -> { });
    }
    /** as {@link #full(SubmissionContext)}, with a hook between the populators and {@link #finish}. */
    public void full(SubmissionContext ctx, Consumer<SubmissionContext> afterPopulators) throws IOException {
        new CommitModelPopulator().accept(ctx);
        new ModuleLevelMetricsPopulator().accept(ctx);
        new FileAnalysisPopulator().accept(ctx);
        new EffectiveChangePopulator().accept(ctx);
        new IndexModelPopulator().accept(ctx);

        new DuplicationReportPopulator().accept(ctx);
        new MetricsAggregator().accept(ctx);
        new ExcludedCoverageClassPopulator().accept(ctx);

        afterPopulators.accept(ctx);
        finish(ctx, true);
    }
    /**
     * the source-only degraded submission for a failed build. Only the driver-score statistics are populated, so the
     * quality and coverage aggregates stay absent rather than being fabricated as zeros.
     */
    public void degraded(SubmissionContext ctx) throws IOException {
        new CommitModelPopulator().accept(ctx);
        new ModuleLevelMetricsPopulator().accept(ctx);
        new FileAnalysisPopulator().accept(ctx);
        new EffectiveChangePopulator().accept(ctx);

        MetricsAggregator.populateDriverMetrics(ctx);

        finish(ctx, false);
    }
    /** git diff and commit metadata only: no code units, no coverage, no project-level metrics. */
    public void diffOnly(SubmissionContext ctx) throws IOException {
        new CommitModelPopulator().accept(ctx);
        new FileAnalysisPopulator().accept(ctx);

        finish(ctx, false);
    }
    /**
     * an excluded commit, submitted as an exclusion rather than scored. Deliberately does not call {@link #finish}:
     * nothing scores this submission, and reading the instruction files can throw.
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
    /**
     * Scoring runs server-side, so the effective config and the repository's own instruction files are collected
     * here — the last point that still has a work tree — and travel with the submission.
     */
    private void finish(SubmissionContext ctx, boolean instructionsRequired) throws IOException {
        RunArgs args = ctx.getArgs();
        Log log = ctx.getLogFactory().getLogger(ConventionGuidance.class);

        ctx.getSubmissionModel().setScoringConfig(ScoringConfigs.map(args));
        Optional.ofNullable(args.getTestRun()).map(run -> testRun(args, run)).ifPresent(ctx.getSubmissionModel()::setTestRun);

        /**
         * an over-budget instruction set has to surface on a scored submission, but not on the other paths: the
         * callers' fallbacks catch IOException only, so an unchecked throw there loses the commit entirely. Only
         * that misconfiguration is tolerated — IllegalStateException is the one unchecked type ConventionGuidance
         * raises, and catching RuntimeException wholesale would swallow a defect as a missing hint.
         */
        if (instructionsRequired) {
            ctx.getSubmissionModel().setAgentInstructions(StringUtils.trimToNull(ConventionGuidance.read(args, log)));
            return;
        }
        try {
            ctx.getSubmissionModel().setAgentInstructions(StringUtils.trimToNull(ConventionGuidance.read(args, log)));
        } catch (IOException | IllegalStateException err) {
            log.warn("agent instructions omitted from this submission: %s", ExceptionUtils.getRootCauseMessage(err));
        }
    }
}
