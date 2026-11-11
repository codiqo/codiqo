package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisExcludeCategory;

/** the gates that need the delta, in the order both build tools rely on */
class CommitExclusionsAfterDeltaTest {
    private static final List<String> EXTENSIONS = List.of("java");

    /** a parent missing from a shallow clone lists no file, which the other gates would misread */
    @Test
    void aShallowBoundaryIsReportedAsIncompleteHistoryWhateverTheRulesSay() {
        RunArgs args = new RunArgs();
        args.setIncludeBranches("release/.*");

        assertEquals(AnalysisExcludeCategory.INCOMPLETE_HISTORY,
                CommitExclusions.afterDelta(args, new Delta(true, List.of("main")), EXTENSIONS, List.of()).orElseThrow().getCategory());
    }
    /** as the before-build gate reports the same commit, so the category does not depend on which gate caught it */
    @Test
    void aCommitWithNothingAnalysableIsReportedSoEvenWhenTheRulesFilterItToo() {
        RunArgs args = new RunArgs();
        args.setIncludeBranches("release/.*");

        assertEquals(AnalysisExcludeCategory.NO_ANALYZABLE_DIFF,
                CommitExclusions.afterDelta(args, new Delta(false, List.of("main")), EXTENSIONS, List.of("README.md")).orElseThrow().getCategory());
    }
    @Test
    void anAnalysableCommitOnABranchTheRulesLeaveOutIsFiltered() {
        RunArgs args = new RunArgs();
        args.setIncludeBranches("release/.*");

        assertEquals(AnalysisExcludeCategory.FILTERED_BY_RULES,
                CommitExclusions.afterDelta(args, new Delta(false, List.of("main")), EXTENSIONS, List.of("A.java")).orElseThrow().getCategory());
    }
    @Test
    void anAnalysableCommitTheRulesKeepIsAnalysed() {
        RunArgs args = new RunArgs();
        args.setIncludeBranches("release/.*");

        assertTrue(CommitExclusions.afterDelta(args, new Delta(false, List.of("release/1.0")), EXTENSIONS, List.of("pom.xml")).isEmpty());
    }

    private static final class Delta extends StubAnalysis {
        private final boolean historyIncomplete;
        private final List<String> branches;

        Delta(boolean historyIncomplete, List<String> branches) {
            this.historyIncomplete = historyIncomplete;
            this.branches = branches;
        }
        @Override
        public boolean isHistoryIncomplete() {
            return historyIncomplete;
        }
        @Override
        public List<String> getBranches() {
            return branches;
        }
    }
}
