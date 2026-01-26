package io.codiqo.llm;

import org.junit.jupiter.api.Test;

import io.codiqo.llm.ThymeleafPromptBuilder.PromptContext;

class ThymeleafPromptBuilderTest {
    @Test
    void works() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder();
        System.out.println(builder.buildSystemPrompt(PromptContext.builder().config(ScoringConfig.defaults()).build()));
    }
}
