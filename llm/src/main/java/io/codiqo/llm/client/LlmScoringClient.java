package io.codiqo.llm.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.Lists;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.FinalScoreCalculator;
import io.codiqo.llm.PromptBuilder;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.PromptBuilder.UserMessageResult;
import io.codiqo.llm.ThymeleafPromptBuilder;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.client.OpenAIClientWrapper.AccumulatedToolCall;
import io.codiqo.llm.client.OpenAIClientWrapper.StreamingResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;

public class LlmScoringClient implements ScoringClient {
    private static final int MAX_TOOL_CALLS = Byte.MAX_VALUE;
    private static final String FINISH_REASON_STOP = "stop";
    private static final String FINISH_REASON_LENGTH = "length";

    private final Log log;
    private final OpenAIClient client;
    private final OpenAIClientWrapper wrapper;
    private final PromptBuilder promptBuilder;
    private final FinalScoreCalculator finalScoreCalculator;
    private final ObjectMapper objectMapper;
    private final OllamaWebSearchClient webSearchClient;

    private final String model;
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final int maxRetries;
    private final boolean enableWebSearch;

    public LlmScoringClient(RunArgs args, Log log) {
        this.log = Objects.requireNonNull(log);

        log.info("configuring OpenAI client: timeout=" + args.getReadTimeout());
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder();
        builder.timeout(args.getReadTimeout());

        if (StringUtils.isNotEmpty(args.getLlmApiKey())) {
            builder = builder.apiKey(args.getLlmApiKey());
        }
        if (StringUtils.isNotEmpty(args.getLlmBaseUrl())) {
            builder = builder.baseUrl(args.getLlmBaseUrl());
        }
        this.client = builder.build();
        this.wrapper = new OpenAIClientWrapper(client);
        this.promptBuilder = new ThymeleafPromptBuilder(args, log);
        this.finalScoreCalculator = new FinalScoreCalculator(args);
        this.model = args.getLlmModel();
        this.temperature = args.getLlmTemperature();
        this.topP = args.getLlmTopP();
        this.maxTokens = args.getLlmMaxTokens();
        this.maxRetries = args.getLlmMaxRetries();
        this.enableWebSearch = args.isLlmEnableWebSearchTool();
        this.webSearchClient = enableWebSearch ? new OllamaWebSearchClient(args, promptBuilder) : null;

        this.objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
    }
    @Override
    public ScoringResult score(ScoringClient.Params params) throws Exception {
        LlmScoringRequest request = params.getRequest();
        PromptContext context = params.getContext();
        StreamingHandler handler = params.getHandler();

        String systemPrompt = promptBuilder.buildSystemPrompt(context);
        UserMessageResult userMessageResult = promptBuilder.buildUserMessageWithScores(request, context);
        String userMessage = userMessageResult.getMessage();
        PreComputedScores preComputedScores = userMessageResult.getPreComputedScores();
        int promptLength = systemPrompt.length() + userMessage.length();
        log.info("prompt size: system=" + systemPrompt.length() + " user=" + userMessage.length() + " total=" + promptLength + " chars");

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder();
        paramsBuilder.model(model);
        paramsBuilder.addSystemMessage(systemPrompt);
        paramsBuilder.addUserMessage(userMessage);
        paramsBuilder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        if (Objects.nonNull(temperature)) {
            paramsBuilder.temperature(temperature);
        }
        if (Objects.nonNull(topP)) {
            paramsBuilder.temperature(topP);
        }
        if (Objects.nonNull(maxTokens)) {
            paramsBuilder.maxCompletionTokens(maxTokens.longValue());
        }
        if (enableWebSearch) {
            paramsBuilder.addTool(WebSearchTool.class);
        }

        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        List<String> toolCallsMade = Lists.newArrayList();

        OpenAIClientWrapper.StreamingHandler bridgeHandler = new OpenAIClientWrapper.StreamingHandler() {
            @Override
            public void onContent(String delta) {
                handler.onContent(delta);
            }
        };

        for (int iteration = 0; iteration < MAX_TOOL_CALLS; iteration++) {
            StreamingResult streamResult = streamWithRetry(paramsBuilder.build(), bridgeHandler);

            totalPromptTokens += streamResult.getPromptTokens();
            totalCompletionTokens += streamResult.getCompletionTokens();

            if (CollectionUtils.isNotEmpty(streamResult.getToolCalls())) {
                ChatCompletionAssistantMessageParam.Builder assistantMsg = ChatCompletionAssistantMessageParam.builder();
                String content = streamResult.getContent().toString();
                if (StringUtils.isNotEmpty(content)) {
                    assistantMsg.content(content);
                }
                for (AccumulatedToolCall tc : streamResult.getToolCalls()) {
                    assistantMsg.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                            .id(tc.getId())
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(tc.getName())
                                    .arguments(tc.getArguments().toString())
                                    .build())
                            .build());
                }
                paramsBuilder.addMessage(assistantMsg.build());

                for (AccumulatedToolCall tc : streamResult.getToolCalls()) {
                    String toolName = tc.getName();
                    String arguments = tc.getArguments().toString();
                    toolCallsMade.add(toolName + ": " + arguments);
                    handler.onToolCall(toolName);

                    Object result = executeTool(toolName, arguments);
                    paramsBuilder.addMessage(ChatCompletionToolMessageParam.builder()
                            .toolCallId(tc.getId())
                            .contentAsJson(result)
                            .build());
                }
                continue;
            }

