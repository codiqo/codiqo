package io.codiqo.llm.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.apache.commons.lang3.StringUtils;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.Timeout;

import io.codiqo.api.RunArgs;

/**
 * Shared construction of the OpenAI-compatible HTTP client used by the streaming LLM clients
 * ({@link LlmScoringClient}, {@link TagConsolidationClient}). Centralizes the timeout policy and
 * executor wiring so every streaming caller behaves identically.
 */
public final class OpenAIClientFactory {
    private OpenAIClientFactory() {}

    public static OpenAIClient buildStreamingClient(RunArgs args, ExecutorService executor, Map<String, String> additionalHeaders) {
        // The read timeout is applied as a per-chunk idle guard (connect/read/write); the total-call
        // timeout is disabled (request = ZERO). OkHttp's callTimeout budgets the ENTIRE call — including
        // reading the full stream — so a long but steadily-streaming completion would otherwise be
        // aborted mid-generation with "Stream failed" the moment total elapsed time exceeds the budget.
        Duration readTimeout = args.getLlmReadTimeout();
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .timeout(Timeout.builder()
                        .connect(readTimeout)
                        .read(readTimeout)
                        .write(readTimeout)
                        .request(Duration.ZERO)
                        .build())
                .dispatcherExecutorService(executor)
                .streamHandlerExecutor(executor);
        if (StringUtils.isNotEmpty(args.getLlmApiKey())) {
            builder.apiKey(args.getLlmApiKey());
        }
        if (StringUtils.isNotEmpty(args.getLlmBaseUrl())) {
            builder.baseUrl(args.getLlmBaseUrl());
        }
        for (Map.Entry<String, String> header : additionalHeaders.entrySet()) {
            builder.putHeader(header.getKey(), header.getValue());
        }
        return builder.build();
    }
}
