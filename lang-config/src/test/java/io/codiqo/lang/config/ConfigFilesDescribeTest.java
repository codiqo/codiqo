package io.codiqo.lang.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code describeSupported()} is interpolated into the stored NO_ANALYZABLE_DIFF exclusion reason, so two analyses
 * of the same commit have to produce the same string — hence the exact-string assertions on the rendering.
 */
class ConfigFilesDescribeTest {
    @Test
    void everySupportedDescriptorIsNamedInAStableOrder() {
        assertEquals("pom.xml, *.proto, *.gradle, *.gradle.kts, gradle.properties, gradle-wrapper.properties, *.versions.toml",
                ConfigFiles.describeSupported());
    }
    @Test
    void eachSpecRendersItsOwnMatchingRule() {
        assertEquals("pom.xml", new PomFileSpec().describe());
        assertEquals("*.proto", new ProtoFileSpec().describe());
        assertEquals("*.gradle, *.gradle.kts", new GradleScriptFileSpec().describe());
        assertEquals("gradle.properties, gradle-wrapper.properties, *.versions.toml", new GradleBuildConfigFileSpec().describe());
    }
    /** the description has to name everything isConfigFile accepts, so adding a spec without describe() fails here */
    @Test
    void theDescriptionAccountsForEveryKindIsConfigFileAccepts() {
        String described = ConfigFiles.describeSupported();
        for (String path : new String[] {
                "pom.xml", "api/src/main/proto/svc.proto", "build.gradle", "build.gradle.kts",
                "gradle.properties", "gradle/wrapper/gradle-wrapper.properties", "gradle/libs.versions.toml" }) {
            assertTrue(ConfigFiles.isConfigFile(path), path + " must be recognised as a config file");
        }
        for (String fragment : new String[] {
                "pom.xml", "*.proto", "*.gradle", "*.gradle.kts",
                "gradle.properties", "gradle-wrapper.properties", "*.versions.toml" }) {
            assertTrue(described.contains(fragment), "describeSupported() omits " + fragment + ": " + described);
        }
    }
}
