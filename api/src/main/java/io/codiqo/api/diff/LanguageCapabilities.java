package io.codiqo.api.diff;

import org.apache.commons.io.FilenameUtils;

import com.google.common.collect.ImmutableSet;

import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageCapabilities {
    // Free-form (brace/delimiter) languages where leading whitespace is purely presentational, so a
    // re-indent is a cosmetic change. Excludes layout-significant languages (Python, YAML, Makefiles, …)
    // where indentation carries meaning. Keyed on file extension since the analysis-layer language is
    // resolved later than diff generation; unknown extensions are treated as whitespace-significant.
    private static final ImmutableSet<String> WHITESPACE_INSENSITIVE_EXTENSIONS = ImmutableSet.of(
            "java", "kt", "kts", "scala", "sc", "groovy", "gvy",
            "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "cs", "swift");

    public static boolean whitespaceInsensitive(String path) {
        return WHITESPACE_INSENSITIVE_EXTENSIONS.contains(FilenameUtils.getExtension(path).toLowerCase());
    }
}
