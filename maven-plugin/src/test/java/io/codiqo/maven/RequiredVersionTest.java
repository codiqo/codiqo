package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * Aether normalises a null version to the empty string rather than refusing it, so a version missing from
 * codiqo.versions would leave the extension silently absent instead of failing the run.
 */
class RequiredVersionTest {
    @Test
    void aPresentVersionIsReturnedVerbatim() {
        Properties versions = new Properties();
        versions.setProperty("codiqo.version", "1.0-SNAPSHOT");

        assertEquals("1.0-SNAPSHOT", AbstractAnalyzeMojo.requiredVersion(versions, "codiqo.version"));
    }
    @Test
    void aMissingVersionFailsAndNamesTheKey() {
        NullPointerException err = assertThrows(NullPointerException.class,
                () -> AbstractAnalyzeMojo.requiredVersion(new Properties(), "jacoco.version"));

        assertTrue(err.getMessage().contains("jacoco.version"),
                "the failure has to name the missing key: " + err.getMessage());
        assertTrue(err.getMessage().contains("codiqo.versions"),
                "and where it was expected: " + err.getMessage());
    }
}
