package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.schema.LlmScoringResponse;

class BackgroundReviewTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void aFinishedReviewIsReturned() throws Exception {
        LocalReview review = new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), "{}", null, 0, List.of(), List.of());
        try (BackgroundReview<LocalReview> background = BackgroundReview.start(() -> review)) {
            assertEquals(review, background.await(WAIT));
        }
    }
    @Test
    void aFailedReviewFailsTheBuild() throws Exception {
        try (BackgroundReview<LocalReview> background = BackgroundReview.start(() -> {
            throw new IOException("opencode serve exited with code 1");
        })) {
            MojoExecutionException err = assertThrows(MojoExecutionException.class, () -> background.await(WAIT));
            assertTrue(err.getMessage().contains("opencode serve exited"), err.getMessage());
        }
    }
    @Test
    void aReviewThatRunsOutOfTimeFailsTheBuild() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundReview<LocalReview> background = BackgroundReview.start(() -> {
            release.await();
            return null;
        })) {
            assertThrows(MojoExecutionException.class, () -> background.await(Duration.ofMillis(50)));
        }
    }
    @Test
    void closingInterruptsAReviewStillRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        BackgroundReview<LocalReview> background = BackgroundReview.start(() -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException err) {
                interrupted.countDown();
                throw err;
            }
            throw new IllegalStateException("the review should have been interrupted");
        });

        assertTrue(started.await(WAIT.toSeconds(), TimeUnit.SECONDS));
        background.close();
        assertTrue(interrupted.await(WAIT.toSeconds(), TimeUnit.SECONDS), "close must interrupt the review so it stops its OpenCode server");
    }
}
