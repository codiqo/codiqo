package io.codiqo.llm.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@RequiredArgsConstructor
public class OpenAIClientWrapper {
    private final OpenAIClient client;

    public StreamingResult stream(ChatCompletionCreateParams params, StreamingHandler handler) {
        StreamingResult result = new StreamingResult();
        Map<Long, AccumulatedToolCall> toolCallMap = Maps.newLinkedHashMap();

        try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params)) {
            stream.stream().forEach(chunk -> {
                if (CollectionUtils.isNotEmpty(chunk.choices())) {
                    ChatCompletionChunk.Choice choice = Iterables.getOnlyElement(chunk.choices());
                    choice.finishReason().ifPresent(reason -> result.setFinishReason(reason.toString()));
                    ChatCompletionChunk.Choice.Delta delta = choice.delta();

                    delta.content().ifPresent(content -> {
                        result.appendContent(content);
                        handler.onContent(content);
                    });

                    delta.toolCalls().ifPresent(toolCalls -> {
                        for (ChatCompletionChunk.Choice.Delta.ToolCall tc : toolCalls) {
                            AccumulatedToolCall accumulated = toolCallMap.computeIfAbsent(tc.index(), idx -> new AccumulatedToolCall((int) (long) idx));
                            tc.id().ifPresent(accumulated::setId);
                            tc.function().ifPresent(fn -> {
                                fn.name().ifPresent(accumulated::setName);
                                fn.arguments().ifPresent(args -> accumulated.getArguments().append(args));
                            });
                        }
                    });
                }

                chunk.usage().ifPresent(usage -> {
                    result.setPromptTokens((int) usage.promptTokens());
                    result.setCompletionTokens((int) usage.completionTokens());
                    result.setTotalTokens((int) usage.totalTokens());
                });
            });
        }

        result.setToolCalls(Lists.newArrayList(toolCallMap.values()));
        handler.onComplete(result);
        return result;
    }

    public interface StreamingHandler {
        default void onContent(String delta) {}
        default void onComplete(StreamingResult result) {}
    }

    @Getter
    @Setter
    @ToString
    @RequiredArgsConstructor
    public static class AccumulatedToolCall {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    @Getter
    @Setter
    public static class StreamingResult {
        private final StringBuilder content = new StringBuilder();
        private String finishReason;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private List<AccumulatedToolCall> toolCalls = Lists.newArrayList();

        private void appendContent(String text) {
            if (Objects.nonNull(text)) {
                content.append(text);
            }
        }
        @Override
        public String toString() {
            return String.format("StreamingResult{content=%d chars, tokens=%d, toolCalls=%d}", content.length(), totalTokens, toolCalls.size());
        }
    }
}
