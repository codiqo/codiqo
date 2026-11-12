package io.codiqo.submit.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

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
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.llm.review.ReviewVerdict;
import io.codiqo.submit.SubmissionContext;

/**
 * A review's verdict, settled where the caller waits for it: what only the wait sees, a review thread that failed before
 * its reviewer settled anything, a review that outlived its limits, and a run stopped while it waited. What the verdict
 * then does to the commit is the commit's run's ({@code CommitRunTest}).
 */
class LocalReviewVerdictTest {
    private static final String COMMIT = "1a2b3c4d";
    private static final Log LOG = new NoopLog();
    private static final String PROVIDER_ERRORS = "the LLM provider answered 429 12 times during the review";
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
    /** a review that ran out of time before its reviewer existed never reached the model, so no commit is excluded for it */
    @Test
    void aReviewThatRanOutOfTimeBeforeReachingTheModelIsCodiqos() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SubmissionContext ctx = context();
        ctx.getArgs().setReviewStartupTimeout(Duration.ofMillis(10));
        ctx.getArgs().setReviewTimeout(Duration.ofMillis(10));
        try (LocalReviewRun run = LocalReviewRun.of(BackgroundReview.start(() -> {
            release.await();
            return null;
        }), List.of(), LOGS)) {
            ReviewVerdict.CodiqoFailure failed = assertInstanceOf(ReviewVerdict.CodiqoFailure.class, run.attachTo(ctx));

            assertEquals("local review did not finish within PT0.04S", failed.getError().getMessage());
            assertEquals(null, failed.getError().getCause(), "a halt's reason is the root cause's, which the timeout would leave blank");
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
                assertInstanceOf(ReviewVerdict.Stopped.class, run.attachTo(context()));
            } finally {
                assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
            }
        } finally {
            release.countDown();
        }
    }
    /** a built commit whose review failed on it is excluded for the review, with the failure's stack as the detail */
    @Test
    void aReviewThatFailedOnABuiltCommitExcludesItForTheReview() {
        ReviewVerdict.CommitFailure failed = new ReviewVerdict.CommitFailure(new IOException("the coordinator started no reviewer"), Optional.of(PROVIDER_ERRORS));

        AnalysisExcludeModel excluded = LocalReviewRun.exclusion(failed, context(), LOG);

        assertEquals(AnalysisExcludeCategory.LOCAL_REVIEW_FAILURE, excluded.getCategory());
        assertEquals("local review failed: the coordinator started no reviewer; " + PROVIDER_ERRORS, excluded.getReason());
        assertTrue(excluded.getDetail().contains("the coordinator started no reviewer"), "the stack trace is the detail");
        assertEquals(List.of(), excluded.getFiles());
    }
    /** an operator's re-inclusion of a build failure survives a re-analysis only under that category */
    @Test
    void aBuildFailedCommitWhoseReviewFailedOnItKeepsItsBuildFailureWithItsFilesAndMetrics() {
        SubmissionContext ctx = context();
        ctx.getSubmissionModel().setBuildFailure(new AnalysisBuildFailureModel().reason("[ERROR] COMPILATION ERROR").category(AnalysisExcludeCategory.BUILD_FAILURE).detail("cannot find symbol"));
        ctx.getSubmissionModel().setFiles(List.of(new FileChangeModel().path("src/A.java")));
        ctx.getSubmissionModel().setProjectMetrics(new ProjectMetricsModel());

        AnalysisExcludeModel excluded = LocalReviewRun.exclusion(new ReviewVerdict.CommitFailure(new IOException("refused")), ctx, LOG);

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, excluded.getCategory());
        assertEquals("[ERROR] COMPILATION ERROR", excluded.getReason());
        assertEquals("cannot find symbol", excluded.getDetail());
        assertEquals(ctx.getSubmissionModel().getFiles(), excluded.getFiles());
        assertEquals(ctx.getSubmissionModel().getProjectMetrics(), excluded.getProjectMetrics(), "the degraded submission's driver-score statistics go with it");
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
