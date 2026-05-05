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
    void shouldReturnZeroFactorWhenDimensionMedianIsZero() {
        DriverScaler scaler = DriverScaler.of(List.of(
                new DriverScaler.Sample(5, 0, 0),
                new DriverScaler.Sample(10, 0, 0)));

        assertEquals(0.0, scaler.ncssFactor(), 0.0001,
                "no signal in NCSS dimension (p50=0) → factor 0.0 (drop dimension), not silent 1.0 fallback");
        assertEquals(0.0, scaler.invocationsFactor(), 0.0001,
                "no signal in invocations dimension (p50=0) → factor 0.0");
        assertTrue(!scaler.isEmpty(), "scaler with non-zero population is not empty even when projection dimensions are degenerate");
    }

    @Test
    void shouldReturnEmptyWhenLinesMedianIsZero() {
        DriverScaler scaler = DriverScaler.of(List.of(
                new DriverScaler.Sample(0, 5, 5),
                new DriverScaler.Sample(0, 10, 10)));

        assertTrue(scaler.isEmpty(),
                "lines.p50 == 0 makes the scaler degenerate — DriverScore.forNew/forModify will short-circuit to 0.0");
    }

    @Test
    void factorsAreRawRatiosOfMediansWithoutClamping() {
        DriverScaler scaler = DriverScaler.of(List.of(
                new DriverScaler.Sample(30, 1, 1),
                new DriverScaler.Sample(30, 1, 1),
                new DriverScaler.Sample(30, 1, 1)));

        assertEquals(30.0, scaler.ncssFactor(), 0.0001,
                "factors are raw lines.p50 / dim.p50 — no bucket-level clamp. Per-block deviation cap lives elsewhere.");
        assertEquals(30.0, scaler.invocationsFactor(), 0.0001);
    }

    @Test
    void factorsCanBeBelowOneWhenNcssExceedsLines() {
        DriverScaler scaler = DriverScaler.of(List.of(
                new DriverScaler.Sample(1, 30, 30),
                new DriverScaler.Sample(1, 30, 30),
                new DriverScaler.Sample(1, 30, 30)));

        assertEquals(1.0 / 30.0, scaler.ncssFactor(), 0.0001,
                "factors below 1.0 reflect projects where NCSS density exceeds line density");
        assertEquals(1.0 / 30.0, scaler.invocationsFactor(), 0.0001);
    }

    @Test
    void fromPersistedReproducesFactorsFromOf() {
        DriverScaler.DimensionStats lines = new DriverScaler.DimensionStats(1, 30, 30, 30, 30, 30);
        DriverScaler.DimensionStats ncss = new DriverScaler.DimensionStats(1, 1, 1, 1, 1, 1);
        DriverScaler.DimensionStats invocs = new DriverScaler.DimensionStats(1, 1, 1, 1, 1, 1);

        DriverScaler scaler = DriverScaler.fromPersisted(3, lines, ncss, invocs);

        assertEquals(30.0, scaler.ncssFactor(), 0.0001,
                "fromPersisted reproduces the same factors as of() — both use the raw median ratio");
        assertEquals(30.0, scaler.invocationsFactor(), 0.0001);
    }
}
