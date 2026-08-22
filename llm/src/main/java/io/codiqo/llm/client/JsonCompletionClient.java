package io.codiqo.llm.client;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.commons.collections4.MapUtils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.client.OpenAIClientWrapper.StreamingResult;
import lombok.Value;

/**
 * one prompt in, one JSON object out, with the sampling knobs and retry policy the module has settled on.
 *
 * this is the shape every secondary LLM call in codiqo takes — no tools, no multi-turn, no validation loop —
 * and it exists so those calls do not each re-derive the Ollama `options` pass through, the finishReason check
 * and the fence-stripping parse. {@link LlmScoringClient} deliberately does NOT sit on top of it: scoring runs
 * a tool-call loop, accumulates assistant/tool messages across turns and re-prompts on validation failure, so
 * folding it in here would mean an abstraction with a special case per caller
 */
public class JsonCompletionClient implements LlmClient {
    private static final String FINISH_REASON_STOP = "stop";

    private final Log log;
    private final OpenAIClient client;
    private final OpenAIClientWrapper wrapper;
    private final ObjectMapper objectMapper;
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final Integer seed;
    private final Integer maxTokens;
    private final Integer numCtx;
    private final int maxRetries;

    public JsonCompletionClient(RunArgs args, ExecutorService executor, Log log, Map<String, String> additionalHeaders) {
        this(args, executor, log, additionalHeaders, args.getLlmMaxTokens());
    }
    /**
     * {@code maxCompletionTokens} is separate from the scoring allowance because these calls answer with a
     * two-field object, and the scoring value is sized for a full assessment: sending it verbatim reserves
     * tens of thousands of output tokens inside the same window as the prompt, which a narrow-window provider
     * rejects outright — and for the skip classifier that rejection is swallowed and the commit is scored
     * against the author's explicit request. Leave room for reasoning models, which spend real tokens before
     * the answer.
     */
    public JsonCompletionClient(RunArgs args, ExecutorService executor, Log log, Map<String, String> additionalHeaders, Integer maxCompletionTokens) {
        this.log = Objects.requireNonNull(log);

        this.client = OpenAIClientFactory.buildStreamingClient(args, executor, additionalHeaders);
        this.wrapper = new OpenAIClientWrapper(client);

        this.model = args.getLlmModel();
        this.temperature = args.getLlmTemperature();
        this.topP = args.getLlmTopP();
        this.seed = args.getLlmSeed();
        this.maxTokens = maxCompletionTokens;
        this.numCtx = args.getLlmNumCtx();
        this.maxRetries = args.getLlmMaxRetries();

        this.objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
    }
    public <T> Result<T> complete(String label, String prompt, Class<T> responseType) throws Exception {
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder();
        paramsBuilder.model(model);
        paramsBuilder.addUserMessage(prompt);
        paramsBuilder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        paramsBuilder.responseFormat(ResponseFormatJsonObject.builder().build());
        if (Objects.nonNull(temperature)) {
            paramsBuilder.temperature(temperature);
        }
        if (Objects.nonNull(topP)) {
            paramsBuilder.topP(topP);
        }
        if (Objects.nonNull(maxTokens)) {
            paramsBuilder.maxCompletionTokens(maxTokens.longValue());
        }

        /**
         * Ollama honours sampling through its native `options` object only — num_ctx has no effect as a
         * top-level field — so the knobs travel there as well, mirroring {@link LlmScoringClient}. seed
         * belongs here for the same reason it does there: a caller that configured one expects the same
         * answer for the same input, and these calls decide whether a commit is scored at all
         */
        Map<String, Object> options = new LinkedHashMap<>();
        if (Objects.nonNull(numCtx)) {
            options.put("num_ctx", numCtx);
        }
        if (Objects.nonNull(seed)) {
            options.put("seed", seed);
        }
        if (Objects.nonNull(temperature)) {
            options.put("temperature", temperature);
        }
        if (Objects.nonNull(topP)) {
            options.put("top_p", topP);
        }
        if (MapUtils.isNotEmpty(options)) {
            paramsBuilder.putAdditionalBodyProperty("options", JsonValue.from(options));
        }

        /**
         * a failed attempt was still billed for its prompt, so its tokens accumulate into the usage the
         * caller meters — LlmUsage is "one logical operation, however many round trips it took", and
         * reporting only the winning attempt under-reports real spend by the provider's retry rate
         */
        LlmUsage usage = LlmUsage.NONE;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                StreamingResult result = wrapper.stream(paramsBuilder.build(), new OpenAIClientWrapper.StreamingHandler() {});
                usage = usage.plus(LlmUsage.of(result));
                if (FINISH_REASON_STOP.equals(result.getFinishReason())) {
                    T response = objectMapper.readValue(LlmScoringClient.stripMarkdownFences(result.getContent().toString()), responseType);
                    return new Result<>(response, usage);
                }
                lastError = new IOException("unexpected finishReason: " + result.getFinishReason());
            } catch (Exception err) {
                lastError = err;
            }
            if (attempt < maxRetries) {
                log.warn(String.format("%s attempt %d/%d failed: %s, retrying", label, attempt, maxRetries, lastError.getMessage()));
            }
        }
        throw lastError;
    }
    @Override
    public void close() {
        if (Objects.nonNull(client)) {
            client.close();
        }
    }

    @Value
    public static class Result<T> {
        T response;
        LlmUsage usage;
    }
}
