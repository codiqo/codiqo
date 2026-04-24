package io.codiqo.util;

import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Env {
    private final String ENV_PREFIX = "env:";

    public static Optional<String> resolve(String raw) {
        if (StringUtils.isEmpty(raw)) {
            return Optional.empty();
        }
        if (raw.startsWith(ENV_PREFIX)) {
            return Optional.ofNullable(System.getenv(raw.substring(ENV_PREFIX.length()))).filter(StringUtils::isNotEmpty);
        }
        return Optional.of(raw);
    }
    public static String resolveRequired(String raw, String paramName) {
        return resolve(raw).orElseThrow(() -> new IllegalArgumentException(paramName + " is required (inline value or use env:VAR_NAME resolving)"));
    }
    public static void resolveInto(String raw, Consumer<String> setter) {
        resolve(raw).ifPresent(setter);
    }
}
