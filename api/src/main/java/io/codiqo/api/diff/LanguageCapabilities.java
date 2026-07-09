package io.codiqo.api.diff;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;


import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageCapabilities {
    // Free-form (brace/delimiter) languages where leading whitespace is purely presentational, so a
    // re-indent is a cosmetic change. Excludes layout-significant languages (Python, YAML, Makefiles, …)
    // where indentation carries meaning. Keyed on file extension since the analysis-layer language is
    // resolved later than diff generation; unknown extensions are treated as whitespace-significant.
    private static final Set<String> WHITESPACE_INSENSITIVE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "sc", "groovy", "gvy",
            "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "cs", "swift");

    public static boolean whitespaceInsensitive(String path) {
        return WHITESPACE_INSENSITIVE_EXTENSIONS.contains(FilenameUtils.getExtension(path).toLowerCase());
    }
}
