package io.codiqo.api;

public interface LlmTokenizers {
    int estimateTokens(String model, String text);
}
