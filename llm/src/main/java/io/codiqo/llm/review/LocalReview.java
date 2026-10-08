package io.codiqo.llm.review;

import java.time.Duration;
import java.util.List;

import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.Value;

/** The outcome of one local review: the merged findings and what every session involved cost. */
@Value
public class LocalReview {
    String commit;
    LlmScoringResponse.Bugs bugs;
    List<SessionUsage> sessions;
    Duration took;

    /** The coordinator's final answer as the model wrote it, kept so a parse surprise can be inspected afterwards. */
    String answer;

    /**
     * The pieces of a scoring response the agents judged from the same reading, when the review was asked to assess
     * ({@link io.codiqo.api.RunArgs#isReviewAssess()}): block categories, summary, tags, task types and complexity, quality
     * dimensions. Null otherwise.
     */
    LlmScoringResponse assessment;

    /** reviewer sessions the coordinator started, a retried area counting twice */
    int reviewers;

    /**
     * Reviewer sessions that ended without a readable JSON answer, usually at their step limit. Their findings never
     * reached the coordinator unless it retried the area, so a high count means part of the commit went unreviewed.
     */
    List<String> unansweredReviewers;

    /** the reviewers' risk observations, each a short concrete fact; asked for only when the review assesses */
    List<String> observations;

    public long totalInputTokens() {
        return getSessions().stream().mapToLong(SessionUsage::getInputTokens).sum();
    }
    public long totalCachedInputTokens() {
        return getSessions().stream().mapToLong(SessionUsage::getCachedInputTokens).sum();
    }
    public long totalOutputTokens() {
        return getSessions().stream().mapToLong(session -> session.getOutputTokens() + session.getReasoningTokens()).sum();
    }
}
