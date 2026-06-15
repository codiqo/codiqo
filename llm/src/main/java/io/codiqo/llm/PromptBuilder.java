package io.codiqo.llm;

import java.util.Collections;
import java.util.List;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import lombok.Builder;
import lombok.Value;

public interface PromptBuilder {
    String buildSystemPrompt(PromptContext context);
    UserMessageResult buildUserMessageWithScores(LlmScoringRequest request, PromptContext context);
    String buildWebSearchResults(String query, List<WebSearchResultItem> results);
    String buildValidationFeedback(FinalScoreCalculator.ValidationReport report);

    @Value
    @Builder
    class PromptContext {
        RunArgs args;

        @Builder.Default
        List<String> technicalTags = Collections.emptyList();
        @Builder.Default
        List<String> functionalTags = Collections.emptyList();

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

    @Value
    class UserMessageResult {
        String message;
        PreComputedScores preComputedScores;
    }

    @Value
    @Builder
    class WebSearchResultItem {
        String title;
        String url;
        String content;
    }
}
