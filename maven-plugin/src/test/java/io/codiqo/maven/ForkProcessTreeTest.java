package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ForkProcessTreeTest {
    @Test
    void stopsOnlyTheProcessesStartedAfterTheSnapshot() throws IOException, InterruptedException {
        Process host = new ProcessBuilder("sleep", "300").start();
        try {
            Set<Long> before = ForkProcessTree.snapshot();
            Process forked = new ProcessBuilder("sleep", "300").start();

            assertEquals(1, ForkProcessTree.stop(before, Duration.ofSeconds(10)));
            assertFalse(forked.isAlive());
            assertTrue(host.isAlive());
        } finally {
            host.destroyForcibly();
        }
    }
}
