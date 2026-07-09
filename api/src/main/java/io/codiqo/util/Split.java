package io.codiqo.util;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Split {
    public List<String> on(String input, char separator) {
        return clean(StringUtils.split(input, separator));
    }
    public List<String> on(String input, String separator) {
        return clean(StringUtils.splitByWholeSeparator(input, separator));
    }
    public List<String> onWhitespace(String input) {
        return clean(StringUtils.split(input));
    }
    private static List<String> clean(String[] parts) {
        if (Objects.isNull(parts)) {
            return List.of();
        }
        return Arrays.stream(parts).map(String::trim).filter(StringUtils::isNotEmpty).toList();
    }
}
