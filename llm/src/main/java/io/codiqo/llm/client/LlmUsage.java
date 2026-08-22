package io.codiqo.llm.client;

import io.codiqo.llm.client.OpenAIClientWrapper.StreamingResult;
import lombok.Value;

/**
 * token accounting for one logical LLM operation, however many round trips it took. every client in this
 * package reports usage as this type so a caller can meter a call without knowing which client made it —
 * codiqo-backend bills every operation into llm_usage_logs, so a call that cannot report usage is a hole
 * in that ledger rather than merely an inconsistency.
 *
 * totalTokens is kept as the provider reported it rather than recomputed: providers do not always return
 * prompt + completion (cached-prompt and reasoning tokens land in the total on some gateways), and the
 * ledger should carry what was actually billed.
 */
@Value
public class LlmUsage {
    public static final LlmUsage NONE = new LlmUsage(0, 0, 0);

    int promptTokens;
    int completionTokens;
    int totalTokens;

    /**
     * the provider's own total wins when it reports one, but a gateway that populates prompt and completion
     * while leaving total at zero would otherwise write a zero into the ledger beside two non-zero counts —
     * so an absent total falls back to the sum rather than under-reporting the call as free
     */
    public static LlmUsage of(StreamingResult result) {
        int reportedTotal = result.getTotalTokens();
        return new LlmUsage(result.getPromptTokens(), result.getCompletionTokens(),
                reportedTotal > 0 ? reportedTotal : result.getPromptTokens() + result.getCompletionTokens());
    }
    public static LlmUsage of(int promptTokens, int completionTokens) {
        return new LlmUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }
    public LlmUsage plus(LlmUsage other) {
        return new LlmUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
    }
}
