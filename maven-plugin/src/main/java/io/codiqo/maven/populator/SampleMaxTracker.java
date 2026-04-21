package io.codiqo.maven.populator;

import io.codiqo.api.metrics.DriverScaler;
import lombok.Getter;
import lombok.Value;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class SampleMaxTracker {
    private MaxHolder lines = MaxHolder.EMPTY;
    private MaxHolder ncss = MaxHolder.EMPTY;
    private MaxHolder invocations = MaxHolder.EMPTY;

    void update(String file, String block, DriverScaler.Sample sample) {
        if (sample.lines() > lines.value()) {
            lines = new MaxHolder(file, block, sample.lines());
        }
        if (sample.ncss() > ncss.value()) {
            ncss = new MaxHolder(file, block, sample.ncss());
        }
        if (sample.invocations() > invocations.value()) {
            invocations = new MaxHolder(file, block, sample.invocations());
        }
    }
    void mergeFrom(SampleMaxTracker other) {
        if (other.lines.value() > lines.value()) {
            lines = other.lines;
        }
        if (other.ncss.value() > ncss.value()) {
            ncss = other.ncss;
        }
        if (other.invocations.value() > invocations.value()) {
            invocations = other.invocations;
        }
    }

    @Value
    @Accessors(fluent = true)
    public static class MaxHolder {
        public static final MaxHolder EMPTY = new MaxHolder("-", "-", -1);

        String file;
        String block;
        int value;
    }
}
