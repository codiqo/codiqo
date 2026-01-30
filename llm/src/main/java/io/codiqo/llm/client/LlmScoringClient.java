package io.codiqo.llm.client;

import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
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
    private final boolean enableWebSearch;

    public LlmScoringClient(RunArgs args) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder();
        builder.timeout(args.getReadTimeout());

        if (StringUtils.isNotEmpty(args.getLlmApiKey())) {
            builder = builder.apiKey(args.getLlmApiKey());
        }
        if (StringUtils.isNotEmpty(args.getLlmBaseUrl())) {
            builder = builder.baseUrl(args.getLlmBaseUrl());
        }
        if (args.getLlmMaxRetries() > 0) {
            builder = builder.maxRetries(args.getLlmMaxRetries());
        }

        this.client = builder.build();
        this.wrapper = new OpenAIClientWrapper(client);
        this.promptBuilder = new ThymeleafPromptBuilder(args);
        this.finalScoreCalculator = new FinalScoreCalculator(args);
        this.model = args.getLlmModel();
        this.temperature = args.getLlmTemperature();
        this.topP = args.getLlmTopP();
        this.maxTokens = args.getLlmMaxTokens();
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
    public ScoringResult score(LlmScoringRequest request, PromptContext context, StreamingHandler handler) throws Exception {
        String systemPrompt = promptBuilder.buildSystemPrompt(context);
        UserMessageResult userMessageResult = promptBuilder.buildUserMessageWithScores(request, context);
        String userMessage = userMessageResult.getMessage();
        PreComputedScores preComputedScores = userMessageResult.getPreComputedScores();
        int promptLength = systemPrompt.length() + userMessage.length();

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
            StreamingResult streamResult = wrapper.stream(paramsBuilder.build(), bridgeHandler);

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

            String rawContent = streamResult.getContent().toString();
            LlmScoringResponse scoringResponse = objectMapper.readValue(rawContent, LlmScoringResponse.class);

            finalScoreCalculator.apply(scoringResponse, preComputedScores);

            ScoringResult result = ScoringResult.builder()
                    .response(scoringResponse)
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
    private Object executeTool(String name, String arguments) throws Exception {
        if (WebSearchTool.class.getSimpleName().equals(name)) {
            WebSearchTool searchTool = objectMapper.readValue(arguments, WebSearchTool.class);
            return searchTool.apply(webSearchClient);
        }
        throw new IllegalArgumentException("unknown tool: " + name);
    }
}
