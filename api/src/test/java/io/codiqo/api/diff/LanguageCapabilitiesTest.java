package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LanguageCapabilitiesTest {
    @Test
    void freeFormLanguagesAreWhitespaceInsensitive() {
        assertTrue(LanguageCapabilities.whitespaceInsensitive("src/main/java/com/x/Foo.java"));
        assertTrue(LanguageCapabilities.whitespaceInsensitive("Foo.kt"));
        assertTrue(LanguageCapabilities.whitespaceInsensitive("Foo.scala"));
        assertTrue(LanguageCapabilities.whitespaceInsensitive("app/widget.tsx"));
        assertTrue(LanguageCapabilities.whitespaceInsensitive("main.go"));
    }
    @Test
    void layoutSignificantAndUnknownAreWhitespaceSensitive() {
        assertFalse(LanguageCapabilities.whitespaceInsensitive("script.py"));
        assertFalse(LanguageCapabilities.whitespaceInsensitive("config.yaml"));
        assertFalse(LanguageCapabilities.whitespaceInsensitive("config.yml"));
        assertFalse(LanguageCapabilities.whitespaceInsensitive("Makefile"));
        assertFalse(LanguageCapabilities.whitespaceInsensitive("notes.txt"));
    }
    @Test
    void extensionMatchIsCaseInsensitive() {
        assertTrue(LanguageCapabilities.whitespaceInsensitive("Foo.JAVA"));
        assertTrue(LanguageCapabilities.whitespaceInsensitive("Bar.Kt"));
    }
}
