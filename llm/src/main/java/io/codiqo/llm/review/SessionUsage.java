package io.codiqo.llm.review;

import lombok.Value;
import lombok.With;

/**
 * Tokens one OpenCode session used, as the server reports them. Cached input is kept apart from new input because it is
 * priced far lower (often 1/20 to 1/50 of the input rate) and is the bulk of an agent review: every tool step resends
 * the conversation, so measured reviews were 85-95% cache hits.
 */
@Value
public class SessionUsage {
    String sessionId;
    String agent;

    /**
     * OpenCode records a model only for sessions started with an explicit one; the coordinator session takes its model
     * from the agent configuration and reports none, so {@link LocalReviewer} fills it in from the options.
     */
    @With
    String model;

    long inputTokens;
    long cachedInputTokens;
    long outputTokens;
    long reasoningTokens;
}
