package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.code.CompilerFinding;

class ErrorProneWarningsTest {
    @TempDir
    Path workTree;

    @TempDir
    Path elsewhere;

    @Test
    void readsAnErrorProneWarningAsAFindingOnTheWorkTreeRelativePath() throws IOException {
        Path test = source(workTree, "core/src/test/java/org/example/ServiceTest.java");
        String line = "[WARNING] " + test + ":[21,27] [FormatString] extra format arguments: used 0, provided 2";

        assertTrue(ErrorProneWarnings.isWarning(line));
        assertEquals(
                List.of(new CompilerFinding("errorprone", "FormatString", "core/src/test/java/org/example/ServiceTest.java", 21, "extra format arguments: used 0, provided 2")),
                ErrorProneWarnings.findings(List.of(line), workTree.toFile()));
    }
    @Test
    void javacLintWarningsAndOtherOutputAreNotErrorProneFindings() {
        assertFalse(ErrorProneWarnings.isWarning("[WARNING] /w/Foo.java:[3,9] [deprecation] old() in Bar has been deprecated"));
        assertFalse(ErrorProneWarnings.isWarning("[WARNING] /w/Foo.java:[3,9] [removal] Thread.stop() has been deprecated and marked for removal"));
        assertFalse(ErrorProneWarnings.isWarning("[WARNING] /w/Foo.java:[3,9] unchecked conversion"));
        assertFalse(ErrorProneWarnings.isWarning("    (see https://errorprone.info/bugpattern/FormatString)"));
        assertFalse(ErrorProneWarnings.isWarning("[ERROR] /w/Foo.java:[3,9] [FormatString] extra format arguments: used 0, provided 2"));
        assertFalse(ErrorProneWarnings.isWarning("[WARNING] Using platform encoding (UTF-8 actually) to copy filtered resources"));
    }
    @Test
    void aWarningRepeatedInTheOutputIsOneFinding() throws IOException {
        Path file = source(workTree, "Service.java");
        String line = "[WARNING] " + file + ":[5,9] [UnusedVariable] The local variable 'count' is never read.";

        assertEquals(1, ErrorProneWarnings.findings(List.of(line, line), workTree.toFile()).size());
    }
    /** a generated or unpacked source outside the clone cannot be a file the commit changed */
    @Test
    void aFileOutsideTheWorkTreeOrNoLongerThereIsDropped() throws IOException {
        Path outside = source(elsewhere, "Generated.java");

        assertEquals(List.of(), ErrorProneWarnings.findings(List.of(
                "[WARNING] " + outside + ":[5,9] [UnusedVariable] The local variable 'count' is never read.",
                "[WARNING] " + workTree.resolve("Gone.java") + ":[5,9] [UnusedVariable] The local variable 'count' is never read."),
                workTree.toFile()));
    }
    private static Path source(Path root, String relative) throws IOException {
        Path toReturn = root.resolve(relative);
        Files.createDirectories(toReturn.getParent());
        Files.writeString(toReturn, "class Service {}");
        return toReturn;
    }
}
