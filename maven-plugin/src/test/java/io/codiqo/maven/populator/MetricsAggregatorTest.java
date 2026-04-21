package io.codiqo.maven.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.api.metrics.DriverScore;

class MetricsAggregatorTest {
    @Test
    void computeDriverQuantileReturnsZeroForEmptyPopulation() {
        DriverScaler scaler = DriverScaler.EMPTY;

        assertEquals(0, MetricsAggregator.computeDriverQuantile(List.of(), scaler, 90.0));
    }
    @Test
    void computeDriverQuantileReturnsDriverScoreForSingleSample() {
        DriverScaler.Sample sample = new DriverScaler.Sample(10, 20, 5);
        DriverScaler scaler = DriverScaler.of(List.of(sample));

        int quantile = MetricsAggregator.computeDriverQuantile(List.of(sample), scaler, 90.0);

        double expected = DriverScore.forNew(scaler, sample.lines(), sample.ncss(), sample.invocations());
        assertEquals((int) expected, quantile);
    }
    @Test
    void computeDriverQuantileMatchesP90OfDriverScoreDistribution() {
        List<DriverScaler.Sample> samples = Lists.newArrayList();
        for (int i = 1; i <= 100; i++) {
            samples.add(new DriverScaler.Sample(i, i, i));
        }
        DriverScaler scaler = DriverScaler.of(samples);

        int p90 = MetricsAggregator.computeDriverQuantile(samples, scaler, 90.0);
        int p50 = MetricsAggregator.computeDriverQuantile(samples, scaler, 50.0);

        assertTrue(p90 > p50, "P90 must exceed P50 on a uniformly growing population");
        assertTrue(p90 <= DriverScore.forNew(scaler, 100, 100, 100), "P90 cannot exceed the max");
        assertTrue(p90 >= DriverScore.forNew(scaler, 85, 85, 85), "P90 must be at least the 85th sample's driver");
    }
    @Test
    void prodOnlyAndTestOnlyPopulationsYieldDifferentQuantilesFixingThePoolingBias() {
        List<DriverScaler.Sample> prod = Lists.newArrayList();
        for (int i = 1; i <= 50; i++) {
            prod.add(new DriverScaler.Sample(i, i, i));
        }
        List<DriverScaler.Sample> test = Lists.newArrayList();
        for (int i = 20; i <= 70; i++) {
            test.add(new DriverScaler.Sample(i, i, i));
        }
        DriverScaler prodScaler = DriverScaler.of(prod);
        DriverScaler testScaler = DriverScaler.of(test);

        int prodP90 = MetricsAggregator.computeDriverQuantile(prod, prodScaler, 90.0);
        int testP90 = MetricsAggregator.computeDriverQuantile(test, testScaler, 90.0);

        List<DriverScaler.Sample> pooled = Lists.newArrayList();
        pooled.addAll(prod);
        pooled.addAll(test);
        DriverScaler pooledScaler = DriverScaler.of(pooled);
        int pooledP90 = MetricsAggregator.computeDriverQuantile(pooled, pooledScaler, 90.0);

        assertTrue(prodP90 < pooledP90,
                "prod-only P90 must be tighter than pooled P90; pooled is inflated by test-code outliers (the whole point of the split)");
        assertTrue(testP90 > 0);
    }
}
