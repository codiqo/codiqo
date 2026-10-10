package io.codiqo.core.java;

import java.lang.constant.ConstantDescs;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.base.Splitter;

import lombok.experimental.UtilityClass;

/**
 * Java member signatures in the one form that two independent writers of the same member agree on. The index prints
 * parameter types with their type arguments; a reviewer reading the source qualifies them, erases them, writes a
 * varargs or names a constructor {@code <init>}.
 */
@UtilityClass
public class JavaSignatures {
    private static final Pattern TYPE_ANNOTATION = Pattern.compile("@[\\w.]+(\\([^)]*\\))?");
    private static final String VARARGS = "...";
    private static final String ARRAY = "[]";
    private static final String NESTED_TYPE = "#";
    /**
     * PMD prints a parameter type it could not resolve on the module's auxiliary classpath (a missing dependency,
     * generated sources that were not compiled, a broken build) with this prefix, as {@code a(*Unknown)}. A reviewer
     * reading the source writes {@code a(Unknown)}, so unless the marker goes, no label can name such a unit.
     */
    private static final String UNRESOLVED_TYPE = "*";

    /**
     * {@code name(Types)} without whitespace, type arguments or type parameters, every name simple, and a constructor
     * named after {@code className}: in {@code Totals}, both {@code <init>(java.util.Map<String, List<Integer>>, int...)}
     * and {@code Totals(Map<K, V>, int[]) <K, V>} are {@code Totals(Map,int[])}. Text without a parameter list keeps
     * only the first two of those steps.
     */
    public String comparable(String signature, String className) {
        String compact = StringUtils.deleteWhitespace(TYPE_ANNOTATION.matcher(signature).replaceAll(StringUtils.EMPTY));
        if (Strings.CS.startsWith(compact, ConstantDescs.INIT_NAME)) {
            compact = className + compact.substring(ConstantDescs.INIT_NAME.length());
        }

        String erased = eraseTypeArguments(compact);
        String parameters = StringUtils.substringBetween(erased, "(", ")");
        if (Objects.nonNull(parameters)) {
            return simpleTypeName(StringUtils.substringBefore(erased, '(')) + '('
                    + Splitter.on(',').omitEmptyStrings().splitToStream(parameters).map(JavaSignatures::simpleTypeName).collect(Collectors.joining(",")) + ')';
        }
        return erased;
    }
    /** the text without annotations or type arguments, whitespace kept: {@code @Override Map<K, V> Totals.run()} is {@code  Map Totals.run()} */
    public String declaration(String signature) {
        return eraseTypeArguments(TYPE_ANNOTATION.matcher(signature).replaceAll(StringUtils.EMPTY));
    }
    /**
     * {@code @Nullable java.util.Map<String, List<Integer>>} is {@code Map}, {@code String...} is {@code String[]}, a
     * nested type the index prints as {@code Outer#Inner} is {@code Inner}, and a type PMD could not resolve,
     * {@code *Unknown}, is {@code Unknown}, as a reviewer writes it
     */
    public String simpleTypeName(String type) {
        String compact = StringUtils.deleteWhitespace(declaration(type)).replace(VARARGS, ARRAY);
        return Strings.CS.removeStart(compact.substring(StringUtils.lastIndexOfAny(compact, ".", NESTED_TYPE) + 1), UNRESOLVED_TYPE);
    }
    private static String eraseTypeArguments(String text) {
        StringBuilder toReturn = new StringBuilder();
        int depth = 0;
        for (char next : text.toCharArray()) {
            if (next == '<') {
                depth++;
            } else if (next == '>') {
                depth--;
            } else if (depth == 0) {
                toReturn.append(next);
            }
        }
        return toReturn.toString();
    }
}
