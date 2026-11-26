package io.codiqo.api.diff;

import java.util.Set;

import org.apache.commons.lang3.BooleanUtils;

import lombok.experimental.UtilityClass;

/**
 * How a line of a free-form language reads once its spacing is set aside: the line without the whitespace that lies
 * outside its string and character literals. Two versions of a line that read the same differ only in spacing
 * ({@code foo(a,  b)} and {@code foo(a, b)}), which a free-form language's compiler never sees. Which files are free-form
 * is decided by the file-kind table ({@code FileKinds} in lang-config), not here: in Python, YAML or a Makefile the same
 * whitespace changes what the file means.
 *
 * <p>Whitespace inside a literal is kept, since {@code "a  b"} and {@code "a b"} are different strings. A literal opens at
 * any of {@code "}, {@code '} and a backtick, closes at the same character, and a backslash escapes the next one; a
 * literal left open runs to the end of the line. An apostrophe that opens no literal (a Rust lifetime, a Kotlin label)
 * is read as one, which keeps whitespace that means nothing: such a line can read as changed when it is not.
 *
 * <p>One case errs the other way: a line inside a multi-line literal whose opening quote is on an earlier line (a Java
 * text block, a Kotlin raw string) reads as code, so a spacing change inside such a string is set aside too. A line is
 * read on its own, without the lines around it, so nothing here can tell.
 */
@UtilityClass
public class Spacing {
    private static final Set<Character> QUOTES = Set.of('"', '\'', '`');
    private static final char ESCAPE = '\\';
    /** The quote of the literal being read, or this outside every literal. */
    private static final char OUTSIDE_LITERALS = '\0';

    /** The line as a free-form language reads it: every whitespace character outside a literal removed. */
    public String withoutSpacing(String line) {
        StringBuilder toReturn = new StringBuilder(line.length());
        char quote = OUTSIDE_LITERALS;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == OUTSIDE_LITERALS) {
                if (QUOTES.contains(c)) {
                    quote = c;
                }
                if (BooleanUtils.negate(Character.isWhitespace(c))) {
                    toReturn.append(c);
                }
            } else {
                toReturn.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == ESCAPE) {
                    escaped = true;
                } else if (c == quote) {
                    quote = OUTSIDE_LITERALS;
                }
            }
        }
        return toReturn.toString();
    }
}
