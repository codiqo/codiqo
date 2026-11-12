package io.codiqo.submit.outcome;

import java.time.Duration;
import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import io.codiqo.api.testing.TestRun;
import io.codiqo.client.model.AnalysisExcludeCategory;
import lombok.Value;

/**
 * How one step of a commit's build ended. A failure is the author's, the environment's (a dependency that could not be
 * resolved, a plugin download that failed) or a timeout; a failure on Codiqo's side, a compiler crash or a module whose
 * tests ran without the coverage agent, is no verdict on the commit: the build throws it, and the run halts.
 */
public sealed interface BuildVerdict permits BuildVerdict.Passed, BuildVerdict.Failed {
    static BuildVerdict passed(Optional<Duration> took) {
        return new Passed(Optional.empty(), took);
    }
    static BuildVerdict tested(TestRun testRun, Optional<Duration> took) {
        return new Passed(Optional.of(testRun), took);
    }

    /**
     * The test run the step produced, read only when the run measures coverage, and how long the step's build ran when
     * the build tool timed it: Gradle built the commit before the analysis started, so it times nothing.
     */
    final class Passed extends ImmutablePair<Optional<TestRun>, Optional<Duration>> implements BuildVerdict {
        private static final long serialVersionUID = 1L;

        private Passed(Optional<TestRun> testRun, Optional<Duration> took) {
            super(testRun, took);
        }
        public Optional<TestRun> getTestRun() {
            return getLeft();
        }
        public Optional<Duration> getTook() {
            return getRight();
        }
    }

    /** the reason, category and detail are what the commit's exclusion, or its degraded submission, records */
    @Value
    final class Failed implements BuildVerdict {
        String reason;
        AnalysisExcludeCategory category;
        String detail;
        boolean timedOut;

        /**
         * A build the author broke, which a review scores as a degraded commit. An unresolvable dependency or a build
         * that ran out of time is the environment's, and scoring it would alert on a failure nobody made.
         */
        public boolean isAuthors() {
            return BooleanUtils.and(new boolean[] { AnalysisExcludeCategory.BUILD_FAILURE == category, BooleanUtils.negate(timedOut) });
        }
    }
}
