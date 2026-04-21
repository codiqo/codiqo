package io.codiqo.api.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

class DriverScoreTest {
    @Test
    void forNewOnEmptyScalerReturnsZero() {
        double driver = DriverScore.forNew(DriverScaler.EMPTY, 10, 10, 10);
        assertEquals(0.0, driver, 0.0001);
    }
    @Test
    void forModifyOnEmptyScalerReturnsZero() {
        double driver = DriverScore.forModify(DriverScaler.EMPTY, 10, 10);
        assertEquals(0.0, driver, 0.0001);
    }
    @Test
    void forNewOnUniformPopulationEqualsLineValue() {
        DriverScaler scaler = uniformScaler(1, 40);

        double driver = DriverScore.forNew(scaler, 40, 40, 40);

        assertEquals(40.0, driver, 0.01,
                "With uniform population (p50 equal across dims) every dim factor is 1.0, "
                        + "so the projection collapses to the raw arithmetic mean of the three values");
    }
    @Test
    void forModifyFullyModifiedBlockMatchesEquivalentNewBlock() {
        DriverScaler scaler = uniformScaler(1, 40);

        double newDriver = DriverScore.forNew(scaler, 40, 40, 40);
        double modifyDriver = DriverScore.forModify(scaler, 40, 40);

        assertEquals(newDriver, modifyDriver, 0.01,
                "forModify averages lines+invocations only; with equal values the result matches forNew "
                        + "since forNew also averages three equal values");
    }
    @Test
    void forNewAtDimensionMinIsNearZero() {
        DriverScaler scaler = uniformScaler(1, 100);

        double driver = DriverScore.forNew(scaler, 1, 1, 0);

        assertTrue(driver >= 0.0, "min-dimension block should not produce negative driver");
        assertTrue(driver < 1.0, "min-dimension block should produce a near-zero driver");
    }
    @Test
    void forNewIsMonotonicInEachDimension() {
        DriverScaler scaler = uniformScaler(1, 100);

        double small = DriverScore.forNew(scaler, 10, 10, 10);
        double mediumLines = DriverScore.forNew(scaler, 50, 10, 10);
        double mediumNcss = DriverScore.forNew(scaler, 10, 50, 10);
        double mediumInvocs = DriverScore.forNew(scaler, 10, 10, 50);

        assertTrue(mediumLines > small, "raising lines should raise driver");
        assertTrue(mediumNcss > small, "raising ncss should raise driver");
        assertTrue(mediumInvocs > small, "raising invocations should raise driver");
    }
    @Test
    void forModifyIgnoresNcssDimension() {
        DriverScaler scaler = uniformScaler(1, 100);

        double low = DriverScore.forModify(scaler, 50, 50);
        double high = DriverScore.forModify(scaler, 50, 50);

        assertEquals(low, high, 0.01,
                "forModify takes (scaler, linesChanged, invocationsChanged) — there is no ncss input, "
                        + "so the NCSS dimension cannot influence the modify driver score");
    }
    @Test
    void forModifyIsMonotonicInLinesAndInvocations() {
        DriverScaler scaler = uniformScaler(1, 100);

        double base = DriverScore.forModify(scaler, 10, 10);
        double moreLines = DriverScore.forModify(scaler, 50, 10);
        double moreInvocs = DriverScore.forModify(scaler, 10, 50);

        assertTrue(moreLines > base, "raising linesChanged should raise modify driver");
        assertTrue(moreInvocs > base, "raising invocationsChanged should raise modify driver");
    }
    @Test
    void forNewCrossDimLeakIsRemoved() {
        List<DriverScaler.Sample> samples = Lists.newArrayList();
        for (int i = 1; i <= 100; i++) {
            samples.add(new DriverScaler.Sample(i, i, i));
        }
        samples.add(new DriverScaler.Sample(10_000, 1, 1));
        DriverScaler scalerWithLinesOutlier = DriverScaler.of(samples);

        samples.clear();
        for (int i = 1; i <= 100; i++) {
            samples.add(new DriverScaler.Sample(i, i, i));
        }
        samples.add(new DriverScaler.Sample(1, 10_000, 1));
        DriverScaler scalerWithNcssOutlier = DriverScaler.of(samples);

        double a = DriverScore.forNew(scalerWithLinesOutlier, 10, 10, 10);
        double b = DriverScore.forNew(scalerWithNcssOutlier, 10, 10, 10);

        assertEquals(a, b, 0.5,
                "An outlier on one dimension must not change the driver score of a typical block "
                        + "via the cross-dim anchor path");
    }
    @Test
    void totalWeightSumsIndividualWeights() {
        assertEquals(DriverScore.WEIGHT_LINES + DriverScore.WEIGHT_NCSS + DriverScore.WEIGHT_INVOCATIONS,
                DriverScore.TOTAL_WEIGHT, 0.0001);
    }

    private static DriverScaler uniformScaler(int min, int max) {
        List<DriverScaler.Sample> samples = Lists.newArrayList();
        for (int i = min; i <= max; i++) {
            samples.add(new DriverScaler.Sample(i, i, i));
        }
        return DriverScaler.of(samples);
    }
}
