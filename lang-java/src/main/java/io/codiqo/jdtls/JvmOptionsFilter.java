package io.codiqo.jdtls;

import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;

import lombok.experimental.UtilityClass;

@UtilityClass
public class JvmOptionsFilter {
    private static final Pattern MEMORY_PATTERN = Pattern.compile("^-X(ms|mx|ss)\\S+$");

    public static List<String> keepMemory(String raw) {
        return Splitter.on(CharMatcher.whitespace()).omitEmptyStrings().splitToList(StringUtils.defaultString(raw))
                .stream()
                .filter(t -> MEMORY_PATTERN.matcher(t).matches())
                .toList();
    }
}
