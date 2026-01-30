package io.codiqo.llm.client;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletion.Choice;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall.Function;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.FinalScoreCalculator;
import io.codiqo.llm.PromptBuilder;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.PromptBuilder.UserMessageResult;
import io.codiqo.llm.ThymeleafPromptBuilder;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
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

        for (int iteration = 0; iteration < MAX_TOOL_CALLS; iteration++) {
            ChatCompletion completion = wrapper.chat(paramsBuilder.build());
            Choice choice = Iterables.getOnlyElement(completion.choices());

            if (completion.usage().isPresent()) {
                var usage = completion.usage().get();
                totalPromptTokens += (int) usage.promptTokens();
                totalCompletionTokens += (int) usage.completionTokens();
            }

            List<ChatCompletionMessageToolCall> toolCalls = choice.message().toolCalls().orElse(Collections.emptyList());
            if (CollectionUtils.isEmpty(toolCalls)) {
                String rawContent = choice.message().content().get();

                String thinking = null;
                String jsonContent = rawContent;
                ThinkingExtractor extractor = new ThinkingExtractor(rawContent);
                if (extractor.hasThinking()) {
                    thinking = extractor.getThinking();
                    jsonContent = extractor.getContent();
                }

                handler.onContent(jsonContent);

                LlmScoringResponse scoringResponse = objectMapper.readValue(jsonContent, LlmScoringResponse.class);

                if (thinking != null) {
                    handler.onThinking(thinking);
                    if (StringUtils.isBlank(scoringResponse.getThinking())) {
                        scoringResponse.setThinking(thinking);
                    }
                }

                finalScoreCalculator.apply(scoringResponse, preComputedScores);

                ScoringResult result = ScoringResult.builder()
                        .response(scoringResponse)
                        .rawJson(jsonContent)
                        .thinking(thinking)
                        .promptTokens(totalPromptTokens)
                        .completionTokens(totalCompletionTokens)
                        .promptLength(promptLength)
                        .toolCallsMade(toolCallsMade)
                        .build();

                handler.onComplete(result);
                return result;
            }

            paramsBuilder.addMessage(choice.message());

            for (ChatCompletionMessageToolCall call : toolCalls) {
                Function function = call.asFunction().function();
                String toolName = function.name();
                toolCallsMade.add(toolName + ": " + function.arguments());

                Object result = executeTool(function);
                paramsBuilder.addMessage(ChatCompletionToolMessageParam.builder()
                        .toolCallId(call.asFunction().id())
                        .contentAsJson(result)
                        .build());
            }
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
    private Object executeTool(ChatCompletionMessageFunctionToolCall.Function function) throws Exception {
        if (WebSearchTool.class.getSimpleName().equals(function.name())) {
            WebSearchTool searchTool = function.arguments(WebSearchTool.class);
            return searchTool.apply(webSearchClient);
        }
        throw new IllegalArgumentException("unknown tool: " + function.name());
    }

    /**
     * Extracts thinking content from tags like &lt;think&gt;, &lt;thinking&gt;, &lt;reasoning&gt;.
     * Used for models that output their reasoning wrapped in XML-style tags.
     */
    private static class ThinkingExtractor {
        private static final String[][] TAG_PAIRS = {
                { "<think>", "</think>" },
                { "<thinking>", "</thinking>" },
                { "<reasoning>", "</reasoning>" }
        };

        private final String thinking;
        private final String content;

        private ThinkingExtractor(String rawContent) {
            StringBuilder thinkingBuilder = new StringBuilder();
            String remaining = rawContent;

            for (String[] tagPair : TAG_PAIRS) {
                String openTag = tagPair[0];
                String closeTag = tagPair[1];

                int openIdx;
                while ((openIdx = remaining.indexOf(openTag)) >= 0) {
                    int closeIdx = remaining.indexOf(closeTag, openIdx + openTag.length());
                    if (closeIdx >= 0) {
                        String thinkContent = remaining.substring(openIdx + openTag.length(), closeIdx);
                        if (thinkingBuilder.length() > 0) {
                            thinkingBuilder.append("\n\n");
                        }
                        thinkingBuilder.append(thinkContent.trim());
                        remaining = remaining.substring(0, openIdx) + remaining.substring(closeIdx + closeTag.length());
                    } else {
                        break;
                    }
                }
            }

            this.thinking = thinkingBuilder.length() > 0 ? thinkingBuilder.toString() : null;
            this.content = remaining.trim();
        }

        private boolean hasThinking() {
            return thinking != null && !thinking.isEmpty();
        }
        private String getThinking() {
            return thinking;
        }
        private String getContent() {
            return content;
        }
    }
}
