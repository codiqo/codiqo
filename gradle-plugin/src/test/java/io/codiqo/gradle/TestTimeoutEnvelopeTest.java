package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;

/**
 * The envelope every Test task is capped with. It has to come out identical to the Maven fork's, because a per-test
 * timeout that differs between build tools makes a hanging test abort the analysis on one and be excused on the other.
 */
class TestTimeoutEnvelopeTest {
    @Test
    void unsetPerTestTimeoutIsDerivedFromTheTaskTimeout() {
        RunArgs timeouts = GradleSettings.runArgs(Map.of("codiqo.testTimeoutMinutes", "10"));

        assertEquals(Duration.ofMinutes(10), timeouts.getTestTimeout());
        assertEquals(Duration.ofMinutes(5), timeouts.getPerTestTimeout());
    }
    @Test
    void explicitPerTestTimeoutOverridesTheDerivation() {
        CodiqoExtension ext = new CodiqoExtension();
        ext.setTestTimeoutMinutes(30L);
        ext.setPerTestTimeoutMinutes(2L);

        assertEquals(Duration.ofMinutes(2), timeouts(ext).getPerTestTimeout());
    }
    @Test
    void perTestTimeoutIsCappedAtTheRunArgsCeiling() {
        CodiqoExtension ext = new CodiqoExtension();
        ext.setTestTimeoutMinutes(120L);

        assertEquals(RunArgs.PER_TEST_TIMEOUT_MAX, timeouts(ext).getPerTestTimeout());
    }
    private static RunArgs timeouts(CodiqoExtension ext) {
        return GradleSettings.runArgs(GradleSettings.of(unset -> null, ext));
    }
}
