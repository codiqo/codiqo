package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

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
}
