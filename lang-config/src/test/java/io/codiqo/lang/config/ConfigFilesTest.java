package io.codiqo.lang.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
    void recognisesGradleScriptsByExtension() {
        assertTrue(ConfigFiles.isConfigFile("build.gradle"));
        assertTrue(ConfigFiles.isConfigFile("settings.gradle"));
        assertTrue(ConfigFiles.isConfigFile("spring-kafka/build.gradle"));
        assertTrue(ConfigFiles.isConfigFile("publish-maven.gradle"), "a build splits across as many scripts as it likes");
        assertTrue(ConfigFiles.isConfigFile("build.gradle.kts"));
        assertTrue(ConfigFiles.isConfigFile("buildSrc/src/main/kotlin/conventions.gradle.kts"));
    }
    @Test
    void recognisesGradleDeclarativeConfig() {
        assertTrue(ConfigFiles.isConfigFile("gradle/libs.versions.toml"));
        assertTrue(ConfigFiles.isConfigFile("gradle.properties"));
        assertTrue(ConfigFiles.isConfigFile("gradle/wrapper/gradle-wrapper.properties"));
    }
    @Test
    void rejectsNonConfigFiles() {
        assertFalse(ConfigFiles.isConfigFile("codiqo-analyze.yml"));
        assertFalse(ConfigFiles.isConfigFile("Foo.java"));
        assertFalse(ConfigFiles.isConfigFile("settings.xml"));
        assertFalse(ConfigFiles.isConfigFile("README.md"));
        assertFalse(ConfigFiles.isConfigFile("scripts/release.kts"), "a bare Kotlin script is code, not a build descriptor");
        assertFalse(ConfigFiles.isConfigFile("src/main/resources/application.properties"), "only Gradle's own properties files are config");
        assertFalse(ConfigFiles.isConfigFile("config/server.toml"));
    }
    @Test
    void configOnlyRequiresEveryPathToBeConfig() {
        assertTrue(ConfigFiles.isConfigOnly(List.of("pom.xml", "api/user.proto")));
        assertTrue(ConfigFiles.isConfigOnly(List.of("build.gradle", "gradle/libs.versions.toml")),
                "a Gradle dependency bump is config-only, exactly as the pom.xml equivalent is");
        assertFalse(ConfigFiles.isConfigOnly(List.of("pom.xml", "Foo.java")));
        assertFalse(ConfigFiles.isConfigOnly(List.of("build.gradle", "Foo.java")));
        assertFalse(ConfigFiles.isConfigOnly(List.of("README.md")));
        assertFalse(ConfigFiles.isConfigOnly(List.of()), "a fileless change set is not config-only");
    }
    @Test
    void mapsFilterPerConfigKind() {
        assertEquals(Optional.of(new IneffectiveLineFilter(CommentSyntax.XML, null)), ConfigFiles.filterFor("pom.xml"));
        assertEquals(Optional.of(new IneffectiveLineFilter(CommentSyntax.C_STYLE, IneffectiveLineFilter.IMPORT_PREFIX)), ConfigFiles.filterFor("user.proto"));
        assertEquals(Optional.of(new IneffectiveLineFilter(CommentSyntax.C_STYLE, null)), ConfigFiles.filterFor("build.gradle"));
        assertEquals(Optional.of(new IneffectiveLineFilter(CommentSyntax.HASH, null)), ConfigFiles.filterFor("gradle/libs.versions.toml"));
        assertEquals(Optional.empty(), ConfigFiles.filterFor("codiqo-analyze.yml"));
    }
}
