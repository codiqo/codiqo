package io.codiqo.lang.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.codiqo.api.diff.CallShape;
import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.lang.config.FileKind.Scoring;

class FileKindsTest {
    @Test
    void whitespaceIsFreeFormInBraceDelimitedLanguages() {
        assertTrue(freeFormWhitespace("src/main/java/com/x/Foo.java"));
        assertTrue(freeFormWhitespace("Foo.kt"));
        assertTrue(freeFormWhitespace("Foo.scala"));
        assertTrue(freeFormWhitespace("app/widget.tsx"));
        assertTrue(freeFormWhitespace("main.go"));
        assertTrue(freeFormWhitespace("build.gradle"), "a Groovy build script is as brace-delimited as its Kotlin twin");
        assertTrue(freeFormWhitespace("build.gradle.kts"));
    }
    @Test
    void whitespaceCountsInLayoutSignificantDescriptorAndUnknownFiles() {
        assertFalse(freeFormWhitespace("script.py"));
        assertFalse(freeFormWhitespace("config.yaml"));
        assertFalse(freeFormWhitespace("config.yml"));
        assertFalse(freeFormWhitespace("Makefile"));
        assertFalse(freeFormWhitespace("notes.txt"));
        assertFalse(freeFormWhitespace("pom.xml"));
        assertFalse(freeFormWhitespace("api/user.proto"));
        assertFalse(freeFormWhitespace("gradle.properties"));
        assertFalse(freeFormWhitespace("gradle/libs.versions.toml"));
    }
    @Test
    void recognitionIgnoresCase() {
        assertTrue(freeFormWhitespace("Foo.JAVA"));
        assertTrue(freeFormWhitespace("Bar.Kt"));
        assertEquals(FileKinds.of("pom.xml"), FileKinds.of("modules/core/POM.XML"));
    }
    @Test
    void aWholeNameOrCompoundSuffixWinsOverTheExtension() {
        assertEquals(Scoring.LINES, FileKinds.of("build.gradle.kts").orElseThrow().getScoredBy(), "a Gradle script, though .kts is Kotlin");
        assertEquals(Scoring.LINES, FileKinds.of("gradle/libs.versions.toml").orElseThrow().getScoredBy());

        FileKind kotlin = FileKinds.of("scripts/release.kts").orElseThrow();
        assertEquals(Scoring.NOT_SCORED, kotlin.getScoredBy(), "a bare Kotlin script is code, not a build descriptor");
        assertEquals("kotlin", kotlin.getLanguage().orElseThrow().getWireId());

        assertEquals(Optional.empty(), FileKinds.of("config/server.toml"));
        assertEquals(Optional.empty(), FileKinds.of("src/main/resources/application.properties"));
    }
    /**
     * A file must have one kind, or the rows' order would decide its rules without anyone choosing it: a second row
     * claiming {@code kts} would change how a Kotlin script's re-indent is diffed, and a row named
     * {@code libs.versions.toml} beside the {@code .versions.toml} suffix would take the version catalogue. Every name
     * and suffix is tried as a file name against every row, and every extension against every row's extensions.
     */
    @Test
    void noTwoKindsClaimOneFileAtTheSamePrecedence() {
        for (FileKind kind : FileKinds.all()) {
            Stream.concat(kind.getNames().stream(), kind.getSuffixes().stream().map(suffix -> "file" + suffix)).forEach(name -> {
                assertEquals(List.of(kind), FileKinds.all().stream().filter(other -> other.isNamed(name)).toList(), name + " is claimed by more than one kind");
                assertEquals(name.toLowerCase(Locale.ROOT), name, "a rule is matched against the lower-cased name");
            });
            for (String extension : kind.getExtensions()) {
                assertEquals(List.of(kind), FileKinds.all().stream().filter(other -> other.getExtensions().contains(extension)).toList(), extension + " is claimed by more than one kind");
                assertEquals(extension.toLowerCase(Locale.ROOT), extension, "a rule is matched against the lower-cased name");
            }
        }
    }
    @Test
    void everyKindScoredByUnitsNamesItsLanguageFiltersItsLinesAndCountsItsCalls() {
        List<FileKind> units = FileKinds.all().stream().filter(kind -> kind.isScoredBy(Scoring.UNITS)).toList();

        assertEquals(List.of(FileKinds.JAVA), units.stream().map(kind -> kind.getLanguage().orElseThrow()).toList());
        for (FileKind kind : units) {
            assertNotEquals(IneffectiveLineFilter.NONE, kind.getLineFilter());
            assertNotEquals(CallShape.NONE, kind.getCalls());
        }
    }
    /**
     * {@link FileKinds#ofLanguage} takes the first row whose wire value matches without regard to case, so a second row
     * with the same value would never be read, and files of its language would take the first row's rules.
     */
    @Test
    void noTwoKindsShareAWireValue() {
        List<String> wireValues = FileKinds.all().stream()
                .flatMap(kind -> kind.getLanguage().stream())
                .map(language -> language.getWireId().toLowerCase(Locale.ROOT))
                .toList();

        assertEquals(Set.copyOf(wireValues).size(), wireValues.size(), wireValues + " repeats a wire value");
    }
    @Test
    void aWireValueFindsItsKindWithoutRegardToCase() {
        assertEquals(Scoring.UNITS, FileKinds.ofLanguage("JAVA").orElseThrow().getScoredBy());
        assertEquals(List.of("ts", "tsx"), FileKinds.ofLanguage("typescript").orElseThrow().getExtensions());
        assertEquals(Optional.empty(), FileKinds.ofLanguage("ts"), "an extension is not a wire value");
        assertEquals(Optional.empty(), FileKinds.ofLanguage(null), "a diff-only file with no extension carries no language");
    }
    @Test
    void theWordsReadByNameAreTheImportWordAndTheWordsThatAreNoCalls() {
        assertEquals(Set.of("import", "if", "while", "for", "switch", "catch", "synchronized", "try", "return", "throw"),
                FileKinds.ofLanguage("java").orElseThrow().getWordsReadByName());
        assertEquals(Set.of("import"), FileKinds.of("api/user.proto").orElseThrow().getWordsReadByName());
        assertEquals(Set.of(), FileKinds.of("pom.xml").orElseThrow().getWordsReadByName());
    }
    private static boolean freeFormWhitespace(String path) {
        return FileKinds.of(path).filter(FileKind::isFreeFormWhitespace).isPresent();
    }
}
