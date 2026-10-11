package io.codiqo.llm.client;

import java.util.List;

import org.apache.commons.collections4.CollectionUtils;

import com.google.common.collect.Lists;

import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.Builder;
import lombok.Value;

public interface ScoringClient extends Scorer<ScoringClient.Params, ScoringClient.ScoringResult>, LlmClient {
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
        int promptLength;
        @Builder.Default
        LlmUsage usage = LlmUsage.NONE;
        @Builder.Default
        List<String> toolCallsMade = Lists.newArrayList();

        /** the score the local assessment gave a commit scored from it alone; null for a commit the prompt scored */
        Double localAssessmentScore;

        public int getPromptTokens() {
            return usage.getPromptTokens();
        }
        public int getCompletionTokens() {
            return usage.getCompletionTokens();
        }
        public int getTotalTokens() {
            return usage.getTotalTokens();
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
