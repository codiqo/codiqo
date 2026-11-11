package io.codiqo.llm;

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
        long projectTotalStatements = 0;
        @Builder.Default
        int projectTotalMethods = 0;
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
