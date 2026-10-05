package io.codiqo.llm;

import java.util.Collections;
import java.util.List;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import lombok.Builder;
import lombok.Value;
import org.apache.commons.lang3.tuple.ImmutablePair;

public interface PromptBuilder {
    String buildSystemPrompt(PromptContext context);
    UserMessageResult buildUserMessageWithScores(LlmScoringRequest request, PromptContext context);
    String buildWebSearchResults(String query, List<WebSearchResultItem> results);
    String buildValidationFeedback(FinalScoreCalculator.ValidationReport report);
    int estimateTokens(String model, String text);

    @Value
    @Builder
    class PromptContext {
        RunArgs args;

        @Builder.Default
        List<String> technicalTags = Collections.emptyList();
        @Builder.Default
        List<String> functionalTags = Collections.emptyList();
        @Builder.Default
        int tagsVocabularyCap = 30;

        /**
         * Assembled by ConventionGuidance outside the prompt builder and passed in as text. Reading it can fail the
         * analysis, and a builder constructor is not a safe place to throw from because it sits inside a
         * try-with-resources head.
         */
        @Builder.Default
        String conventionGuidance = "";

        @Builder.Default
        long projectTotalStatements = 0;
        @Builder.Default
        int projectTotalFiles = 0;
        @Builder.Default
        int projectTotalMethods = 0;
        @Builder.Default
        int codeUnitsAffected = 0;
        @Builder.Default
        int methodCapQuantileProd = 0;
        @Builder.Default
        int methodCapQuantileTest = 0;
        @Builder.Default
        int constructorCapQuantileProd = 0;
        @Builder.Default
        int constructorCapQuantileTest = 0;

        public static PromptContextBuilder withFullContext(RunArgs args) {
            return PromptContext.builder().args(args);
        }
    }

    final class UserMessageResult extends ImmutablePair<String, PreComputedScores> {
        public UserMessageResult(String message, PreComputedScores preComputedScores) {
            super(message, preComputedScores);
        }
        public String getMessage() {
            return getLeft();
        }
        public PreComputedScores getPreComputedScores() {
            return getRight();
        }
    }

    @Value
    @Builder
    class WebSearchResultItem {
        String title;
        String url;
        String content;
    }
}
