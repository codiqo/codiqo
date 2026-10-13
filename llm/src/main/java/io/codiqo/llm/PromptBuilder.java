package io.codiqo.llm;

import java.util.Collections;
import java.util.List;

import io.codiqo.api.RunArgs;
import lombok.Builder;
import lombok.Value;

/** what scoring knows about the commit's project beside the commit itself; the rules read the project's totals from it */
public interface PromptBuilder {
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

}
