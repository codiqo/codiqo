package io.codiqo.llm.client;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.commons.lang3.StringUtils;

import com.google.common.util.concurrent.ForwardingExecutorService;
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
        /**
         * The read timeout is applied as a per-chunk idle guard (connect/read/write); the total-call
         * timeout is disabled (request = ZERO). OkHttp's callTimeout budgets the ENTIRE call — including
         * reading the full stream — so a long but steadily-streaming completion would otherwise be
         * aborted mid-generation with "Stream failed" the moment total elapsed time exceeds the budget.
         */
        Duration readTimeout = args.getLlmReadTimeout();

        /**
         * OpenAIClient.close() shuts down whatever executor it is handed (both the OkHttp dispatcher
         * pool and the stream-handler executor). These clients are closed per call while the executor
         * is a shared, bean-managed platform pool, so guard it against shutdown — the owning bean
         * disposes the real pool in its own lifecycle.
         */
        ExecutorService guardedExecutor = new NonShutdownExecutorService(executor);

        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .timeout(Timeout.builder()
                        .connect(readTimeout)
                        .read(readTimeout)
                        .write(readTimeout)
                        .request(Duration.ZERO)
                        .build())
                .dispatcherExecutorService(guardedExecutor)
                .streamHandlerExecutor(guardedExecutor);
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

    private static final class NonShutdownExecutorService extends ForwardingExecutorService {
        private final ExecutorService delegate;

        private NonShutdownExecutorService(ExecutorService delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }
        @Override
        protected ExecutorService delegate() {
            return delegate;
        }
        @Override
        public void shutdown() {}
        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }
        /**
         * no @Override: ExecutorService.close() is a JDK19+ AutoCloseable default, absent under the JDK17 compile
         * target; the method still overrides it at runtime on JDK19+, so the no-op shutdown guard holds either way
         */
        public void close() {}
    }
}
