package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;


import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.cpd.DuplicationMatch;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.api.metrics.DriverScore;

class MetricsAggregatorTest {
    @Test
    void cpdPercentIsEmptyOnlyWhenTheDetectorNeverRan() {
        assertTrue(MetricsAggregator.cpdDuplicationPercent(List.of()).isEmpty(),
                "no summary at all is ignoreCpd or an unsupported language — unknown, and it must not publish a confident 0%");
        assertTrue(MetricsAggregator.cpdDuplicationPercent(List.of(new ScannedCode(0, 0))).isEmpty(),
                "a run that read nothing measured nothing");

        assertEquals(0.0, MetricsAggregator.cpdDuplicationPercent(List.of(new ScannedCode(0, 53_000))).orElseThrow(),
                "a run that read code and found no clones is a measured zero, not unknown");
    }
    /** the numbers are ebean's 1545e68c3e (42 duplicated lines) and c275953582 (133), over its ~53k-line reactor. */
    @Test
    void cpdPercentIsAShareOfEverythingScannedNotOfTheCommit() {
        assertEquals(0.079, MetricsAggregator.cpdDuplicationPercent(List.of(new ScannedCode(42, 53_000))).orElseThrow(), 0.001);
        assertEquals(0.251, MetricsAggregator.cpdDuplicationPercent(List.of(new ScannedCode(133, 53_000))).orElseThrow(), 0.001);
    }
    @Test
    void cpdPercentPoolsEveryLanguageSummary() {
        OptionalDouble pooled = MetricsAggregator.cpdDuplicationPercent(
                List.of(new ScannedCode(30, 40_000), new ScannedCode(12, 13_000)));

        assertEquals(0.079, pooled.orElseThrow(), 0.001,
                "one summary per language: the ratio is of the pooled totals, not the mean of the per-language ratios (0.084)");
    }
    @Test
    void cpdPercentIsClampedAtOneHundred() {
        assertEquals(100.0, MetricsAggregator.cpdDuplicationPercent(List.of(new ScannedCode(900, 100))).orElseThrow());
    }
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
        List<DriverScaler.Sample> samples = new ArrayList<>();
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
        List<DriverScaler.Sample> prod = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            prod.add(new DriverScaler.Sample(i, i, i));
        }
        List<DriverScaler.Sample> test = new ArrayList<>();
        for (int i = 20; i <= 70; i++) {
            test.add(new DriverScaler.Sample(i, i, i));
        }
        DriverScaler prodScaler = DriverScaler.of(prod);
        DriverScaler testScaler = DriverScaler.of(test);

        int prodP90 = MetricsAggregator.computeDriverQuantile(prod, prodScaler, 90.0);
        int testP90 = MetricsAggregator.computeDriverQuantile(test, testScaler, 90.0);

        List<DriverScaler.Sample> pooled = new ArrayList<>();
        pooled.addAll(prod);
        pooled.addAll(test);
        DriverScaler pooledScaler = DriverScaler.of(pooled);
        int pooledP90 = MetricsAggregator.computeDriverQuantile(pooled, pooledScaler, 90.0);

        assertTrue(prodP90 < pooledP90,
                "prod-only P90 must be tighter than pooled P90; pooled is inflated by test-code outliers (the whole point of the split)");
        assertTrue(testP90 > 0);
    }

    private static class ScannedCode implements CopyPasteDetectionSummary {
        private final int duplicatedLines;
        private final int scannedLines;

        ScannedCode(int duplicatedLines, int scannedLines) {
            this.duplicatedLines = duplicatedLines;
            this.scannedLines = scannedLines;
        }
        @Override
        public int duplicatedLines() {
            return duplicatedLines;
        }
        @Override
        public int scannedLines() {
            return scannedLines;
        }
        @Override
        public Map<File, Integer> tokensPerFile() {
            return Collections.emptyMap();
        }
        @Override
        public Set<DuplicationMatch> affected() {
            return Collections.emptySet();
        }
        @Override
        public Map<CodeBlockInfo, Set<CodeBlockInfo>> copyPasteFrom() {
            return Collections.emptyMap();
        }
        @Override
        public Set<Set<CodeBlockInfo>> copyPasteNew() {
            return Collections.emptySet();
        }
    }
}
