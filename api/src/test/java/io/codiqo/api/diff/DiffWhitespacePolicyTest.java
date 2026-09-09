package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiffWhitespacePolicyTest {
    @Test
    void freeFormLanguagesAreWhitespaceInsensitive() {
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("src/main/java/com/x/Foo.java"));
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("Foo.kt"));
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("Foo.scala"));
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("app/widget.tsx"));
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("main.go"));
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("build.gradle"),
                "a Groovy build script is as brace-delimited as its Kotlin twin");
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("build.gradle.kts"));
    }
    @Test
    void layoutSignificantAndUnknownAreWhitespaceSensitive() {
        assertFalse(DiffWhitespacePolicy.whitespaceInsensitive("script.py"));
        assertFalse(DiffWhitespacePolicy.whitespaceInsensitive("config.yaml"));
        assertFalse(DiffWhitespacePolicy.whitespaceInsensitive("config.yml"));
        assertFalse(DiffWhitespacePolicy.whitespaceInsensitive("Makefile"));
        assertFalse(DiffWhitespacePolicy.whitespaceInsensitive("notes.txt"));
    }
    @Test
    void extensionMatchIsCaseInsensitive() {
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("Foo.JAVA"));
        assertTrue(DiffWhitespacePolicy.whitespaceInsensitive("Bar.Kt"));
    }
}
