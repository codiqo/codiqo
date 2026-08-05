package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jacoco.core.JaCoCo;
import org.junit.jupiter.api.Test;

/**
 * The agent coordinate the analysis build instruments with. JaCoCo.VERSION carries an OSGi build qualifier
 * (0.8.15.202606040825) that is not part of the Maven coordinate, and feeding it to the jacocoAgent configuration
 * verbatim resolves nothing — the symptom is a hard "Could not find org.jacoco:org.jacoco.agent" on every analysed
 * project, so the stripping is worth pinning down.
 */
class JacocoAgentVersionTest {
    @Test
    void agentVersionCarriesNoOsgiQualifier() {
        String version = CodiqoGradlePlugin.agentArtifactVersion();

        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "not a bare maven version: " + version);
        assertTrue(JaCoCo.VERSION.startsWith(version), version + " is not the prefix of " + JaCoCo.VERSION);
    }
    /**
     * the pin only prevents an instrument-with-one-version/parse-with-another divergence if it tracks the JaCoCo the
     * engine itself reads the exec file with, so it must be derived from the classpath rather than hardcoded.
     */
    @Test
    void agentVersionTracksTheCoreOnTheClasspath() {
        assertEquals(
                JaCoCo.VERSION.split("\\.")[0] + "." + JaCoCo.VERSION.split("\\.")[1] + "." + JaCoCo.VERSION.split("\\.")[2],
                CodiqoGradlePlugin.agentArtifactVersion());
    }
}
