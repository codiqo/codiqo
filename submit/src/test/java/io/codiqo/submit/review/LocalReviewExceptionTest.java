package io.codiqo.submit.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import io.codiqo.llm.review.ReviewProviderException;

class LocalReviewExceptionTest {
    /** the review thread's failure arrives wrapped, so the provider's failure is found anywhere in the cause chain */
    @Test
    void aReviewTheProviderFailedIsTheProvidersFailure() {
        ReviewProviderException provider = new ReviewProviderException("timeout", "the LLM provider answered 429 466 times during the review", new IOException("timeout"));

        assertTrue(new LocalReviewException("local review failed", new ExecutionException(provider)).isProviderFailure());
    }
    @Test
    void anUnreadableAnswerIsTheReviewsFailure() {
        assertFalse(new LocalReviewException("local review failed", new IOException("Unrecognized token 'Now'")).isProviderFailure());
    }
}
