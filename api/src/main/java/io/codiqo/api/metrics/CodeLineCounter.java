package io.codiqo.api.metrics;

import org.apache.commons.lang3.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class CodeLineCounter {
    public static final String LINE_COMMENT_PREFIX = "//";
    public static final String BLOCK_COMMENT_START = "/*";
    public static final String BLOCK_COMMENT_END = "*/";
    public static final String BLOCK_COMMENT_LINE_PREFIX = "*";

    public static boolean isCommentLine(String trimmedContent) {
        if (StringUtils.isBlank(trimmedContent)) {
            return false;
        }
        if (trimmedContent.startsWith(LINE_COMMENT_PREFIX)) {
            return true;
        }
        if (trimmedContent.startsWith(BLOCK_COMMENT_END)) {
            return isTrailingCommentOnly(trimmedContent.substring(BLOCK_COMMENT_END.length()).trim());
        }
        if (trimmedContent.startsWith(BLOCK_COMMENT_LINE_PREFIX)) {
            return true;
        }
        if (trimmedContent.startsWith(BLOCK_COMMENT_START)) {
            int endIdx = trimmedContent.indexOf(BLOCK_COMMENT_END, BLOCK_COMMENT_START.length());
            if (endIdx < 0) {
                return true;
            }
            return isTrailingCommentOnly(trimmedContent.substring(endIdx + BLOCK_COMMENT_END.length()).trim());
        }
        return false;
    }

    private static boolean isTrailingCommentOnly(String trailingContent) {
        return StringUtils.isBlank(trailingContent) || isCommentLine(trailingContent);
    }
}
