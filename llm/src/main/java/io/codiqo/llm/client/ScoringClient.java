package io.codiqo.llm.client;

import java.io.Closeable;
import java.util.List;
import java.util.ArrayList;

import org.apache.commons.collections4.CollectionUtils;


import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.Builder;
import lombok.Value;

public interface ScoringClient extends Scorer<ScoringClient.Params, ScoringClient.ScoringResult>, Closeable {
    @Value
    @Builder
    class Params {
        LlmScoringRequest request;
        PromptContext context;
        StreamingHandler handler;
    }

    @Override
    ScoringResult score(Params params) throws Exception;

    @Value
    @Builder
    class ScoringResult {
        LlmScoringResponse response;
        PreComputedScores preComputedScores;
        String rawJson;
        String thinking;
        int promptTokens;
        int completionTokens;
        int promptLength;
        @Builder.Default
        List<String> toolCallsMade = new ArrayList<>();

        public int getTotalTokens() {
            return promptTokens + completionTokens;
        }
        public boolean usedTools() {
            return CollectionUtils.isNotEmpty(toolCallsMade);
        }
    }

    interface StreamingHandler {
        default void onContent(String delta) {}
        default void onToolCall(String toolName) {}
        default void onComplete(ScoringResult result) {}
        default void onError(String error) {}
    }
}
