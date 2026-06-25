package io.codiqo.api.diff;

import java.util.Objects;
import java.util.function.Predicate;

import org.apache.commons.lang3.BooleanUtils;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * Per-file rule for diff lines that carry no scoreable change, so reformatting and boilerplate don't
 * inflate volume. Combines a {@link CommentSyntax} with an optional import prefix (a line that begins
 * with it — e.g. {@code import } — is ineffective when deleted). Blank lines are always ineffective
 * and handled by callers.
 *
 * <p>This type is the generic mechanism only; which file maps to which filter is decided by the
 * language modules (e.g. {@code lang-config} for pom/proto, {@code LanguageCapabilities} for Java),
 * not here. {@link #NONE} filters nothing beyond blanks and is the default.
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class IneffectiveLineFilter {
    public static final String IMPORT_PREFIX = "import ";
    public static final IneffectiveLineFilter NONE = new IneffectiveLineFilter(CommentSyntax.NONE, null);

    private final CommentSyntax commentSyntax;
    private final String importPrefix;

    public boolean isNone() {
        return equals(NONE);
    }
    public Predicate<String> commentFilter() {
        return commentSyntax::isCommentLine;
    }
    public Predicate<String> commentOrImportFilter() {
        return trimmed -> BooleanUtils.or(new boolean[] { commentSyntax.isCommentLine(trimmed), isImport(trimmed) });
    }
    private boolean isImport(String trimmed) {
        return Objects.nonNull(importPrefix) && trimmed.startsWith(importPrefix);
    }
}
