package io.codiqo.api.diff;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * How a call looks on one raw source line, for the lines no AST is available for: deleted lines and lines a commit
 * moved. A call is an identifier followed by an opening parenthesis, except for the words that take the same shape
 * without being a call, such as {@code if (}. Coarse by design: it also counts a call written inside a string or a
 * comment, and it misses lambdas and method references.
 *
 * <p>A file kind decides which shape its lines have ({@code FileKinds} in lang-config); this type is only the counter.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@ToString
public class CallShape {
    private static final Pattern IDENTIFIER_THEN_PARENTHESIS = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\(");
    /**
     * Counts no call on any line, for a file kind whose calls are not counted. {@code (?!)} is an empty negative
     * lookahead, which fails at every position of every line.
     */
    public static final CallShape NONE = new CallShape(Pattern.compile("(?!)"), Set.of());

    private final Pattern pattern;
    private final Set<String> notCalls;

    public int countInLine(String line) {
        int count = 0;
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            if (notCalls.contains(matcher.group(1))) {
                continue;
            }
            count++;
        }
        return count;
    }
    /**
     * The words that take a call's shape without being a call. A hidden-source diff ({@code SourceHiding}) leaves
     * exactly these readable: every other identifier is replaced by a keyed hash, and a hashed {@code if (} would count
     * as a call.
     */
    public Set<String> getNotCalls() {
        return notCalls;
    }
    /**
     * The call shape of the C family of languages: an identifier, letters, digits, {@code _} and {@code $}, then
     * {@code (}. The pattern's first group is the identifier, which is compared against {@code notCalls}.
     */
    public static CallShape parenthesised(Set<String> notCalls) {
        return new CallShape(IDENTIFIER_THEN_PARENTHESIS, Set.copyOf(notCalls));
    }
}
