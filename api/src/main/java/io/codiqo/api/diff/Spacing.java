package io.codiqo.api.diff;

import java.util.Set;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;

import lombok.experimental.UtilityClass;

/**
 * How a line of a free-form language reads once its spacing is set aside, so two versions of a line that differ only in
 * spacing ({@code foo(a,  b)} and {@code foo(a, b)}) read the same. Which files are free-form is decided by the file-kind
 * table ({@code FileKinds} in lang-config), not here: in Python, YAML or a Makefile the same whitespace changes what the
 * file means.
 *
 * <p>A run of whitespace outside a literal is dropped, except where removing it would join two tokens into one, which
 * a compiler reads as something else: between two word characters ({@code new Builder} is not {@code newBuilder}) and
 * between two operator characters that begin a longer operator ({@code j + ++k} is not {@code j+++k}, {@code a - > b}
 * is not {@code a -> b}). There it becomes one space. Operators that never join ({@code x = -1}, {@code a && !b}) lose
 * it, so the usual formatter edits around them read as spacing.
 *
 * <p>A literal is read exactly as hidden-source mode reads one ({@code MovedLineDetector.WORD_TOKEN},
 * {@code SourceHiding}): a quote opens it and the next same quote closes it, with no escapes, and its content is kept
 * whole, since {@code "a  b"} and {@code "a b"} are different strings. A hidden submission keeps every quote,
 * whitespace character, every operator, and every character's word class (an identifier becomes a hash of ASCII word
 * characters, a non-ASCII run private-use characters, both read as word characters here), so a pair of lines reads the
 * same here exactly when its hidden pair does, and both score alike. A backtick opens a literal too, which hidden-source mode
 * reads as plain text but keeps character for character. A quote left open runs to the end of the line.
 *
 * <p>Reading literals without escapes errs in one place: after an escaped quote ({@code "say \" a  b"}) the rest of a
 * string reads as code, so a spacing change there is set aside although it changes the string. A quote that opens no
 * literal (a Rust lifetime, an apostrophe in a trailing comment) errs the other way, keeping spacing that means nothing.
 * A line is read on its own, so a line inside a multi-line literal whose opening quote is on an earlier line (a Java
 * text block, a Groovy slashy string) reads as code too.
 */
@UtilityClass
public class Spacing {
    private static final Set<Character> QUOTES = Set.of('"', '\'', '`');
    /**
     * The two-character starts of the longer operators of the brace-delimited languages (Java, Kotlin, JavaScript, Go,
     * the C family), comments included: two operator characters a space keeps apart join only when they make one of these.
     */
    private static final Set<String> JOINING_OPERATORS = Set.of("++", "--", "->", "=>", "&&", "||", "==", "!=", "<=", ">=", "<<", ">>", "::", "+=", "-=",
            "*=", "/=", "%=", "&=", "|=", "^=", "//", "/*", "*/", "**", "??", "?:", "!!", "<-", ":=", "=~", "!~");
    private static final Set<Character> WORD_SYMBOLS = Set.of('_', '$');
    /** The quote of the literal being read, or this outside every literal. */
    private static final char OUTSIDE_LITERALS = '\0';

    /** The line as a free-form language reads it: its spacing outside literals reduced to what keeps tokens apart. */
    public String withoutSpacing(String line) {
        StringBuilder toReturn = new StringBuilder(line.length());
        char quote = OUTSIDE_LITERALS;
        boolean spaced = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == OUTSIDE_LITERALS) {
                if (Character.isWhitespace(c)) {
                    spaced = toReturn.length() > 0;
                } else {
                    /**
                     * && rather than BooleanUtils.and, whose arguments are all evaluated: spaced is only set once a
                     * character was kept, and before that the last kept character, which joins reads, does not exist.
                     */
                    if (spaced && joins(toReturn.charAt(toReturn.length() - 1), c)) {
                        toReturn.append(StringUtils.SPACE);
                    }
                    spaced = false;
                    toReturn.append(c);
                    if (QUOTES.contains(c)) {
                        quote = c;
                    }
                }
            } else {
                toReturn.append(c);
                if (c == quote) {
                    quote = OUTSIDE_LITERALS;
                }
            }
        }
        return toReturn.toString();
    }
    /** whether two characters a run of whitespace separates would read as one token without it */
    private static boolean joins(char before, char after) {
        return BooleanUtils.or(new boolean[] {
                BooleanUtils.and(new boolean[] { isWord(before), isWord(after) }),
                JOINING_OPERATORS.contains(String.valueOf(new char[] { before, after })) });
    }
    /**
     * A character of an identifier, a number or a keyword: an ASCII letter, digit, underscore or dollar, or any other
     * character outside ASCII that is not whitespace, since a language may name things in any script and hidden-source
     * mode turns such a run into private-use characters, which then read alike.
     */
    private static boolean isWord(char c) {
        return BooleanUtils.or(new boolean[] {
                CharUtils.isAsciiAlphanumeric(c),
                WORD_SYMBOLS.contains(c),
                BooleanUtils.and(new boolean[] { BooleanUtils.negate(CharUtils.isAscii(c)), BooleanUtils.negate(Character.isWhitespace(c)) }) });
    }
}
