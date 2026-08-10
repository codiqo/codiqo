package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSpecContainsTest {
    /**
     * the shape that lost every caller's test/production classification on macOS: the language server
     * reports a caller through the resolved path while the build tool reports its base directory as it was
     * given, so a symlinked work tree put the two on different spellings of the same directory and no
     * module owned any file
     */
    @Test
    void fileUnderARealPathIsOwnedByAModuleRootedAtASymlinkToIt(@TempDir Path temp) throws IOException {
        Path real = Files.createDirectories(temp.resolve("real/module"));
        Path source = Files.createFile(Files.createDirectories(real.resolve("src/test/java")).resolve("FooTest.java"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), real);

        assertTrue(spec(link.toFile()).contains(source.toFile()),
                "a module addressed through a symlink must still own the files under its real path");
        assertTrue(spec(real.toFile()).contains(link.resolve("src/test/java/FooTest.java").toFile()),
                "and a file addressed through the symlink must still be owned by the real module root");
    }
    /**
     * the half of the bug the first fix missed: ownership resolved, but the test source root was still
     * compared with normalize(), so every caller came back production anyway
     */
    @Test
    void testSourceRootMembershipSurvivesTheSameSymlink(@TempDir Path temp) throws IOException {
        Path real = Files.createDirectories(temp.resolve("real/module"));
        Path testRoot = Files.createDirectories(real.resolve("src/test/java"));
        Path source = Files.createFile(testRoot.resolve("FooTest.java"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), real);

        assertTrue(PathContainment.isUnder(link.resolve("src/test/java").toFile(), source.toFile()),
                "a test source root addressed through a symlink must still contain the files under its real path");
        assertTrue(PathContainment.isUnder(testRoot.toFile(), link.resolve("src/test/java/FooTest.java").toFile()),
                "and a file addressed through the symlink must still fall inside the real test root");
        assertFalse(PathContainment.isUnder(real.resolve("src/main/java").toFile(), source.toFile()),
                "a file outside the root must still be outside it");
    }
    @Test
    void fileOutsideTheModuleIsNotOwned(@TempDir Path temp) throws IOException {
        Path module = Files.createDirectories(temp.resolve("module"));
        Path outside = Files.createFile(Files.createDirectories(temp.resolve("elsewhere")).resolve("Bar.java"));

        assertFalse(spec(module.toFile()).contains(outside.toFile()));
    }
    /**
     * a path that cannot be resolved is a file no longer on disk — it must still compare rather than throw,
     * because containment is asked about deleted files during a diff
     */
    @Test
    void missingFileFallsBackToANormalizedComparison(@TempDir Path temp) throws IOException {
        Path module = Files.createDirectories(temp.resolve("module"));

        assertTrue(spec(module.toFile()).contains(module.resolve("gone/Removed.java").toFile()));
        assertFalse(spec(module.toFile()).contains(temp.resolve("other/Removed.java").toFile()));
    }
    private static ProjectSpec spec(File baseDirectory) {
        return new ProjectSpec() {
            @Override
            public File getBaseDirectory() {
                return baseDirectory;
            }
            @Override
            public String getId() {
                return "test";
            }
            @Override
            public String getName() {
                return "test";
            }
            @Override
            public String getDescription() {
                return "test";
            }
            @Override
            public String getVersion() {
                return "1.0";
            }
            @Override
            public File getOutputDirectory() {
                return baseDirectory;
            }
            @Override
            public Optional<File> coverage() {
                return Optional.empty();
            }
            @Override
            public boolean isTestResource(File destination) {
                return false;
            }
            @Override
            public Optional<Date> latestModified() {
                return Optional.empty();
            }
            @Override
            public void setLatestModified(Date date) {
            }
            @Override
            public Optional<Date> latestSourceModified() {
                return Optional.empty();
            }
            @Override
            public void setLatestSourceModified(Date date) {
            }
            @Override
            public void close() {
            }
        };
    }
}
