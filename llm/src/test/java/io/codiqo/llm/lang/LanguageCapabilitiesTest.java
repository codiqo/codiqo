package io.codiqo.llm.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.LanguageEnum;
import io.codiqo.lang.config.FileKind.Scoring;
import io.codiqo.lang.config.FileKinds;

class LanguageCapabilitiesTest {
    @Test
    void pomAndProtoAreLineCountScoredAndDiffClassified() {
        assertTrue(LanguageCapabilities.isLineCountScored(file("pom.xml", null)));
        assertTrue(LanguageCapabilities.isLineCountScored(file("api/v1/user.proto", null)));
        assertTrue(LanguageCapabilities.requiresDiffClassification(file("pom.xml", null)));
        assertTrue(LanguageCapabilities.requiresDiffClassification(file("api/v1/user.proto", null)));
    }
    @Test
    void genericConfigIsNeitherScoredNorClassified() {
        FileChangeModel yaml = file("src/main/resources/application.yaml", null);
        assertFalse(LanguageCapabilities.isLineCountScored(yaml));
        assertFalse(LanguageCapabilities.requiresDiffClassification(yaml));
    }
    @Test
    void javaIsDiffClassifiedButNotLineCountScored() {
        FileChangeModel java = file("src/main/java/Foo.java", LanguageEnum.JAVA);
        assertTrue(LanguageCapabilities.requiresDiffClassification(java));
        assertFalse(LanguageCapabilities.isLineCountScored(java));
    }
    @Test
    void filterForSelectsByLanguageAndPath() {
        IneffectiveLineFilter cStyle = new IneffectiveLineFilter(CommentSyntax.C_STYLE, IneffectiveLineFilter.IMPORT_PREFIX);
        IneffectiveLineFilter xml = new IneffectiveLineFilter(CommentSyntax.XML, null);

        assertEquals(cStyle, LanguageCapabilities.filterFor(file("Foo.java", LanguageEnum.JAVA)));
        assertEquals(cStyle, LanguageCapabilities.filterFor(file("user.proto", null)));
        assertEquals(xml, LanguageCapabilities.filterFor(file("pom.xml", null)));
        assertEquals(IneffectiveLineFilter.NONE, LanguageCapabilities.filterFor(file("application.yaml", null)));
        assertEquals(IneffectiveLineFilter.NONE, LanguageCapabilities.filterFor(file("web/app.ts", null)), "a kind codiqo does not read filters nothing");
    }
    /**
     * A diff-only submission resolves no language. Its Java files are scored on the server with their comments and
     * deleted imports counted, and taking Java's filter by the extension would change those degraded scores.
     */
    @Test
    void aSourceFileWithNoResolvedLanguageIsFilteredByNothing() {
        assertEquals(IneffectiveLineFilter.NONE, LanguageCapabilities.filterFor(file("src/main/java/Foo.java", null)));
        assertTrue(LanguageCapabilities.kindScoredByUnits(file("src/main/java/Foo.java", null)).isEmpty());
    }
    /**
     * A file of a language scored by units is submitted with its row's wire value as the API's language enum
     * ({@code FileAnalysisPopulator}), and {@link LanguageEnum#fromValue} throws on a value the enum lacks, which would
     * fail every submission that changes such a file.
     */
    @Test
    void everyKindScoredByUnitsIsALanguageTheApiNames() {
        List<String> wireValues = FileKinds.all().stream()
                .filter(kind -> kind.isScoredBy(Scoring.UNITS))
                .map(kind -> kind.getLanguage().orElseThrow().getWireId())
                .toList();

        assertEquals(wireValues, wireValues.stream().map(wire -> LanguageEnum.fromValue(wire).getValue()).toList());
    }
    @Test
    void hasCodeChangesFollowsResolvedLanguage() {
        assertTrue(LanguageCapabilities.hasCodeChanges(Arrays.asList(null, LanguageEnum.JAVA)));
        assertFalse(LanguageCapabilities.hasCodeChanges(Arrays.asList(null, null)), "config and yaml files resolve to no language");
        assertFalse(LanguageCapabilities.hasCodeChanges(List.of()));
    }
    private static FileChangeModel file(String path, LanguageEnum language) {
        FileChangeModel toReturn = new FileChangeModel();
        toReturn.setPath(path);
        toReturn.setLanguage(language);
        return toReturn;
    }
}
