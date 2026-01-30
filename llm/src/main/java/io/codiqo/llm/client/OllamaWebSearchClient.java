package io.codiqo.llm.client;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.PromptBuilder;
import io.codiqo.llm.PromptBuilder.WebSearchResultItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OllamaWebSearchClient implements WebSearchClient {
    private static final String WEB_SEARCH_URL = "https://ollama.com/api/web_search";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final PromptBuilder promptBuilder;

    public OllamaWebSearchClient(RunArgs args, PromptBuilder promptBuilder) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder);
        this.apiKey = args.getLlmApiKey();
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(args.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(args.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }
    @Override
    public String searchAsMarkdown(String query, int maxResults) throws IOException {
        List<WebSearchResultItem> results = search(query, maxResults);
        return promptBuilder.buildWebSearchResults(query, results);
    }
    @Override
    public List<WebSearchResultItem> search(String query, int maxResults) throws IOException {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .maxResults(maxResults)
                .build();

        String requestBody = objectMapper.writeValueAsString(searchRequest);

        Request request = new Request.Builder()
                .url(WEB_SEARCH_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                SearchResponse searchResponse = objectMapper.readValue(responseBody, SearchResponse.class);

                List<WebSearchResultItem> items = Lists.newArrayList();
                if (Objects.nonNull(searchResponse.getResults())) {
                    for (ApiSearchResultItem item : searchResponse.getResults()) {
                        items.add(WebSearchResultItem.builder()
                                .title(item.getTitle())
                                .url(item.getUrl())
                                .content(item.getContent())
                                .build());
                    }
                }
                return items;
            }
            String errorBody = Objects.nonNull(response.body()) ? response.body().string() : "No response body";
            throw new IOException("Web search failed: " + response.code() + " - " + errorBody);
        }
    }
    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class SearchRequest {
        private String query;
        private Integer maxResults;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SearchResponse {
        private List<ApiSearchResultItem> results = Lists.newArrayList();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ApiSearchResultItem {
        private String title;
        private String url;
        private String content;
    }
}
