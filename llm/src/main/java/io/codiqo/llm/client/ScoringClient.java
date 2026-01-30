package io.codiqo.llm.client;

import java.io.Closeable;
import java.util.List;

import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.Builder;
import lombok.Value;

public interface ScoringClient extends Closeable {
    ScoringResult score(LlmScoringRequest request, PromptContext context, StreamingHandler handler) throws Exception;

    @Value
    @Builder
    class ScoringResult {
        LlmScoringResponse response;
        String rawJson;
        String thinking;
        int promptTokens;
        int completionTokens;
        int promptLength;
        List<String> toolCallsMade;

        public int getTotalTokens() {
            return promptTokens + completionTokens;
        }
        public boolean usedTools() {
            return toolCallsMade != null && !toolCallsMade.isEmpty();
        }
    }

    interface StreamingHandler {
        default void onContent(String delta) {}
        default void onThinking(String delta) {}
        default void onComplete(ScoringResult result) {}
        default void onError(String error) {}
    }
}
