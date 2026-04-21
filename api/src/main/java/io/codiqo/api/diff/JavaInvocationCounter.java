package io.codiqo.api.diff;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableSet;

import lombok.experimental.UtilityClass;

/**
 * Approximate Java invocation counter for raw source lines (used on deleted lines where no AST is available).
 * The regex matches identifier-followed-by-open-paren; the keyword filter discards Java control-flow
 * statements that share the same shape ({@code if (}, {@code while (}, etc.) but are not calls.
 * Coarse by design: false positives in strings/comments, misses lambdas and method references.
 */
@UtilityClass
public class JavaInvocationCounter {
    private static final Pattern CALL_SHAPE = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Set<String> CONTROL_FLOW_KEYWORDS = ImmutableSet.of(
            "if", "while", "for", "switch", "catch", "synchronized", "try", "return", "throw");

    public static int countInLine(String line) {
        int count = 0;
        Matcher matcher = CALL_SHAPE.matcher(line);
        while (matcher.find()) {
            if (CONTROL_FLOW_KEYWORDS.contains(matcher.group(1))) {
                continue;
            }
            count++;
        }
        return count;
    }
}
