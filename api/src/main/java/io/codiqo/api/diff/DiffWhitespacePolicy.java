package io.codiqo.api.diff;

import java.util.Locale;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;

import lombok.experimental.UtilityClass;

/** Whether a re-indent in a file of this kind is cosmetic, which decides the diff comparator JGit is given. */
@UtilityClass
public class DiffWhitespacePolicy {
    /**
     * free-form (brace/delimiter) languages, where leading whitespace is presentational. Keyed on the extension
     * rather than the language, because the analysis-layer language is resolved later than diff generation.
     */
    private static final Set<String> WHITESPACE_INSENSITIVE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "sc", "groovy", "gvy", "gradle",
            "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "cs", "swift");

    public static boolean whitespaceInsensitive(String path) {
        // Locale.ROOT rather than the default: a locale-sensitive lowercase would not match these extensions
        return WHITESPACE_INSENSITIVE_EXTENSIONS.contains(FilenameUtils.getExtension(path).toLowerCase(Locale.ROOT));
    }
}
