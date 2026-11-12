package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;

class ReviewVerdictTest {
    private static final Optional<String> PROVIDER_ERRORS = Optional.of("the LLM provider answered 429 12 times during the review");

    @Test
    void anAnswerLostWhileTheProviderFailedIsTheProviders() {
        IOException cutOff = new IOException("session ses_1 was cut off before it called codiqo_submit_answer, so it gave no answer");

        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, ReviewVerdict.unanswered(cutOff, true, PROVIDER_ERRORS));

        assertSame(cutOff, failed.getError(), "the summary is kept apart, for the halt's reason");
        assertEquals(PROVIDER_ERRORS.get(), failed.getProviderSummary());
        assertEquals(cutOff.getMessage() + "; " + PROVIDER_ERRORS.get(), failed.getReason(), "a developer reads the provider's answers with it");
    }
    /** a 400 the provider answered is counted and named, but it is no failure of the provider's */
    @Test
    void anAnswerLostWhileTheProviderOnlyRefusedRequestsIsTheCommits() {
        ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class,
                ReviewVerdict.unanswered(new IOException("session ses_1 did not end its turn within PT150M"), false, Optional.of("the LLM provider answered 400 once during the review")));

        assertEquals("session ses_1 did not end its turn within PT150M; the LLM provider answered 400 once during the review", failed.getError().getMessage());
    }
    @Test
    void aFailureNoPartOfTheReviewSettledIsCodiqos() {
        IOException startup = new IOException("opencode serve exited with code 1");

        ReviewVerdict.CodiqoFailure failed = assertInstanceOf(ReviewVerdict.CodiqoFailure.class, ReviewVerdict.unexpected(startup, Optional.empty()));

        assertSame(startup, failed.getError());
    }
    /** a review whose agents were never prompted lost no answer: running out of time is Codiqo's failure */
    @Test
    void aReviewThatTimedOutBeforeItsAgentsWerePromptedIsCodiqos() {
        LocalReviewer reviewer = new LocalReviewer(new RunArgs(), new ReviewEndpoint("http://127.0.0.1:1", null, null), StringUtils.EMPTY, ReviewTags.NONE, List.of(), List.of(),
                new NoopLog());

        assertInstanceOf(ReviewVerdict.CodiqoFailure.class, reviewer.timedOut(new IOException("local review did not finish within PT7H31M")));
    }
    /** an interrupt surfaces as an I/O error whose root cause is no InterruptedException, so the thread's flag decides */
    @Test
    void aReviewInterruptedWhileItRanIsStopped() {
        Thread.currentThread().interrupt();
        try {
            assertInstanceOf(ReviewVerdict.Stopped.class, ReviewVerdict.unexpected(new InterruptedIOException("interrupted while waiting for /api/session"), PROVIDER_ERRORS));
        } finally {
            assertTrue(Thread.interrupted());
        }
        assertInstanceOf(ReviewVerdict.Stopped.class, ReviewVerdict.unexpected(new IOException("cancelled", new InterruptedException()), PROVIDER_ERRORS));
    }
}
