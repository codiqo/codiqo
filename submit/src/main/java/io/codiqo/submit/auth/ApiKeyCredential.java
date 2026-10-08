package io.codiqo.submit.auth;

import java.util.Objects;
import java.util.function.BiConsumer;

/** A key configured for CI ({@code codiqo.apiKey}) or left behind by a login from before OAuth. */
public final class ApiKeyCredential implements CodiqoCredential {
    public static final String HEADER = "X-API-Key";

    private final String key;

    public ApiKeyCredential(String key) {
        this.key = Objects.requireNonNull(key);
    }
    @Override
    public void accept(BiConsumer<String, String> header) {
        header.accept(HEADER, key);
    }
    @Override
    public String describe() {
        return "API key";
    }
}
