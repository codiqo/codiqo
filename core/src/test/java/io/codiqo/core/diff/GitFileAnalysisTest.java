package io.codiqo.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.File;

import org.eclipse.jgit.diff.DiffEntry;
import org.junit.jupiter.api.Test;

class GitFileAnalysisTest {
    @Test
    void twoAnalysesOfTheSameChangeAreEqual() {
        assertEquals(analysisOf("Foo.java"), analysisOf("Foo.java"));
        assertEquals(analysisOf("Foo.java").hashCode(), analysisOf("Foo.java").hashCode());
        assertNotEquals(analysisOf("Foo.java"), analysisOf("Bar.java"));
    }
    /**
     * Object.equals answers false for null and for a value of another type. A collection that holds an analysis beside
     * another kind of value relies on it: a cast without a type check throws there instead of answering.
     */
    @Test
    void anAnalysisIsNeverEqualToNullOrAnotherType() {
        assertNotEquals(analysisOf("Foo.java"), (Object) null);
        assertNotEquals(analysisOf("Foo.java"), "Foo.java");
    }
    private static GitFileAnalysis analysisOf(String path) {
        GitFileAnalysis toReturn = new GitFileAnalysis();
        toReturn.setFile(new File(path));
        toReturn.setChangeType(DiffEntry.ChangeType.MODIFY);
        toReturn.setDiffText("@@ -1 +1 @@");
        return toReturn;
    }
}
