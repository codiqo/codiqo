package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    /** each turn the agents take has its own timeout, so the wait covers every turn the review can take */
    @Test
    void theWaitCoversEveryTurn() {
        RunArgs args = new RunArgs();
        args.setReviewTimeout(Duration.ofMinutes(30));
        args.setReviewStartupTimeout(Duration.ofMinutes(1));

        assertEquals(Duration.ofMinutes(61), LocalReviewer.longestReview(args, false));
        assertEquals(Duration.ofMinutes(121), LocalReviewer.longestReview(args, true));
    }
}
