package io.codiqo.api.diff;

import java.util.Set;

import org.apache.commons.io.FilenameUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageCapabilities {
    /**
     * free-form (brace/delimiter) languages, where leading whitespace is presentational and a re-indent is cosmetic.
     * Layout-significant languages (Python, YAML, Makefiles) are excluded, as are unknown extensions: the
     * analysis-layer language is resolved later than diff generation, so the key is the file extension.
     */
    private static final Set<String> WHITESPACE_INSENSITIVE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "sc", "groovy", "gvy", "gradle",
            "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "cs", "swift");

    public static boolean whitespaceInsensitive(String path) {
        return WHITESPACE_INSENSITIVE_EXTENSIONS.contains(FilenameUtils.getExtension(path).toLowerCase());
    }
}
