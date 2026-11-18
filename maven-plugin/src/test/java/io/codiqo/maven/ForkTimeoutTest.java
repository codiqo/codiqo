package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ForkTimeoutTest {
    @Test
    void aTestRunWithTimeToSpareKeepsTheTestTimeout() {
        assertEquals(Duration.ofMinutes(30).getSeconds(), AbstractAnalyzeMojo.forkTimeoutSeconds(Duration.ofMinutes(30), Duration.ofMinutes(45)));
    }
    /** a test run started late: surefire must end a hung test JVM before the build's deadline kills the whole build */
    @Test
    void aTestRunStartedLateEndsItsTestJvmBeforeTheDeadline() {
        assertEquals(Duration.ofMinutes(17).getSeconds(), AbstractAnalyzeMojo.forkTimeoutSeconds(Duration.ofMinutes(30), Duration.ofMinutes(20)));
    }
    @Test
    void aTestRunWithAlmostNoTimeLeftStillGetsAMinute() {
        assertEquals(60L, AbstractAnalyzeMojo.forkTimeoutSeconds(Duration.ofMinutes(30), Duration.ofMinutes(2)));
    }
}
