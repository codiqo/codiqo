package io.codiqo.llm.client;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.llm.GoldenText;

class TagConsolidationClientTest {
    @Test
    void theVocabularyIsListedExactly() throws Exception {
        GoldenText.assertMatches("tag-consolidation-prompt",
                TagConsolidationClient.prompt(Lists.newArrayList("spring-boot", null, "spring-boot-4"), Lists.newArrayList(), 30));
    }
}
