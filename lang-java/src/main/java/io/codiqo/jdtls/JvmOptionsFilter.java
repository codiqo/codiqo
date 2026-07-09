package io.codiqo.jdtls;

import java.util.List;
import java.util.regex.Pattern;

import io.codiqo.util.Split;
import lombok.experimental.UtilityClass;

@UtilityClass
public class JvmOptionsFilter {
    private static final Pattern MEMORY_PATTERN = Pattern.compile("^-X(ms|mx|ss)\\S+$");

    public static List<String> keepMemory(String raw) {
        return Split.onWhitespace(raw)
                .stream()
                .filter(t -> MEMORY_PATTERN.matcher(t).matches())
                .toList();
    }
}
