package io.codiqo.api.testing;

import java.time.Duration;
import java.util.List;

import com.google.common.collect.Lists;

import lombok.Builder;
import lombok.Value;

/**
 * The commit's test run in the analysis build, summed over every JUnit XML report the build tool wrote. A report is
 * written by any JUnit-reporting runner, so the same reading serves Maven's surefire and failsafe and Gradle's test task.
 */
@Value
@Builder
public class TestRun {
    int tests;
    int failures;
    int errors;
    int skipped;
    /** the sum of the suites' own times, which leaves out JVM start-up and the build's other work */
    Duration testTime;
    /** the skipped tests as class#method, in report order, at most {@link TestRuns#MAX_SKIPPED_TESTS} */
    @Builder.Default
    List<String> skippedTests = Lists.newArrayList();

    /** the share of executed tests that failed or errored; 0 when nothing executed */
    public double failedShare() {
        int executed = tests - skipped;
        return executed > 0 ? (double) (failures + errors) / executed : 0.0;
    }
}
