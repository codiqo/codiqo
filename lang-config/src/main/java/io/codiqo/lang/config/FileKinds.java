package io.codiqo.lang.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;

import io.codiqo.api.SourceLanguage;
import io.codiqo.api.diff.CallShape;
import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.lang.config.FileKind.Scoring;
import lombok.experimental.UtilityClass;

/**
 * The one table of file kinds: every rule that reads a file's lines (the diff's whitespace comparator and the
 * scorer's spacing-only lines, the ineffective lines, the invocation count of deleted and moved lines, how a change to
 * a file is scored, and the words a hidden-source diff leaves readable) asks its row. Which files the analysis reads units from is still decided by the
 * extensions of the registered languages, before any row is asked. The server scores with these rules too, through codiqo-llm, and has
 * neither lang-java nor core, so the rows live here and a language module reads its own row: lang-java takes
 * {@link #JAVA} for its registration.
 *
 * <p>A file is recognised by its whole name or a compound suffix first, then by its extension, so
 * {@code build.gradle.kts} is a Gradle script while {@code Release.kts} is Kotlin. No two rows share a name, a suffix or
 * an extension ({@code FileKindsTest}).
 *
 * <p>The languages a rule names but codiqo does not read yet (the free-form languages, whose whitespace changes
 * nothing) have a row that carries only that answer, and the wire value where the wire names the language. The rows scored by units must be exactly the languages
 * {@code DefaultLanguageProcessors} registers, or files of a kind would be tracked with no unit to score.
 */
@UtilityClass
public class FileKinds {
    /** Java as the analysis reads it: its wire value and the extensions written in it. */
    public static final SourceLanguage JAVA = new SourceLanguage("java", List.of("java"));

    private static final IneffectiveLineFilter C_STYLE_WITH_IMPORTS = new IneffectiveLineFilter(CommentSyntax.C_STYLE, IneffectiveLineFilter.IMPORT_PREFIX);
    private static final List<FileKind> KINDS = List.of(
            FileKind.builder()
                    .language(JAVA)
                    .lineFilter(C_STYLE_WITH_IMPORTS)
                    .freeFormWhitespace(true)
                    .calls(CallShape.parenthesised(Set.of("if", "while", "for", "switch", "catch", "synchronized", "try", "return", "throw")))
                    .scoredBy(Scoring.UNITS)
                    .build(),
            FileKind.builder()
                    .names(List.of("pom.xml"))
                    .lineFilter(new IneffectiveLineFilter(CommentSyntax.XML, null))
                    .scoredBy(Scoring.LINES)
                    .build(),
            FileKind.builder()
                    .extensions(List.of("proto"))
                    .lineFilter(C_STYLE_WITH_IMPORTS)
                    .scoredBy(Scoring.LINES)
                    .build(),
            FileKind.builder()
                    .extensions(List.of("gradle"))
                    .suffixes(List.of(".gradle.kts"))
                    .lineFilter(new IneffectiveLineFilter(CommentSyntax.C_STYLE, null))
                    .freeFormWhitespace(true)
                    .scoredBy(Scoring.LINES)
                    .build(),
            FileKind.builder()
                    .names(List.of("gradle.properties", "gradle-wrapper.properties"))
                    .suffixes(List.of(".versions.toml"))
                    .lineFilter(new IneffectiveLineFilter(CommentSyntax.HASH, null))
                    .scoredBy(Scoring.LINES)
                    .build(),
            notRead(new SourceLanguage("kotlin", List.of("kt", "kts"))),
            notRead(new SourceLanguage("javascript", List.of("js", "jsx", "mjs", "cjs"))),
            notRead(new SourceLanguage("typescript", List.of("ts", "tsx"))),
            notRead(new SourceLanguage("go", List.of("go"))),
            notRead(new SourceLanguage("rust", List.of("rs"))),
            notRead("scala", "sc"),
            notRead("groovy", "gvy"),
            notRead("c", "h"),
            notRead("cc", "cpp", "cxx", "hpp", "hh", "hxx"),
            notRead("cs"),
            notRead("swift"));

    public List<FileKind> all() {
        return KINDS;
    }
    public Optional<FileKind> of(String path) {
        String name = FilenameUtils.getName(path).toLowerCase(Locale.ROOT);
        String extension = FilenameUtils.getExtension(name);
        return KINDS.stream().filter(kind -> kind.isNamed(name)).findFirst()
                .or(() -> KINDS.stream().filter(kind -> kind.getExtensions().contains(extension)).findFirst());
    }
    /** The row of the language a file carries on the wire, compared without regard to case. */
    public Optional<FileKind> ofLanguage(String wireValue) {
        return KINDS.stream().filter(kind -> kind.isLanguage(wireValue)).findFirst();
    }
    private FileKind notRead(SourceLanguage language) {
        return FileKind.builder().language(language).freeFormWhitespace(true).scoredBy(Scoring.NOT_SCORED).build();
    }
    private FileKind notRead(String... extensions) {
        return FileKind.builder().extensions(List.of(extensions)).freeFormWhitespace(true).scoredBy(Scoring.NOT_SCORED).build();
    }
}
