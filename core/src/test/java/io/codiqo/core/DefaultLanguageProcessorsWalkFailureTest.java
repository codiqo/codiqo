package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.coverage.ExcludedCoverageClass;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.util.Fetch;

/**
 * The index walk's failure policy: an unreadable path is tolerated only when it could not have contributed to the
 * index anyway, because a tracked source file that fails is a hole in a scored analysis.
 */
class DefaultLanguageProcessorsWalkFailureTest {
    @TempDir
    Path tempDir;

    private Repository repository;
    private Git git;
    private RunArgs args;
    private Path unreadable;

    @BeforeEach
    void initRepo() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "the failure is provoked by removing directory traversal, which needs POSIX permissions");

        git = Git.init().setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();
        repository.getConfig().setString("user", null, "name", "Test Author");
        repository.getConfig().setString("user", null, "email", "test@example.com");
        repository.getConfig().save();

        args = new RunArgs();
        args.setGit(repository);
        args.setIncludeUntracked(false);
    }
    @AfterEach
    void restoreAndClose() throws Exception {
        // @TempDir cannot clean a directory it cannot traverse
        if (unreadable != null && Files.exists(unreadable)) {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"));
        }
        if (git != null) {
            git.close();
        }
        if (repository != null) {
            repository.close();
        }
    }
    /** "rw-": the directory still lists, but stat of each child fails, so the failure is reported against the FILE */
    @Test
    void anUnreadableTrackedSourceFileFailsTheIndexRatherThanScoringWithoutIt() throws Exception {
        unreadable = commitSourceUnder("app", "rw-------");
        assumeWalkReportsFailure();

        IOException err = assertThrows(IOException.class, this::index);
        assertTrue(err.getMessage().contains("Foo.java"), "the failure must name the path it could not read: " + err.getMessage());
        assertTrue(err.getMessage().contains("tracked source"),
                "the failure must say why continuing is unacceptable: " + err.getMessage());
    }
    /** "---": the stream cannot be opened, so the failure is reported against the DIRECTORY that hides the source */
    @Test
    void anUnreadableDirectoryHidingTrackedSourceFailsTheIndex() throws Exception {
        unreadable = commitSourceUnder("app", "---------");
        assumeWalkReportsFailure();

        IOException err = assertThrows(IOException.class, this::index);
        assertTrue(err.getMessage().contains("app"), "the failure must name the directory it could not read: " + err.getMessage());
    }
    @Test
    void anUnreadableDirectoryHoldingNothingIndexableIsSkipped() throws Exception {
        unreadable = Files.createDirectory(tempDir.resolve("scratch"));
        Files.writeString(unreadable.resolve("notes.txt"), "untracked, unsupported\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        assumeWalkReportsFailure();

        assertDoesNotThrow(this::index, "a path that could not have been indexed must not fail the run");
    }
    /**
     * the exclusion patterns pruned the only tracked source under the unreadable directory, so the walk would
     * never have indexed it — and the pattern names the directory, which does not match the files below it
     */
    @Test
    void anUnreadableDirectoryWhoseTrackedSourceIsExcludedIsSkipped() throws Exception {
        Path module = Files.createDirectories(tempDir.resolve("apps").resolve("legacy"));
        Files.writeString(module.resolve("Foo.java"), "class Foo {\n}\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("apps").call();
        git.commit().setMessage("add excluded module").call();
        args.setExcludePaths("apps/legacy");

        unreadable = tempDir.resolve("apps");
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        assumeWalkReportsFailure();

        assertDoesNotThrow(this::index, "an excluded tree contributes nothing to the index whether it can be read or not");
    }
    private Path commitSourceUnder(String directory, String mode) throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve(directory));
        Files.writeString(dir.resolve("Foo.java"), "class Foo {\n    void run() {\n    }\n}\n", StandardCharsets.UTF_8);
        git.add().addFilepattern(directory).call();
        git.commit().setMessage("add source").call();

        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(mode));
        return dir;
    }
    private void index() throws IOException {
        try (Fetch fetch = new Fetch(args)) {
            try (DefaultLanguageProcessors processors = new DefaultLanguageProcessors(new SlfLogFactory(), args, fetch)) {
                processors.index(new EmptyAnalysis());
            }
        }
    }
    /** probes with the mechanism under test rather than guessing: root, and some filesystems, ignore the mode */
    private void assumeWalkReportsFailure() throws IOException {
        MutableBoolean reported = new MutableBoolean();
        Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFileFailed(Path path, IOException err) {
                reported.setTrue();
                return FileVisitResult.CONTINUE;
            }
        });
        assumeTrue(reported.isTrue(), "running as root, or the filesystem ignores the permission change");
    }

    /** the walk runs before anything reads the delta, so an empty analysis is all index() needs */
    private static final class EmptyAnalysis implements CommitAnalysis {
        @Override
        public Iterator<FileAnalysis> iterator() {
            return Collections.<FileAnalysis> emptyList().iterator();
        }
        @Override
        public String getCommitId() {
            return "0000000000000000000000000000000000000000";
        }
        @Override
        public String getMessage() {
            return "test";
        }
        @Override
        public String getAuthor() {
            return "Test Author";
        }
        @Override
        public String getAuthorEmail() {
            return "test@example.com";
        }
        @Override
        public Date getAuthorTimestamp() {
            return new Date();
        }
        @Override
        public String getCommitter() {
            return getAuthor();
        }
        @Override
        public String getCommitterEmail() {
            return getAuthorEmail();
        }
        @Override
        public Date getCommitTimestamp() {
            return getAuthorTimestamp();
        }
        @Override
        public List<String> getParentIds() {
            return List.of();
        }
        @Override
        public boolean isHistoryIncomplete() {
            return false;
        }
        @Override
        public List<String> getBranches() {
            return List.of();
        }
        @Override
        public boolean isMergeCommit() {
            return false;
        }
        @Override
        public boolean isRevertCommit() {
            return false;
        }
        @Override
        public String getRevertedCommitId() {
            return null;
        }
        @Override
        public int getFilesChanged() {
            return 0;
        }
        @Override
        public boolean isPresent(File file, CodeBlockInfo block) {
            return false;
        }
        @Override
        public Collection<File> locations() {
            return List.of();
        }
        @Override
        public Collection<CopyPasteDetectionSummary> cpd() {
            return List.of();
        }
        @Override
        public Collection<ExcludedCoverageClass> excludedCoverageClasses() {
            return List.of();
        }
    }
}
