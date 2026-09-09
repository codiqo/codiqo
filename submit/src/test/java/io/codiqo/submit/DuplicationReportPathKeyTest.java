package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The duplication report keys clone locations by work-tree-relative path and matches those keys against
 * {@code FileChangeModel.path}, so the rendering has to be exact: {@code separatorsToUnix} replaces backslashes on
 * every platform, while a backslash is a legal filename character on POSIX.
 */
class DuplicationReportPathKeyTest {
    @TempDir
    Path workTree;

    @Test
    void aNestedPathIsRenderedWithUnixSeparators() throws Exception {
        Path file = write("src/main/java/io/codiqo/Foo.java");

        assertEquals("src/main/java/io/codiqo/Foo.java",
                DuplicationReportPopulator.relativePath(workTree.toRealPath(), file.toFile()));
    }
    @Test
    void aFileAtTheWorkTreeRootRendersAsItsBareName() throws Exception {
        Path file = write("Foo.java");

        assertEquals("Foo.java", DuplicationReportPopulator.relativePath(workTree.toRealPath(), file.toFile()));
    }
    /** the regression: a backslash here is part of the name, not a separator to normalise away */
    @Test
    void aBackslashInAFileNameSurvivesTheRendering() throws Exception {
        Path file = tryWrite("src", "od\\d.txt");
        assumeTrue(file != null, "the filesystem rejects a backslash in a file name");

        assertEquals("src/od\\d.txt", DuplicationReportPopulator.relativePath(workTree.toRealPath(), file.toFile()));
    }
    private Path write(String relative) throws IOException {
        Path file = workTree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x\n", StandardCharsets.UTF_8);
        return file;
    }
    private Path tryWrite(String directory, String fileName) throws IOException {
        Path dir = workTree.resolve(directory);
        Files.createDirectories(dir);
        try {
            Path file = dir.resolve(fileName);
            Files.writeString(file, "x\n", StandardCharsets.UTF_8);
            return file;
        } catch (IOException | java.nio.file.InvalidPathException err) {
            return null;
        }
    }
}
