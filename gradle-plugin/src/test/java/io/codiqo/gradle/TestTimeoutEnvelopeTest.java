package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;

/**
 * The envelope every Test task is capped with. It has to come out identical to the Maven fork's, because a per-test
 * timeout that differs between build tools makes a hanging test abort the analysis on one and be excused on the other.
 */
class TestTimeoutEnvelopeTest {
    @Test
    void unsetPerTestTimeoutIsDerivedFromTheTaskTimeout() {
        CodiqoExtension ext = new CodiqoExtension();
        ext.setTestTimeoutMinutes(10);

        RunArgs timeouts = CodiqoGradlePlugin.resolveTimeouts(ext);

        assertEquals(Duration.ofMinutes(10), timeouts.getTestTimeout());
        assertEquals(Duration.ofMinutes(5), timeouts.getPerTestTimeout());
    }
    @Test
    void explicitPerTestTimeoutOverridesTheDerivation() {
        CodiqoExtension ext = new CodiqoExtension();
        ext.setTestTimeoutMinutes(30);
        ext.setPerTestTimeoutMinutes(2L);

        assertEquals(Duration.ofMinutes(2), CodiqoGradlePlugin.resolveTimeouts(ext).getPerTestTimeout());
    }
    @Test
    void perTestTimeoutIsCappedAtTheRunArgsCeiling() {
        CodiqoExtension ext = new CodiqoExtension();
        ext.setTestTimeoutMinutes(120);

        assertEquals(RunArgs.PER_TEST_TIMEOUT_MAX, CodiqoGradlePlugin.resolveTimeouts(ext).getPerTestTimeout());
    }
}
