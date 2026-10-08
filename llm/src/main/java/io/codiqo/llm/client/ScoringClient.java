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

        /** the local review's assessment of the commit, when the submission carried one */
        LlmScoringResponse localAssessment;

        /**
         * Score from the local assessment rather than the prompt's judgment. Without it a local assessment is only
         * compared: the score it would give is reported next to the prompt's, which stays the one recorded.
         */
        boolean applyLocalAssessment;
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

        /** the score the local assessment gives, whether or not it was applied; null without one */
        Double localAssessmentScore;

        /** the score the prompt's own judgment gives; differs from the response's when the local one was applied */
        Double promptScore;

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
