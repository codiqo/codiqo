package io.codiqo.llm.client;

import java.util.Objects;
import java.util.function.Consumer;

import org.apache.commons.collections4.CollectionUtils;

import com.google.common.collect.Iterables;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class OpenAIClientWrapper {
    private final OpenAIClient client;

    public ChatCompletion chat(ChatCompletionCreateParams params) {
        return client.chat().completions().create(params);
    }
    public StreamingResult streamAndCollect(ChatCompletionCreateParams params) {
        return streamWithThinking(params, new StreamingHandler() {});
    }
    public StreamingResult streamWithThinking(ChatCompletionCreateParams params, StreamingHandler handler) {
        StreamingResult result = new StreamingResult();
        ThinkingParser parser = new ThinkingParser();

        try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params)) {
            stream.stream().forEach(chunk -> {
                if (CollectionUtils.isNotEmpty(chunk.choices())) {
                    ChatCompletionChunk.Choice choice = Iterables.getOnlyElement(chunk.choices());
                    choice.finishReason().ifPresent(reason -> result.setFinishReason(reason.toString()));
                    ChatCompletionChunk.Choice.Delta delta = choice.delta();

                    delta.content().ifPresent(content -> {
                        parser.process(content,
                                c -> {
                                    result.appendContent(c);
                                    handler.onContent(c);
                                },
                                t -> {
                                    result.appendThinking(t);
                                    handler.onThinking(t);
                                });
                    });

                    chunk.usage().ifPresent(usage -> {
                        result.setPromptTokens((int) usage.promptTokens());
                        result.setCompletionTokens((int) usage.completionTokens());
                        result.setTotalTokens((int) usage.totalTokens());
                    });
                }
            });
        }

        parser.flush(
                content -> {
                    result.appendContent(content);
                    handler.onContent(content);
                },
                thinking -> {
                    result.appendThinking(thinking);
                    handler.onThinking(thinking);
                });

        handler.onComplete(result);
        return result;
    }

    public interface StreamingHandler {
        default void onContent(String delta) {}
        default void onThinking(String delta) {}
        default void onComplete(StreamingResult result) {}
    }

    public static class StreamingResult {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private String finishReason;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        public String getContent() {
            return content.toString();
        }
        public String getThinking() {
            return thinking.toString();
        }
        public String getFinishReason() {
            return finishReason;
        }
        public int getPromptTokens() {
            return promptTokens;
        }
        public int getCompletionTokens() {
            return completionTokens;
        }
        public int getTotalTokens() {
            return totalTokens;
        }
        public boolean hasThinking() {
            return thinking.length() > 0;
        }
        private void setFinishReason(String reason) {
            this.finishReason = reason;
        }
        private void setPromptTokens(int tokens) {
            this.promptTokens = tokens;
        }
        private void setCompletionTokens(int tokens) {
            this.completionTokens = tokens;
        }
        private void setTotalTokens(int tokens) {
            this.totalTokens = tokens;
        }
        private void appendContent(String text) {
            if (Objects.nonNull(text)) {
                content.append(text);
            }
        }
        private void appendThinking(String text) {
            if (Objects.nonNull(text)) {
                thinking.append(text);
            }
        }
        @Override
        public String toString() {
            return String.format("StreamingResult{content=%d chars, thinking=%d chars, tokens=%d}", content.length(), thinking.length(), totalTokens);
        }
    }

    private static class ThinkingParser {
        private static final String[] OPEN_TAGS = { "<think>", "<thinking>", "<reasoning>" };
        private static final String[] CLOSE_TAGS = { "</think>", "</thinking>", "</reasoning>" };

        private final StringBuilder buffer = new StringBuilder();
        private boolean inThinking = false;
        private String activeCloseTag = null;

        private synchronized void process(String text, Consumer<String> onContent, Consumer<String> onThinking) {
            buffer.append(text);

            while (buffer.length() > 0) {
                if (inThinking) {
                    int closeIdx = buffer.indexOf(activeCloseTag);
                    if (closeIdx >= 0) {
                        String thinkContent = buffer.substring(0, closeIdx);
                        onThinking.accept(thinkContent);
                        buffer.delete(0, closeIdx + activeCloseTag.length());
                        inThinking = false;
                        activeCloseTag = null;
                    } else if (couldBePartial(CLOSE_TAGS)) {
                        break;
                    } else {
                        onThinking.accept(buffer.toString());
                        buffer.setLength(0);
                    }
                } else {
                    int openIdx = -1;
                    int tagIdx = -1;

                    for (int i = 0; i < OPEN_TAGS.length; i++) {
                        int idx = buffer.indexOf(OPEN_TAGS[i]);
                        if (idx >= 0 && (openIdx < 0 || idx < openIdx)) {
                            openIdx = idx;
                            tagIdx = i;
                        }
                    }

                    if (openIdx >= 0) {
                        if (openIdx > 0) {
                            onContent.accept(buffer.substring(0, openIdx));
                        }
                        buffer.delete(0, openIdx + OPEN_TAGS[tagIdx].length());
                        inThinking = true;
                        activeCloseTag = CLOSE_TAGS[tagIdx];
                    } else if (couldBePartial(OPEN_TAGS)) {
                        int safeEnd = findSafeEnd();
                        if (safeEnd > 0) {
                            onContent.accept(buffer.substring(0, safeEnd));
                            buffer.delete(0, safeEnd);
                        }
                        break;
                    } else {
                        onContent.accept(buffer.toString());
                        buffer.setLength(0);
                    }
                }
            }
        }
        private void flush(Consumer<String> onContent, Consumer<String> onThinking) {
            if (buffer.length() > 0) {
                if (inThinking) {
                    onThinking.accept(buffer.toString());
                } else {
                    onContent.accept(buffer.toString());
                }
                buffer.setLength(0);
            }
        }
        private boolean couldBePartial(String[] tags) {
            String s = buffer.toString();
            for (String tag : tags) {
                for (int len = 1; len < tag.length() && len <= s.length(); len++) {
                    if (s.endsWith(tag.substring(0, len))) {
                        return true;
                    }
                }
            }
            return false;
        }
        private int findSafeEnd() {
            int lastLt = buffer.lastIndexOf("<");
            return lastLt >= 0 ? lastLt : buffer.length();
        }
    }
}
