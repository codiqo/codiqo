package io.codiqo.api.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DriverScalerTest {
    @Test
    void shouldBuildStatsAndExposeP50BasedFactors() {
        DriverScaler scaler = DriverScaler.of(List.of(
                new DriverScaler.Sample(10, 20, 5),
                new DriverScaler.Sample(20, 30, 15),
                new DriverScaler.Sample(30, 40, 25),
                new DriverScaler.Sample(40, 50, 35)));

        assertEquals(4, scaler.population());
        assertEquals(10, scaler.lines().min());
        assertEquals(40, scaler.lines().max());
        assertEquals(20, scaler.ncss().min());
        assertEquals(50, scaler.ncss().max());
        assertEquals(5, scaler.invocations().min());
        assertEquals(35, scaler.invocations().max());
        assertTrue(scaler.lines().p50() >= scaler.lines().min());
        assertTrue(scaler.lines().p50() <= scaler.lines().p75());
        assertTrue(scaler.lines().p75() <= scaler.lines().p90());
        assertTrue(scaler.lines().p90() <= scaler.lines().p95());
        assertTrue(scaler.lines().p95() <= scaler.lines().max());

        double ncssFactor = (double) scaler.lines().p50() / scaler.ncss().p50();
        double invocsFactor = (double) scaler.lines().p50() / scaler.invocations().p50();
        assertEquals(ncssFactor, scaler.ncssFactor(), 0.0001);
        assertEquals(invocsFactor, scaler.invocationsFactor(), 0.0001);
    }

    @Test
    void shouldReturnUnitFactorsWhenP50IsZero() {
        DriverScaler scaler = DriverScaler.of(List.of(
                new DriverScaler.Sample(5, 0, 0),
                new DriverScaler.Sample(10, 0, 0)));

        assertEquals(1.0, scaler.ncssFactor(), 0.0001);
        assertEquals(1.0, scaler.invocationsFactor(), 0.0001);
        assertTrue(!scaler.isEmpty());
    }
}
