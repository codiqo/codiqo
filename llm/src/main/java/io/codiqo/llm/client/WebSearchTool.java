package io.codiqo.llm.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Function;

import org.apache.commons.lang3.exception.ExceptionUtils;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonClassDescription("Search the web for information about library dependencies, breaking changes, migration guides, and technical documentation. Use this tool when you need to verify breaking changes, understand migration paths, or find documentation about library upgrades.")
public class WebSearchTool implements Function<OllamaWebSearchClient, WebSearchTool.WebSearchResult> {
    public static final int MAX_RESULTS = 3;
    public static final int DEFAULT_RESULTS = 2;

    @JsonPropertyDescription("The search query. Be specific, e.g. 'Spring Boot 3 to 4 migration breaking changes' or 'Kafka 3.x to 4.x upgrade guide'")
    public String query;

    @JsonPropertyDescription("Maximum number of results to return (1-3, default 2)")
    public Integer maxResults;

    @Override
    public WebSearchResult apply(OllamaWebSearchClient client) {
        for (;;) {
            try {
                int max = Objects.nonNull(maxResults) ? Math.min(Math.max(maxResults, BigDecimal.ONE.intValue()), MAX_RESULTS) : DEFAULT_RESULTS;
                String markdown = client.searchAsMarkdown(query, max);
                return WebSearchResult.builder()
                        .query(query)
                        .content(markdown)
                        .build();
            } catch (IOException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebSearchResult {
        private String query;
        private String content;
    }
}
