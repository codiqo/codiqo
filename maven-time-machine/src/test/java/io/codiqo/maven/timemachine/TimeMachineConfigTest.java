package io.codiqo.maven.timemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TimeMachineConfigTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(TimeMachineConfig.PROP_FORWARD_WINDOW);
    }

    @Test
    void forwardWindowDefaultsToOneDay() {
        System.clearProperty(TimeMachineConfig.PROP_FORWARD_WINDOW);
        assertEquals(Duration.ofDays(1), TimeMachineConfig.forwardWindow());
    }
    @Test
    void forwardWindowReadsSystemProperty() {
        System.setProperty(TimeMachineConfig.PROP_FORWARD_WINDOW, "PT12H");
        assertEquals(Duration.ofHours(12), TimeMachineConfig.forwardWindow());
    }
}
