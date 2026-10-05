package io.codiqo.llm.client;

import io.codiqo.llm.client.OpenAIClientWrapper.StreamingResult;
import org.apache.commons.lang3.tuple.ImmutableTriple;

/**
 * Token accounting for one logical LLM operation, however many round trips it took. Every client in this
 * package reports usage as this type so a caller can meter a call without knowing which client made it —
 * codiqo-backend bills every operation into llm_usage_logs, so a call that cannot report usage is a hole
 * in that ledger rather than merely an inconsistency.
 *
 * totalTokens is kept as the provider reported it rather than recomputed: providers do not always return
 * prompt + completion (cached-prompt and reasoning tokens land in the total on some gateways), and the
 * ledger should carry what was actually billed.
 */
public final class LlmUsage extends ImmutableTriple<Integer, Integer, Integer> {
    public static final LlmUsage NONE = new LlmUsage(0, 0, 0);

    public LlmUsage(int promptTokens, int completionTokens, int totalTokens) {
        super(promptTokens, completionTokens, totalTokens);
    }
    public int getPromptTokens() {
        return getLeft();
    }
    public int getCompletionTokens() {
        return getMiddle();
    }
    public int getTotalTokens() {
        return getRight();
    }
    public LlmUsage plus(LlmUsage other) {
        return new LlmUsage(
                getPromptTokens() + other.getPromptTokens(),
                getCompletionTokens() + other.getCompletionTokens(),
                getTotalTokens() + other.getTotalTokens());
    }
    /**
     * The provider's own total wins when it reports one, but a gateway that populates prompt and completion
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
}
