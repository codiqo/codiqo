package io.codiqo.submit.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.AnalysisBuildFailureModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.llm.review.ReviewProviderException;
import io.codiqo.llm.review.ReviewVerdict;
import io.codiqo.submit.SubmissionContext;

/**
 * A review's verdict, settled where the caller waits for it, and what it does to its commit on both build tools: only
 * a failure on the commit excludes it; a failure on the provider or on Codiqo's side fails the run, which halts the
 * project, and a stopped review fails it too, halting nothing.
 */
class LocalReviewVerdictTest {
    private static final String COMMIT = "1a2b3c4d";
    private static final Log LOG = new NoopLog();
    private static final LogFactory LOGS = clazz -> LOG;

    /** the project's tag vocabulary is fetched on the review's thread, before a reviewer exists to settle anything */
    @Test
    void aReviewThatFailedBeforeItsReviewerSettledAnythingIsCodiqos() throws Exception {
        try (LocalReviewRun run = LocalReviewRun.of(BackgroundReview.start(() -> {
            throw new IOException("listing the project's tags failed with 503 after 3 attempts");
        }), List.of(), LOGS)) {
            ReviewVerdict.CodiqoFailure failed = assertInstanceOf(ReviewVerdict.CodiqoFailure.class, run.attachTo(context()));

            assertEquals("listing the project's tags failed with 503 after 3 attempts", failed.getError().getMessage());
        }
    }
    /** without a reviewer, no relay counted a provider failure, so a review that outlived its limits is the commit's */
    @Test
    void aReviewThatOutlivedEveryLimitOfItsOwnIsUnanswered() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SubmissionContext ctx = context();
        ctx.getArgs().setReviewStartupTimeout(Duration.ofMillis(10));
        ctx.getArgs().setReviewTimeout(Duration.ofMillis(10));
        try (LocalReviewRun run = LocalReviewRun.of(BackgroundReview.start(() -> {
            release.await();
            return null;
        }), List.of(), LOGS)) {
            ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class, run.attachTo(ctx));

            assertEquals("local review did not finish within PT0.04S", failed.getError().getMessage());
        } finally {
            release.countDown();
        }
    }
    @Test
    void aRunStoppedWhileItWaitedIsStopped() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (LocalReviewRun run = LocalReviewRun.of(BackgroundReview.start(() -> {
            release.await();
            return null;
        }), List.of(), LOGS)) {
            Thread.currentThread().interrupt();
            try {
                ReviewVerdict.Stopped stopped = assertInstanceOf(ReviewVerdict.Stopped.class, run.attachTo(context()));

                assertThrows(InterruptedException.class, () -> LocalReviewRun.exclusionOf(stopped, context(), LOG), "the run fails, and its interrupt halts nothing");
            } finally {
                assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
            }
        } finally {
            release.countDown();
        }
    }
    @Test
    void aReviewThatFailedOnTheCommitExcludesIt() throws Exception {
        AnalysisExcludeModel excluded = LocalReviewRun.exclusionOf(new ReviewVerdict.CommitFailure(new IOException("the coordinator started no reviewer")), context(), LOG)
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.LOCAL_REVIEW_FAILURE, excluded.getCategory());
        assertEquals("local review failed: the coordinator started no reviewer", excluded.getReason());
        assertTrue(Strings.CS.contains(excluded.getDetail(), "the coordinator started no reviewer"), "the stack trace is the detail");
        assertEquals(List.of(), excluded.getFiles());
    }
    @Test
    void aBuildFailedCommitWhoseReviewFailedOnItKeepsTheBuildFailure() throws Exception {
        SubmissionContext ctx = context();
        ctx.getSubmissionModel().setBuildFailure(new AnalysisBuildFailureModel().reason("[ERROR] COMPILATION ERROR").category(AnalysisExcludeCategory.BUILD_FAILURE).detail("cannot find symbol"));
        ctx.getSubmissionModel().setFiles(List.of(new FileChangeModel().path("src/A.java")));

        AnalysisExcludeModel excluded = LocalReviewRun.exclusionOf(new ReviewVerdict.CommitFailure(new IOException("refused")), ctx, LOG).orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, excluded.getCategory(), "an operator's re-inclusion of a build failure survives only under its category");
        assertEquals("[ERROR] COMPILATION ERROR", excluded.getReason());
        assertEquals("cannot find symbol", excluded.getDetail());
        assertEquals(ctx.getSubmissionModel().getFiles(), excluded.getFiles());
    }
    @Test
    void aReviewThatFailedOnTheProviderFailsTheRunWithTheProvidersAnswers() {
        IOException unanswered = new IOException("session ses_1 ended its turn with provider.rate-limit: Too Many Requests");
        ReviewVerdict verdict = new ReviewVerdict.ProviderFailure(unanswered, "the LLM provider answered 429 40 times during the review");

        ReviewProviderException err = assertThrows(ReviewProviderException.class, () -> LocalReviewRun.exclusionOf(verdict, context(), LOG));

        assertEquals("the LLM provider answered 429 40 times during the review", err.getProviderSummary(), "the halt's reason keeps it whole");
        assertSame(unanswered, err.getCause());
    }
    /** an OpenCode that would not start is Codiqo's failure: the project halts rather than the commit being excluded */
    @Test
    void aReviewThatFailedOnCodiqosSideFailsTheRun() {
        IOException startup = new IOException("opencode serve exited with code 1");

        IOException err = assertThrows(IOException.class, () -> LocalReviewRun.exclusionOf(new ReviewVerdict.CodiqoFailure(startup), context(), LOG));

        assertEquals("local review failed: opencode serve exited with code 1", err.getMessage());
        assertSame(startup, err.getCause());
    }
    private static SubmissionContext context() {
        RunArgs args = new RunArgs();
        args.setCommitId(COMMIT);
        return SubmissionContext.builder().args(args).submissionModel(new AnalysisSubmissionModel()).build();
    }
    private static final class NoopLog implements Log {
        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public void log(Level level, String message, Object... formatArgs) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
