package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.schema.LlmScoringResponse;

class LocalReviewerTest {
    /** a coordinator that omits a severity or writes it as null still gives a review whose every severity can be listed */
    @Test
    void aMissingOrNullSeverityReadsAsEmpty() {
        LlmScoringResponse.Bugs bugs = LocalReviewer.readBugs("""
                {"blocking": [{"title": "t", "file": "A.java", "line": 3}], "minor": null}
                """);

        assertEquals(1, bugs.getBlocking().size());
        assertEquals(List.of(), bugs.getMajor());
        assertEquals(List.of(), bugs.getMinor());
    }
    /**
     * each turn the agents take has its own timeout, so the wait covers every turn: the review, the labelling and the
     * triage, each with its repair and resumes, and the corrections of reviewer answers with theirs
     */
    @Test
    void theWaitCoversEveryTurn() {
        RunArgs args = new RunArgs();
        args.setReviewTimeout(Duration.ofMinutes(30));
        args.setReviewStartupTimeout(Duration.ofMinutes(1));

        assertEquals(Duration.ofMinutes(1 + 30 * (6 + LocalReviewer.REVIEWER_REPAIRS) * 3), LocalReviewer.longestReview(args));
    }
    /** the review's progress line counts every severity and adds reasoning to the output tokens, as the usage does */
    @Test
    void theProgressLineSummarisesTheReview() {
        LlmScoringResponse.Bugs bugs = LocalReviewer.readBugs("""
                {"blocking": [{"title": "t", "file": "A.java", "line": 3}], "major": [{"title": "u", "file": "B.java", "line": 5}]}
                """);
        List<SessionUsage> sessions = List.of(
                new SessionUsage("c", "coordinator", "m", 1000, 9000, 200, 50),
                new SessionUsage("r", "reviewer", "m", 500, 4000, 100, 0));
        LocalReview review = new LocalReview("abc", bugs, sessions, Duration.ofMinutes(4), "{}", null, 3, List.of("r2"), List.of());

        assertEquals("2 bugs, 2 sessions, 1 of 3 reviewers unanswered, 1500 input / 350 output tokens", LocalReviewer.progressDetail(review));
    }
    /** an unanswered reviewer whose area an answered retry covered leaves nothing unreviewed; one without a retry fails the review */
    @Test
    void anAreaIsUnreviewedOnlyWhenNoAnsweredRetryCoversIt() {
        assertEquals(0, LocalReviewer.unreviewedAreas(0, 0));
        assertEquals(0, LocalReviewer.unreviewedAreas(2, 2));
        assertEquals(0, LocalReviewer.unreviewedAreas(1, 2), "a retry split in two covers one area");
        assertEquals(1, LocalReviewer.unreviewedAreas(1, 0));
        assertEquals(1, LocalReviewer.unreviewedAreas(3, 2));
    }
    @Test
    void aRetryIsKnownByItsTitle() {
        assertTrue(LocalReviewer.isRetry("Retry: billing DAL and repos"));
        assertTrue(LocalReviewer.isRetry("Retry konquer integration steps"));
        assertTrue(LocalReviewer.isRetry("  retry: providers"));
        assertFalse(LocalReviewer.isRetry("Review konquer b2b models"));
        assertFalse(LocalReviewer.isRetry(""));
        assertFalse(LocalReviewer.isRetry(null));
    }
}
