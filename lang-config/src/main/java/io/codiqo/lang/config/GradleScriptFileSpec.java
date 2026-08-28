package io.codiqo.lang.config;

import java.util.Locale;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;

import io.codiqo.api.config.ConfigFileSpec;
import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;

/**
 * Gradle build scripts — the counterpart of {@link PomFileSpec}. Matched by extension rather than by name, because a
 * build splits across as many scripts as it likes and every one of them is a build descriptor.
 *
 * <p>A bare {@code .kts} is deliberately NOT matched: that is an ordinary Kotlin script, and scoring real code by
 * line count would be wrong. Groovy and Kotlin share the C-style comment grammar, so one filter covers both, and a
 * script's imports are ordinary code rather than the ineffective lines a {@code .proto}'s are.
 */
public class GradleScriptFileSpec implements ConfigFileSpec {
    private static final String GROOVY_SCRIPT_EXTENSION = "gradle";
    private static final String KOTLIN_SCRIPT_SUFFIX = ".gradle.kts";
    private static final IneffectiveLineFilter FILTER = new IneffectiveLineFilter(CommentSyntax.C_STYLE, null);

    @Override
    public boolean matches(String path) {
        String name = FilenameUtils.getName(path).toLowerCase(Locale.ROOT);
        return BooleanUtils.or(new boolean[]{
                GROOVY_SCRIPT_EXTENSION.equals(FilenameUtils.getExtension(name)),
                name.endsWith(KOTLIN_SCRIPT_SUFFIX)});
    }
    @Override
    public IneffectiveLineFilter lineFilter() {
        return FILTER;
    }
}
