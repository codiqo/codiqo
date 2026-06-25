package io.codiqo.lang.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;

class ConfigFilesTest {
    @Test
    void recognisesPomByName() {
        assertTrue(ConfigFiles.isConfigFile("pom.xml"));
        assertTrue(ConfigFiles.isConfigFile("modules/core/pom.xml"));
        assertTrue(ConfigFiles.isConfigFile("POM.XML"));
    }
    @Test
    void recognisesProtoByExtension() {
        assertTrue(ConfigFiles.isConfigFile("user.proto"));
        assertTrue(ConfigFiles.isConfigFile("src/main/proto/api.proto"));
    }
    @Test
    void rejectsNonConfigFiles() {
        assertFalse(ConfigFiles.isConfigFile("codiqo-analyze.yml"));
        assertFalse(ConfigFiles.isConfigFile("Foo.java"));
        assertFalse(ConfigFiles.isConfigFile("settings.xml"));
        assertFalse(ConfigFiles.isConfigFile("README.md"));
    }
    @Test
    void mapsFilterPerConfigKind() {
        assertEquals(Optional.of(new IneffectiveLineFilter(CommentSyntax.XML, null)), ConfigFiles.filterFor("pom.xml"));
        assertEquals(Optional.of(new IneffectiveLineFilter(CommentSyntax.C_STYLE, IneffectiveLineFilter.IMPORT_PREFIX)), ConfigFiles.filterFor("user.proto"));
        assertEquals(Optional.empty(), ConfigFiles.filterFor("codiqo-analyze.yml"));
    }
}
