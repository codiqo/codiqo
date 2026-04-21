package io.codiqo.api.metrics;

import java.util.Collection;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class DriverScaler {
    public static final DriverScaler EMPTY = new DriverScaler(0, DimensionStats.ZERO, DimensionStats.ZERO, DimensionStats.ZERO);

    private final int population;
    private final DimensionStats lines;
    private final DimensionStats ncss;
    private final DimensionStats invocations;

    public double ncssFactor() {
        if (ncss.p50() <= 0 || lines.p50() <= 0) {
            return 1.0;
        }
        return (double) lines.p50() / ncss.p50();
    }
    public double invocationsFactor() {
        if (invocations.p50() <= 0 || lines.p50() <= 0) {
            return 1.0;
        }
        return (double) lines.p50() / invocations.p50();
    }
    public boolean isEmpty() {
        return population == 0;
    }
    public static DriverScaler fromPersisted(int population, DimensionStats lines, DimensionStats ncss, DimensionStats invocations) {
        return new DriverScaler(
                population,
                lines,
                ncss,
                invocations);
    }
    public static DriverScaler of(Collection<Sample> samples) {
        if (CollectionUtils.isEmpty(samples)) {
            return EMPTY;
        }

        int population = samples.size();
        double[] linesValues = new double[population];
        double[] ncssValues = new double[population];
        double[] invocationsValues = new double[population];

        int i = 0;
        for (Sample sample : samples) {
            linesValues[i] = sample.lines();
            ncssValues[i] = sample.ncss();
            invocationsValues[i] = sample.invocations();
            i++;
        }

        return new DriverScaler(
                population,
                DimensionStats.of(linesValues),
                DimensionStats.of(ncssValues),
                DimensionStats.of(invocationsValues));
    }

    @Getter
    @Accessors(fluent = true)
    @Builder
    public static final class DimensionStats {
        public static final DimensionStats ZERO = new DimensionStats(0, 0, 0, 0, 0, 0);

        private final int min;
        private final int p50;
        private final int p75;
        private final int p90;
        private final int p95;
        private final int max;

        public DimensionStats(int min, int p50, int p75, int p90, int p95, int max) {
            this.min = min;
            this.p50 = p50;
            this.p75 = p75;
            this.p90 = p90;
            this.p95 = p95;
            this.max = max;
        }
        public static DimensionStats of(double[] values) {
            DescriptiveStatistics statistics = new DescriptiveStatistics(values);
            return new DimensionStats(
                    (int) statistics.getMin(),
                    (int) statistics.getPercentile(50.0),
                    (int) statistics.getPercentile(75.0),
                    (int) statistics.getPercentile(90.0),
                    (int) statistics.getPercentile(95.0),
                    (int) statistics.getMax());
        }
    }

    @Value
    @Accessors(fluent = true)
    public static final class Sample {
        int lines;
        int ncss;
        int invocations;
    }
}
