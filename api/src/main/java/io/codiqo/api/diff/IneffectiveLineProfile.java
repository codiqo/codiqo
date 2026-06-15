package io.codiqo.api.diff;

import java.util.function.Predicate;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import io.codiqo.api.metrics.CodeLineCounter;

/**
 * Per-language profile deciding which diff lines carry no scoreable change (comments, import-style
 * declarations, blanks). The diff-scoring path filters these so reformatting and boilerplate don't
 * inflate volume. Blank lines are always ineffective and handled by callers; a profile only adds the
 * language-specific comment and import rules.
 *
 * <p>{@code C_STYLE} covers C-style comment languages with {@code import} declarations (Java, proto);
 * {@code XML} covers markup with {@code <!-- --> } comments and no imports (pom.xml); {@code NONE}
 * filters nothing beyond blanks.
 */
public enum IneffectiveLineProfile {
    C_STYLE(CodeLineCounter::isCommentLine, IneffectiveLineProfile::isImportDeclaration),
    XML(IneffectiveLineProfile::isXmlCommentLine, trimmed -> false),
    NONE(trimmed -> false, trimmed -> false);

    private static final String IMPORT_PREFIX = "import ";
    private static final String XML_COMMENT_START = "<!--";
    private static final String XML_COMMENT_END = "-->";

    private final Predicate<String> commentPredicate;
    private final Predicate<String> importPredicate;

    private IneffectiveLineProfile(Predicate<String> commentPredicate, Predicate<String> importPredicate) {
        this.commentPredicate = commentPredicate;
        this.importPredicate = importPredicate;
    }
    public boolean isComment(String trimmed) {
        return commentPredicate.test(trimmed);
    }
    public boolean isImport(String trimmed) {
        return importPredicate.test(trimmed);
    }
    public Predicate<String> commentFilter() {
        return commentPredicate;
    }
    public Predicate<String> commentOrImportFilter() {
        return trimmed -> BooleanUtils.or(new boolean[] { commentPredicate.test(trimmed), importPredicate.test(trimmed) });
    }
    private static boolean isImportDeclaration(String trimmed) {
        return trimmed.startsWith(IMPORT_PREFIX);
    }
    private static boolean isXmlCommentLine(String trimmed) {
        if (StringUtils.isBlank(trimmed)) {
            return false;
        }
        if (trimmed.startsWith(XML_COMMENT_START)) {
            int endIdx = trimmed.indexOf(XML_COMMENT_END, XML_COMMENT_START.length());
            if (endIdx < 0) {
                return true;
            }
            return isTrailingXmlCommentOnly(trimmed.substring(endIdx + XML_COMMENT_END.length()).trim());
        }
        if (trimmed.startsWith(XML_COMMENT_END)) {
            return isTrailingXmlCommentOnly(trimmed.substring(XML_COMMENT_END.length()).trim());
        }
        return false;
    }
    private static boolean isTrailingXmlCommentOnly(String trailing) {
        return StringUtils.isBlank(trailing) || isXmlCommentLine(trailing);
    }
}
