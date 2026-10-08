package io.codiqo.maven.populator;

import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

/**
 * Plain-text layout primitives for the TEXT templates, which reach it as {@code #layout} (see
 * {@link TextLayoutDialect}): padding, fixed-point numbers and word wrap. It knows nothing of any
 * report. Its methods are instance methods because a template can only call an object it is handed.
 */
public class TextLayout {
    private static final Splitter WORDS = Splitter.on(CharMatcher.whitespace()).omitEmptyStrings();

    public String left(Object value, int width) {
        return StringUtils.rightPad(String.valueOf(value), width);
    }
    public String fixed(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
    public List<String> words(String text, int width) {
        List<String> toReturn = Lists.newArrayList();
        String line = StringUtils.EMPTY;
        for (String word : WORDS.split(text)) {
            if (line.isEmpty()) {
                line = word;
            } else if (line.length() + 1 + word.length() > width) {
                toReturn.add(line);
                line = word;
            } else {
                line = line + StringUtils.SPACE + word;
            }
        }
        if (StringUtils.isNotEmpty(line)) {
            toReturn.add(line);
        }
        return toReturn;
    }
}
