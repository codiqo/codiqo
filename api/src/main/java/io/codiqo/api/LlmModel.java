package io.codiqo.api;

import java.util.Optional;

/**
 * Every model Codiqo calls, named by its Ollama id, which is also the id the build plugin and the LLM proxy speak; a
 * server that calls another provider translates it to that provider's id.
 */
public enum LlmModel {
    DEEPSEEK_V4_PRO("deepseek-v4-pro:cloud", 1024 * 1024),
    DEEPSEEK_V4_1_FLASH("deepseek-v4.1-flash:cloud", RunArgs.DEFAULT_NUM_CTX),
    GLM_5_3_FLASH("glm-5.3-flash:cloud", RunArgs.DEFAULT_NUM_CTX);

    private final String id;
    private final int contextWindow;

    LlmModel(String id, int contextWindow) {
        this.id = id;
        this.contextWindow = contextWindow;
    }
    public String id() {
        return id;
    }
    public int contextWindow() {
        return contextWindow;
    }
    public static Optional<LlmModel> byId(String id) {
        for (LlmModel model : values()) {
            if (model.id.equals(id)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }
}
