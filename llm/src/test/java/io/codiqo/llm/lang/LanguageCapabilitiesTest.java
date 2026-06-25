package io.codiqo.llm.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.LanguageEnum;

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
    }
    private static FileChangeModel file(String path, LanguageEnum language) {
        FileChangeModel toReturn = new FileChangeModel();
        toReturn.setPath(path);
        toReturn.setLanguage(language);
        return toReturn;
    }
}
