package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.codiqo.api.SourceLanguage;
import io.codiqo.lang.config.FileKind.Scoring;
import io.codiqo.lang.config.FileKinds;

class DefaultLanguageProcessorsExtensionsTest {
    /**
     * A review language still declares the extensions it resolves files by, apart from its registration, since the
     * review reads only review languages. One that left an extension out would review those files as files of no
     * registered language: their labels matched by exact name only and their findings never triaged.
     */
    @Test
    void everySupportedExtensionHasAReviewLanguage() {
        Set<String> reviewed = DefaultLanguageProcessors.reviewLanguages().stream().flatMap(language -> language.extensions().stream()).collect(Collectors.toSet());

        assertEquals(DefaultLanguageProcessors.supportedExtensions(), reviewed);
    }
    /**
     * The scorer reads which files are scored by units from the file kinds, and the units come from the registered
     * languages. A kind scored by units with no registration would leave its files tracked with no unit to score, and a
     * registered language with no such kind, or one whose wire value differs, would have its files untracked and its
     * units' lines counted with no filter.
     */
    @Test
    void theKindsScoredByUnitsAreTheRegisteredLanguages() {
        Set<SourceLanguage> scoredByUnits = FileKinds.all().stream()
                .filter(kind -> kind.isScoredBy(Scoring.UNITS))
                .map(kind -> kind.getLanguage().orElseThrow())
                .collect(Collectors.toSet());

        assertEquals(Set.copyOf(DefaultLanguageProcessors.supportedLanguages()), scoredByUnits);
    }
}
