package io.codiqo.llm;

import java.nio.BufferOverflowException;

import lombok.Getter;

/**
 * A prompt that cannot fit the model's context window, so retrying it can never succeed. It extends
 * {@link BufferOverflowException} because that is the type schedulers match to treat an oversized prompt as a terminal
 * failure rather than a transient one; that type carries no message, so this one adds the token counts the failure
 * record needs.
 */
@Getter
public class PromptOverflowException extends BufferOverflowException {
    private static final long serialVersionUID = 1L;

    private final int requiredTokens;
    private final int contextWindow;
    private final String detail;

    public PromptOverflowException(int requiredTokens, int contextWindow, String detail) {
        this.requiredTokens = requiredTokens;
        this.contextWindow = contextWindow;
        this.detail = detail;
    }
    @Override
    public String getMessage() {
        return getDetail();
    }
}
