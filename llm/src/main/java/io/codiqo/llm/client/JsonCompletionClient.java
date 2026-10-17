package io.codiqo.llm.client;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.Maps;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.client.OpenAIClientWrapper.StreamingResult;

/**
 * one prompt in, one JSON object out, with the sampling knobs the module has settled on: the shape every secondary
 * LLM call in codiqo takes — no tools, no multi-turn, no validation loop, and one attempt.
 * the old scoring client deliberately does NOT sit on top of it, since scoring runs a tool-call loop and
 * re-prompts on validation failure.
 */
public class JsonCompletionClient implements LlmClient {
    private static final String FINISH_REASON_STOP = "stop";

    private final OpenAIClient client;
    private final OpenAIClientWrapper wrapper;
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final Integer seed;
    private final Integer maxTokens;
    private final Integer numCtx;

    public JsonCompletionClient(RunArgs args, ExecutorService executor, Map<String, String> additionalHeaders) {
        this(args, executor, additionalHeaders, args.getLlmMaxTokens());
    }
    /**
     * {@code maxCompletionTokens} is separate from the scoring allowance because these calls answer with a
     * two-field object, and the scoring value is sized for a full assessment: sending it verbatim reserves
     * tens of thousands of output tokens inside the same window as the prompt, which a narrow-window provider
     * rejects outright — and for the skip classifier that rejection is swallowed and the commit is scored
     * against the author's explicit request. Leave room for reasoning models, which spend real tokens before
     * the answer.
     */
    public JsonCompletionClient(RunArgs args, ExecutorService executor, Map<String, String> additionalHeaders, Integer maxCompletionTokens) {

        this.client = OpenAIClientFactory.buildStreamingClient(args, executor, additionalHeaders);
        this.wrapper = new OpenAIClientWrapper(client);

        this.model = args.getLlmModel();
        this.temperature = args.getLlmTemperature();
        this.topP = args.getLlmTopP();
        this.seed = args.getLlmSeed();
        this.maxTokens = maxCompletionTokens;
        this.numCtx = args.getLlmNumCtx();

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
         * top-level field — so the knobs travel there as well, mirroring the old scoring client. seed
         * belongs here for the same reason it does there: a caller that configured one expects the same
         * answer for the same input, and these calls decide whether a commit is scored at all
         */
        Map<String, Object> options = Maps.newLinkedHashMap();
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
         * One call: the OpenAI client already retries a connection failure, a 429 and a 5xx itself, and the
         * response format holds the answer to JSON, so an answer that is cut off or cannot be read is a defect
         * to see, not one to retry until it passes.
         */
        StreamingResult result = wrapper.stream(paramsBuilder.build(), new OpenAIClientWrapper.StreamingHandler() {});
        if (FINISH_REASON_STOP.equals(result.getFinishReason())) {
            return new Result<>(LlmJson.readAnswer(result.getContent().toString(), responseType), LlmUsage.of(result));
        }
        throw new IOException(label + ": unexpected finishReason: " + result.getFinishReason());
    }
    @Override
    public void close() {
        if (Objects.nonNull(client)) {
            client.close();
        }
    }

    public static final class Result<T> extends ImmutablePair<T, LlmUsage> {
        public Result(T response, LlmUsage usage) {
            super(response, usage);
        }
        public T getResponse() {
            return getLeft();
        }
        public LlmUsage getUsage() {
            return getRight();
        }
    }
}
