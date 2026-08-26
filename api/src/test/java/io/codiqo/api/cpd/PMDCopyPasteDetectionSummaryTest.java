package io.codiqo.api.cpd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.DuplicateMark;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import net.sourceforge.pmd.cpd.Mark;

class PMDCopyPasteDetectionSummaryTest {
    @TempDir
    Path tempDir;

    private static final File FIRST = new File("First.java");
    private static final File SECOND = new File("Second.java");

    @Test
    void sourceLinesCountTheLastLineWithoutATrailingNewline() throws Exception {
        File withNewline = write("a.txt", "one\ntwo\n");
        File without = write("b.txt", "one\ntwo");
        File empty = write("c.txt", "");

        assertEquals(2, PMDCopyPasteDetectionSummary.countSourceLines(List.of(withNewline)));
        assertEquals(2, PMDCopyPasteDetectionSummary.countSourceLines(List.of(without)));
        assertEquals(0, PMDCopyPasteDetectionSummary.countSourceLines(List.of(empty)));
        assertEquals(4, PMDCopyPasteDetectionSummary.countSourceLines(List.of(withNewline, without)));
    }
    /** the paths come from a temporary clone the build has finished with, so a missing one must not fail a commit. */
    @Test
    void anUnreadableFileIsSkippedRatherThanFatal() throws Exception {
        File present = write("present.txt", "one\ntwo\n");
        File missing = new File(tempDir.toFile(), "gone.txt");

        assertEquals(2, PMDCopyPasteDetectionSummary.countSourceLines(List.of(present, missing)));
    }
    private File write(String name, String content) throws Exception {
        File toReturn = new File(tempDir.toFile(), name);
        Files.writeString(toReturn.toPath(), content);
        return toReturn;
    }
    @Test
    void noMatchesMeansNoDuplicatedLines() {
        assertEquals(0, PMDCopyPasteDetectionSummary.countDuplicatedLines(Set.of()));
    }
    @Test
    void aLineInsideTwoCloneGroupsCountsOnce() {
        int duplicated = PMDCopyPasteDetectionSummary.countDuplicatedLines(Set.of(
                new Group(new Copy(FIRST, 10, 20)),
                new Group(new Copy(FIRST, 15, 25))));

        assertEquals(16, duplicated,
                "lines 10-25 are duplicated; summing the two 11-line spans would double-count the overlap as 22");
    }
    @Test
    void everyMarkOfAGroupContributesItsOwnLines() {
        int duplicated = PMDCopyPasteDetectionSummary.countDuplicatedLines(Set.of(
                new Group(new Copy(FIRST, 1, 10), new Copy(SECOND, 1, 10))));

        assertEquals(20, duplicated,
                "both copies are duplicated code; a group's own lineCount reports one copy's length, so 10 undercounts");
    }
    private static class Copy implements DuplicateMark {
        private final File file;
        private final SourceLocation location;

        Copy(File file, int startLine, int endLine) {
            this.file = file;
            this.location = SourceLocation.builder().startLine(startLine).endLine(endLine).build();
        }
        @Override
        public File getFile() {
            return file;
        }
        @Override
        public SourceLocation getLocation() {
            return location;
        }
        @Override
        public Mark getMark() {
            throw new UnsupportedOperationException();
        }
        @Override
        public CharSequence getSourceCodeSlice() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Optional<CodeBlockInfo> block() {
            return Optional.empty();
        }
        @Override
        public void accept(CodeBlockInfo block) {
            throw new UnsupportedOperationException();
        }
    }
    private static class Group implements DuplicationMatch {
        private final List<DuplicateMark> marks;

        Group(DuplicateMark... marks) {
            this.marks = List.of(marks);
        }
        @Override
        public Iterator<DuplicateMark> iterator() {
            return marks.iterator();
        }
        @Override
        public int getTokenCount() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int getLineCount() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean isCrossFile() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Set<CodeBlockInfo> getBlocks() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Set<File> getFiles() {
            throw new UnsupportedOperationException();
        }
        @Override
        public void accept(CodeBlockInfo block) {
            throw new UnsupportedOperationException();
        }
        @Override
        public int compareTo(PmdDuplicationMatch other) {
            throw new UnsupportedOperationException();
        }
    }
}
