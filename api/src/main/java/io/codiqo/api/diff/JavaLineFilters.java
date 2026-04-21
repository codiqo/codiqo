package io.codiqo.api.diff;

import java.util.function.Predicate;

import org.apache.commons.lang3.BooleanUtils;

import io.codiqo.api.metrics.CodeLineCounter;
import lombok.experimental.UtilityClass;

@UtilityClass
public class JavaLineFilters {
    private static final String IMPORT_PREFIX = "import ";

    public static final Predicate<String> NONE = trimmed -> false;
    public static final Predicate<String> COMMENT = CodeLineCounter::isCommentLine;
    public static final Predicate<String> COMMENT_OR_IMPORT = trimmed -> BooleanUtils.or(new boolean[] {
            CodeLineCounter.isCommentLine(trimmed),
            trimmed.startsWith(IMPORT_PREFIX)
    });
}