            String rawContent = null;
            LlmScoringResponse scoringResponse = null;
            Exception lastError = null;

            for (int responseAttempt = 1; responseAttempt <= maxRetries; responseAttempt++) {
                rawContent = streamResult.getContent().toString();
                log.info("raw response length: " + rawContent.length() + " chars, finishReason: " + streamResult.getFinishReason());

                if (FINISH_REASON_STOP.equals(streamResult.getFinishReason())) {
                    try {
                        scoringResponse = deserializeResponse(rawContent);
                        break;
                    } catch (Exception err) {
                        lastError = err;
                    }
                } else if (FINISH_REASON_LENGTH.equals(streamResult.getFinishReason())) {
                    lastError = new IOException("response truncated (finishReason=length)");
                } else {
                    lastError = new IOException("unexpected finishReason: " + streamResult.getFinishReason());
                }

                if (responseAttempt < maxRetries) {
                    log.warn("retrying due to malformed LLM response (" + responseAttempt + "/" + maxRetries + ")");
                    streamResult = streamWithRetry(paramsBuilder.build(), bridgeHandler);
                    totalPromptTokens += streamResult.getPromptTokens();
                    totalCompletionTokens += streamResult.getCompletionTokens();
                }
            }

            if (Objects.isNull(scoringResponse)) {
                throw lastError;
            }

            finalScoreCalculator.apply(scoringResponse, preComputedScores);

            ScoringResult result = ScoringResult.builder()
                    .response(scoringResponse)
                    .preComputedScores(preComputedScores)
                    .rawJson(rawContent)
                    .thinking(scoringResponse.getThinking())
                    .promptTokens(totalPromptTokens)
                    .completionTokens(totalCompletionTokens)
                    .promptLength(promptLength)
                    .toolCallsMade(toolCallsMade)
                    .build();

            handler.onComplete(result);
            return result;
        }

        throw new IllegalStateException("exceeded maximum tool call iterations (" + MAX_TOOL_CALLS + ")");
    }
    @Override
    public void close() {
        if (Objects.nonNull(client)) {
            client.close();
        }
        if (Objects.nonNull(webSearchClient)) {
            webSearchClient.close();
        }
    }
    private LlmScoringResponse deserializeResponse(String rawContent) throws Exception {
        try {
            return objectMapper.readValue(rawContent, LlmScoringResponse.class);
        } catch (IOException err) {
            File dumpFile = File.createTempFile("codiqo-llm-response-", ".json");
            FileUtils.write(dumpFile, rawContent, StandardCharsets.UTF_8);
            log.error("failed to parse LLM response: " + err.getMessage());
            log.error("raw LLM response dumped to: " + dumpFile.getAbsolutePath());
            throw err;
        }
    }
    private Object executeTool(String name, String arguments) throws Exception {
        if (WebSearchTool.class.getSimpleName().equals(name)) {
            WebSearchTool searchTool = objectMapper.readValue(arguments, WebSearchTool.class);
            return searchTool.apply(webSearchClient);
        }
        throw new IllegalArgumentException("unknown tool: " + name);
    }
    private StreamingResult streamWithRetry(ChatCompletionCreateParams params, OpenAIClientWrapper.StreamingHandler handler) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return wrapper.stream(params, handler);
            } catch (Exception err) {
                lastException = err;
                if (attempt < maxRetries) {
                    log.warn(String.format("streaming attempt %d/%d failed: %s, retrying", attempt, maxRetries, err.getMessage()));
                }
            }
        }
        throw lastException;
    }
}
