package io.codiqo.lang.config;

import java.util.Locale;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import io.codiqo.api.config.ConfigFileSpec;
import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;

/**
 * The declarative half of a Gradle build's configuration — version catalog, {@code gradle.properties}, wrapper pin.
 * Separate from {@link GradleScriptFileSpec} because these files comment with {@code #} while the scripts use the
 * C-style grammar, and {@link ConfigFileSpec} carries one filter per kind.
 */
public class GradleBuildConfigFileSpec implements ConfigFileSpec {
    /** a List, not a Set: describe() renders these into a stored exclusion reason, and Set.of iterates per-JVM */
    private static final List<String> FILE_NAMES = List.of("gradle.properties", "gradle-wrapper.properties");
    private static final String VERSION_CATALOG_SUFFIX = ".versions.toml";
    private static final IneffectiveLineFilter FILTER = new IneffectiveLineFilter(CommentSyntax.HASH, null);

    @Override
    public boolean matches(String path) {
        String name = FilenameUtils.getName(path).toLowerCase(Locale.ROOT);
        return BooleanUtils.or(new boolean[]{FILE_NAMES.contains(name), name.endsWith(VERSION_CATALOG_SUFFIX)});
    }
    @Override
    public String describe() {
        return StringUtils.join(FILE_NAMES, ", ") + ", *" + VERSION_CATALOG_SUFFIX;
    }
    @Override
    public IneffectiveLineFilter lineFilter() {
        return FILTER;
    }
}
