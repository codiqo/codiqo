package io.codiqo.submit.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.AnalysisSubmissionModel;
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
