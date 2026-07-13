package io.codiqo.maven.timemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TimeMachineConfigTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(TimeMachineConfig.PROP_FORWARD_WINDOW);
        System.clearProperty(TimeMachineConfig.PROP_TARGET_OFFSET);
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
    @Test
    void targetOffsetDefaultsToZero() {
        System.clearProperty(TimeMachineConfig.PROP_TARGET_OFFSET);
        assertEquals(Duration.ZERO, TimeMachineConfig.targetOffset());
    }
    @Test
    void targetOffsetReadsSystemProperty() {
        System.setProperty(TimeMachineConfig.PROP_TARGET_OFFSET, "PT15M");
        assertEquals(Duration.ofMinutes(15), TimeMachineConfig.targetOffset());
    }
}
