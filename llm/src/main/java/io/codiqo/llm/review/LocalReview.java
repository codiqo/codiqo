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
     * The pieces of a scoring response the agents judged from the same reading: block categories, summary, tags, task
     * types and complexity, quality dimensions.
     */
    LlmScoringResponse assessment;

    /** reviewer sessions the coordinator started */
    int reviewers;

    /** the reviewers' risk observations, each a short concrete fact; asked for only when the review assesses */
    List<String> observations;

    /** what the reviewers say they could not verify: an external artifact, a test they could not run, a refused command */
    List<String> notChecked;

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
