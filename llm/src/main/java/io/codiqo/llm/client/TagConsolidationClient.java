package io.codiqo.llm.client;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.commons.collections4.MapUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.client.OpenAIClientWrapper.StreamingResult;
import io.codiqo.llm.schema.TagConsolidationResponse;

public class TagConsolidationClient implements Closeable {
    private static final String TEMPLATE_TAG_CONSOLIDATION = "tag-consolidation-prompt";
    private static final String FINISH_REASON_STOP = "stop";
    private static final TemplateEngine TEMPLATE_ENGINE;
    static {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);

        TEMPLATE_ENGINE = new TemplateEngine();
        TEMPLATE_ENGINE.setTemplateResolver(resolver);
    }

    private final Log log;
    private final OpenAIClient client;
    private final OpenAIClientWrapper wrapper;
    private final ObjectMapper objectMapper;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final Integer numCtx;
    private final int maxRetries;

    public TagConsolidationClient(RunArgs args, ExecutorService executor, Log log, Map<String, String> additionalHeaders) {
        this.log = Objects.requireNonNull(log);

        this.client = OpenAIClientFactory.buildStreamingClient(args, executor, additionalHeaders);
        this.wrapper = new OpenAIClientWrapper(client);

        this.model = args.getLlmModel();
        this.temperature = args.getLlmTemperature();
        this.maxTokens = args.getLlmMaxTokens();
        this.numCtx = args.getLlmNumCtx();
        this.maxRetries = args.getLlmMaxRetries();

        this.objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
    }
    public TagConsolidationResponse consolidate(List<String> technicalTags, List<String> functionalTags, int vocabularyCap) throws Exception {
        Context ctx = new Context();
        ctx.setVariable("technical_tags", String.join(", ", technicalTags));
        ctx.setVariable("functional_tags", String.join(", ", functionalTags));
        ctx.setVariable("technical_count", technicalTags.size());
        ctx.setVariable("functional_count", functionalTags.size());
        ctx.setVariable("vocabulary_cap", vocabularyCap);

        String prompt = TEMPLATE_ENGINE.process(TEMPLATE_TAG_CONSOLIDATION, ctx);
        log.info(String.format("tag consolidation prompt: %d chars (%d technical, %d functional, cap %d)",
                prompt.length(), technicalTags.size(), functionalTags.size(), vocabularyCap));

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder();
        paramsBuilder.model(model);
        paramsBuilder.addUserMessage(prompt);
        if (Objects.nonNull(temperature)) {
            paramsBuilder.temperature(temperature);
        }
        if (Objects.nonNull(maxTokens)) {
            paramsBuilder.maxCompletionTokens(maxTokens.longValue());
        }
        paramsBuilder.responseFormat(ResponseFormatJsonObject.builder().build());

        Map<String, Object> options = new LinkedHashMap<>();
        if (Objects.nonNull(numCtx)) {
            options.put("num_ctx", numCtx);
        }
        if (Objects.nonNull(temperature)) {
            options.put("temperature", temperature);
        }
        if (MapUtils.isNotEmpty(options)) {
            paramsBuilder.putAdditionalBodyProperty("options", JsonValue.from(options));
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                StreamingResult result = wrapper.stream(paramsBuilder.build(), new OpenAIClientWrapper.StreamingHandler() {});
                if (FINISH_REASON_STOP.equals(result.getFinishReason())) {
                    return objectMapper.readValue(LlmScoringClient.stripMarkdownFences(result.getContent().toString()), TagConsolidationResponse.class);
                }
                lastError = new IOException("unexpected finishReason: " + result.getFinishReason());
            } catch (Exception err) {
                lastError = err;
            }
            if (attempt < maxRetries) {
                log.warn(String.format("tag consolidation attempt %d/%d failed: %s, retrying", attempt, maxRetries, lastError.getMessage()));
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
}
