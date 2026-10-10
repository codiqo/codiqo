package io.codiqo.api.code;

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
    /**
     * {@code @Nullable java.util.Map<String, List<Integer>>} is {@code Map}, {@code String...} is {@code String[]}, and
     * a nested type the index prints as {@code Outer#Inner} is {@code Inner}, as a reviewer writes it
     */
    public String simpleTypeName(String type) {
        String compact = StringUtils.deleteWhitespace(TYPE_ANNOTATION.matcher(eraseTypeArguments(type)).replaceAll(StringUtils.EMPTY)).replace(VARARGS, ARRAY);
        return compact.substring(StringUtils.lastIndexOfAny(compact, ".", NESTED_TYPE) + 1);
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
