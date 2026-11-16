package io.codiqo.llm.lang;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;

import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.LanguageEnum;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.lang.config.FileKind;
import io.codiqo.lang.config.FileKind.Scoring;
import io.codiqo.lang.config.FileKinds;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageCapabilities {
    public static boolean requiresLineFiltering(LanguageEnum language) {
        return Objects.nonNull(language) && isLineFilteringLanguage(language.getValue());
    }
    public static boolean requiresLineFiltering(FileChangeModel file) {
        return requiresLineFiltering(file.getLanguage());
    }
    public static boolean isLineFilteringLanguage(String languageValue) {
        return FileKinds.ofLanguage(languageValue).filter(kind -> kind.isScoredBy(Scoring.UNITS)).isPresent();
    }
    public static boolean isLineCountScored(FileChangeModel file) {
        return ConfigFiles.isConfigFile(file.getPath());
    }
    public static boolean requiresDiffClassification(FileChangeModel file) {
        return BooleanUtils.or(new boolean[] { requiresLineFiltering(file), isLineCountScored(file) });
    }
    /**
     * whether the change set carries source code a language processor recognised. Language is resolved
     * upstream and stays null for files no LanguageSpec handled, so this answers "was there anything
     * analysable" — not "does a source-looking path exist" (a diff-only submission carries no language).
     * Takes the raw language slots rather than file models because the engine and the server generate
     * their own FileChangeModel/LanguageEnum families from the same schema, and this rule serves both.
     */
    public static boolean hasCodeChanges(Collection<?> resolvedLanguages) {
        return resolvedLanguages.stream().anyMatch(Objects::nonNull);
    }
    /**
     * The filter of a file's lines: that of its resolved language when the language is scored by units, else that of
     * the build descriptor its path names. A source file whose language the analysis did not resolve, as in a
     * diff-only submission, is filtered by nothing even when its extension names a language scored by units: the
     * server scores a degraded commit's files with their comments and deleted imports counted, and taking the
     * language's filter by the extension here would change those scores.
     */
    public static IneffectiveLineFilter filterFor(FileChangeModel file) {
        return kindScoredByUnits(file)
                .or(() -> FileKinds.of(file.getPath()).filter(kind -> kind.isScoredBy(Scoring.LINES)))
                .map(FileKind::getLineFilter)
                .orElse(IneffectiveLineFilter.NONE);
    }
    /**
     * The file kind of the language the analysis resolved for a file, when that language is scored by units: the kind
     * whose filter and call shape the file's units are counted with. A file whose language was not resolved has none,
     * whatever its path names.
     */
    public static Optional<FileKind> kindScoredByUnits(FileChangeModel file) {
        return Optional.ofNullable(file.getLanguage())
                .map(LanguageEnum::getValue)
                .flatMap(FileKinds::ofLanguage)
                .filter(kind -> kind.isScoredBy(Scoring.UNITS));
    }
}
