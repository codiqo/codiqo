package io.codiqo.lang.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import io.codiqo.api.SourceLanguage;
import io.codiqo.api.diff.CallShape;
import io.codiqo.api.diff.IneffectiveLineFilter;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

/**
 * One kind of file, as every rule that reads a file's lines sees it: how the file is recognised, the wire value of
 * the language its units are reported in, which of its lines change nothing, whether its whitespace is free-form, how a
 * call looks on one of its lines and how a change to it is scored. A row of {@link FileKinds}.
 */
@Value
@Builder
public class FileKind {
    /** Whole file names, in lower case, such as {@code pom.xml}; they take precedence over every extension. */
    @Builder.Default
    List<String> names = List.of();

    /** Compound suffixes, in lower case, such as {@code .gradle.kts}; they take precedence over every extension. */
    @Builder.Default
    List<String> suffixes = List.of();

    /** Extensions, in lower case and without the separator. */
    @Builder.Default
    List<String> extensions = List.of();

    /** The value a file of this kind carries as its language on the wire, when the wire names the language. */
    @Getter(AccessLevel.NONE)
    String wireId;

    @Builder.Default
    IneffectiveLineFilter lineFilter = IneffectiveLineFilter.NONE;

    /**
     * Whether whitespace between tokens means nothing in this kind, as in a brace-delimited language, so a change to it
     * alone is cosmetic: the diff ignores a re-indent ({@code JGitDeltaAnalyzer}) and the scorer sets aside a changed line
     * that differs from its counterpart only in spacing outside literals ({@code DiffClassificationDeriver},
     * {@link io.codiqo.api.diff.Spacing}). False where layout is meaning (Python, YAML, a Makefile) and for the
     * descriptors whose values may hold whitespace that matters (pom.xml, .proto, .properties, .toml).
     */
    boolean freeFormWhitespace;

    @Builder.Default
    CallShape calls = CallShape.NONE;

    @NonNull
    Scoring scoredBy;

    /** The language the analysis reads files of this kind in, when the wire names it. */
    public Optional<SourceLanguage> getLanguage() {
        return Optional.ofNullable(wireId).map(wire -> new SourceLanguage(wire, extensions));
    }
    /** Whether a file of this kind carries this value as its language on the wire, compared without regard to case. */
    public boolean isLanguage(String wireValue) {
        return Objects.nonNull(wireId) && wireId.equalsIgnoreCase(wireValue);
    }
    public boolean isScoredBy(Scoring scoring) {
        return scoredBy == scoring;
    }
    /** Whether a file of this name is of this kind by its whole name or a compound suffix, before any extension. */
    public boolean isNamed(String lowerCaseName) {
        return BooleanUtils.or(new boolean[] { names.contains(lowerCaseName), suffixes.stream().anyMatch(lowerCaseName::endsWith) });
    }
    /**
     * The words the scorer reads by name in a line of this kind: the import word and the words that take a call's
     * shape without being one. A hidden-source diff leaves them readable.
     */
    public Set<String> getWordsReadByName() {
        return Sets.union(lineFilter.getImportPrefix().map(StringUtils::trim).map(Set::of).orElse(Set.of()), calls.getNotCalls());
    }
    /**
     * How a person reads the recognition rule, in a fixed order: extensions, then names, then suffixes. It is part of
     * the stored {@code NO_ANALYZABLE_DIFF} exclusion reason, so it must not change between two analyses of a commit.
     */
    public String describe() {
        return Streams.concat(
                extensions.stream().map(extension -> "*" + FilenameUtils.EXTENSION_SEPARATOR_STR + extension),
                names.stream(),
                suffixes.stream().map(suffix -> "*" + suffix))
                .collect(Collectors.joining(", "));
    }

    /** How a change to a file is scored. */
    public enum Scoring {
        /** By the units its language's analysis reports. */
        UNITS,
        /** By its changed lines, as a build descriptor is. */
        LINES,
        /** Not at all: codiqo does not read files of this kind yet. */
        NOT_SCORED
    }

    /** Sets the wire value and the extensions of a language the analysis reads, from the language itself. */
    public static class FileKindBuilder {
        public FileKindBuilder language(SourceLanguage language) {
            return wireId(language.getWireId()).extensions(language.getExtensions());
        }
    }
}
