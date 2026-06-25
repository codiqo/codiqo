package io.codiqo.api.diff;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * Comment grammar for a single trimmed line: an optional line-comment prefix, the block-comment
 * delimiters, and the optional block-continuation marker (e.g. the leading {@code *} of a Java
 * block-comment body). The same marker-scan serves every C-style language and XML markup — only the
 * markers differ.
 *
 * <p>Operates on one diff line in isolation. A unified-diff fragment is never a parseable document,
 * so an AST/markup parser cannot run here; full-file comment detection (where a real parser is
 * viable) lives in the language modules, e.g. {@code JavaLineCountAnalyzer} via PMD.
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class CommentSyntax {
    public static final CommentSyntax C_STYLE = new CommentSyntax("//", "/*", "*/", "*");
    public static final CommentSyntax XML = new CommentSyntax(null, "<!--", "-->", null);
    public static final CommentSyntax NONE = new CommentSyntax(null, null, null, null);

    private final String lineComment;
    private final String blockStart;
    private final String blockEnd;
    private final String blockContinuation;

    public boolean isCommentLine(String trimmed) {
        if (StringUtils.isBlank(trimmed)) {
            return false;
        }
        if (Objects.nonNull(lineComment) && trimmed.startsWith(lineComment)) {
            return true;
        }
        if (Objects.nonNull(blockEnd) && trimmed.startsWith(blockEnd)) {
            return isTrailingCommentOnly(trimmed.substring(blockEnd.length()).trim());
        }
        if (Objects.nonNull(blockContinuation) && trimmed.startsWith(blockContinuation)) {
            return true;
        }
        if (Objects.nonNull(blockStart) && trimmed.startsWith(blockStart)) {
            int endIdx = trimmed.indexOf(blockEnd, blockStart.length());
            if (endIdx < 0) {
                return true;
            }
            return isTrailingCommentOnly(trimmed.substring(endIdx + blockEnd.length()).trim());
        }
        return false;
    }
    private boolean isTrailingCommentOnly(String trailing) {
        return StringUtils.isBlank(trailing) || isCommentLine(trailing);
    }
}
