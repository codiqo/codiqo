package io.codiqo.submit.outcome;

import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;

import io.codiqo.api.testing.TestRun;
import io.codiqo.client.model.AnalysisExcludeCategory;
import lombok.Value;

/**
 * How one step of a commit's build ended. A failure is the author's, the environment's (a dependency that could not be
 * resolved, a plugin download that failed) or a timeout; a failure on Codiqo's side, a compiler crash or a module whose
 * tests ran without the coverage agent, is no verdict on the commit: the build throws it, and the run halts.
 */
public sealed interface BuildVerdict permits BuildVerdict.Passed, BuildVerdict.Failed {
    static BuildVerdict passed() {
        return new Passed(Optional.empty());
    }
    static BuildVerdict tested(TestRun testRun) {
        return new Passed(Optional.of(testRun));
    }

    /** the test run the step produced, read only when the run measures coverage */
    @Value
    final class Passed implements BuildVerdict {
        Optional<TestRun> testRun;
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
