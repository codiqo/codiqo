package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.client.model.AnalysisExcludeCategory;

/** the gates that need the delta, in the order both build tools rely on */
class CommitExclusionsAfterDeltaTest {
    private static final List<String> EXTENSIONS = List.of("java");

    /** a parent missing from a shallow clone lists no file, which the analysable-file gate would misread */
    @Test
    void aShallowBoundaryIsReportedAsIncompleteHistory() {
        assertEquals(AnalysisExcludeCategory.INCOMPLETE_HISTORY,
                CommitExclusions.afterDelta(new Delta(true, List.of()), EXTENSIONS).orElseThrow().getCategory());
    }
    @Test
    void aCommitWithNothingAnalysableIsExcluded() {
        assertEquals(AnalysisExcludeCategory.NO_ANALYZABLE_DIFF,
                CommitExclusions.afterDelta(new Delta(false, List.of("README.md")), EXTENSIONS).orElseThrow().getCategory());
    }
    @Test
    void aCommitChangingSourceOrABuildFileIsAnalysed() {
        assertTrue(CommitExclusions.afterDelta(new Delta(false, List.of("README.md", "A.java")), EXTENSIONS).isEmpty());
        assertTrue(CommitExclusions.afterDelta(new Delta(false, List.of("pom.xml")), EXTENSIONS).isEmpty());
    }

    private static final class Delta extends StubAnalysis {
        private final boolean historyIncomplete;
        private final List<FileAnalysis> files;

        Delta(boolean historyIncomplete, List<String> changedFiles) {
            this.historyIncomplete = historyIncomplete;
            this.files = changedFiles.stream().map(Delta::changed).toList();
        }
        @Override
        public boolean isHistoryIncomplete() {
            return historyIncomplete;
        }
        @Override
        public Iterator<FileAnalysis> iterator() {
            return files.iterator();
        }
        private static FileAnalysis changed(String name) {
            FileAnalysis toReturn = mock(FileAnalysis.class);
            when(toReturn.getFile()).thenReturn(new File("src", name));
            return toReturn;
        }
    }
}
