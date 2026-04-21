package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileRevisionInfo;
import io.codiqo.core.diff.GitCommitAnalysis;
import io.codiqo.core.diff.GitFileAnalysis;
import io.codiqo.core.logging.SlfLogFactory;

class JGitDeltaAnalyzerTest {
    @TempDir
    Path tempDir;

    private Repository repository;
    private Git git;
    private RunArgs args;
    private JGitDeltaAnalyzer analyzer;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();
        repository.getConfig().setString("user", null, "name", "Test Author");
        repository.getConfig().setString("user", null, "email", "test@example.com");
        repository.getConfig().save();

        args = new RunArgs();
        args.setGit(repository);
        args.setIncludeUntracked(true);

        analyzer = new JGitDeltaAnalyzer(new SlfLogFactory(), args);
    }
    @AfterEach
    void closeRepo() {
        if (git != null) {
            git.close();
        }
        if (repository != null) {
            repository.close();
        }
    }
    @Test
    void initialCommitYieldsZeroParentsAndEmptyFiles() throws Exception {
        RevCommit first = commitFile("Foo.java", "class Foo {}", "initial");
        args.setCommitId(first.getName());

        CommitAnalysis analysis = analyzer.analyze();

        assertEquals(first.getName(), analysis.getCommitId());
        assertTrue(((GitCommitAnalysis) analysis).getParentIds().isEmpty(),
                "the initial commit has no parents, so parentIds stays empty");
        assertTrue(((GitCommitAnalysis) analysis).getFiles().isEmpty(),
                "early return on parentCount==0 leaves files empty — there is no parent to diff against");
    }
    @Test
    void secondCommitProducesSingleModifiedFile() throws Exception {
        RevCommit first = commitFile("Foo.java", "class Foo {}\n", "initial");
        RevCommit second = commitFile("Foo.java", "class Foo { int x; }\n", "add field");
        args.setCommitId(second.getName());

        CommitAnalysis analysis = analyzer.analyze();

        assertEquals(1, analysis.getFilesChanged());
        assertEquals(1, ((GitCommitAnalysis) analysis).getParentIds().size());
        assertEquals(first.getName(), ((GitCommitAnalysis) analysis).getParentIds().get(0));
        GitFileAnalysis file = (GitFileAnalysis) ((GitCommitAnalysis) analysis).getFiles().iterator().next();
        assertEquals(DiffEntry.ChangeType.MODIFY, file.getChangeType());
        assertEquals("class Foo {}\n", file.getContentBefore());
        assertEquals("class Foo { int x; }\n", file.getContentAfter());
        assertFalse(file.getStructuredDiff().getHunks().isEmpty(), "a real content change must produce at least one diff hunk");
    }
    @Test
    void addingNewFileInSecondCommitYieldsAddChangeType() throws Exception {
        commitFile("README.md", "hello\n", "initial");
        RevCommit add = commitFile("Foo.java", "class Foo {}\n", "add foo");
        args.setCommitId(add.getName());

        CommitAnalysis analysis = analyzer.analyze();

        GitFileAnalysis file = ((GitCommitAnalysis) analysis).getFiles().stream()
                .map(GitFileAnalysis.class::cast)
                .filter(f -> "Foo.java".equals(f.getNewPath()))
                .findFirst().orElseThrow();
        assertEquals(DiffEntry.ChangeType.ADD, file.getChangeType());
        assertNull(file.getContentBefore(), "an ADD must not populate contentBefore");
        assertEquals("class Foo {}\n", file.getContentAfter());
    }
    @Test
    void deletingFileInSecondCommitYieldsDeleteChangeType() throws Exception {
        commitFile("Doomed.java", "class Doomed {}\n", "initial");
        RevCommit delete = deleteFileAndCommit("Doomed.java", "remove doomed");
        args.setCommitId(delete.getName());

        CommitAnalysis analysis = analyzer.analyze();

        GitFileAnalysis file = (GitFileAnalysis) ((GitCommitAnalysis) analysis).getFiles().iterator().next();
        assertEquals(DiffEntry.ChangeType.DELETE, file.getChangeType());
        assertEquals("class Doomed {}\n", file.getContentBefore());
        assertNull(file.getContentAfter(), "a DELETE must not populate contentAfter");
    }
    @Test
    void revertPatternInCommitMessagePopulatesRevertFields() throws Exception {
        commitFile("Foo.java", "v1\n", "initial");
        String originalSha = "a".repeat(40);
        RevCommit revert = commitFile("Foo.java", "v2\n",
                "Revert boom\n\nThis reverts commit " + originalSha + ".\n");
        args.setCommitId(revert.getName());

        GitCommitAnalysis analysis = (GitCommitAnalysis) analyzer.analyze();

        assertTrue(analysis.isRevertCommit());
        assertEquals(originalSha, analysis.getRevertedCommitId(), "the 40-char hex SHA from 'This reverts commit <sha>.' is captured");
    }
    @Test
    void nonRevertCommitMessageLeavesRevertFieldsUntouched() throws Exception {
        RevCommit first = commitFile("Foo.java", "hi\n", "normal commit");
        args.setCommitId(first.getName());

        GitCommitAnalysis analysis = (GitCommitAnalysis) analyzer.analyze();

        assertFalse(analysis.isRevertCommit());
        assertNull(analysis.getRevertedCommitId());
    }
    @Test
    void authorAndCommitterMetadataIsCopiedFromCommit() throws Exception {
        RevCommit first = commitFile("Foo.java", "hi\n", "hello");
        args.setCommitId(first.getName());

        GitCommitAnalysis analysis = (GitCommitAnalysis) analyzer.analyze();

        assertEquals("Test Author", analysis.getAuthor());
        assertEquals("test@example.com", analysis.getAuthorEmail());
        assertEquals("Test Author", analysis.getCommitter());
        assertEquals("test@example.com", analysis.getCommitterEmail());
        assertNotNull(analysis.getAuthorTimestamp());
        assertNotNull(analysis.getCommitTimestamp());
        assertFalse(analysis.isMergeCommit());
    }
    @Test
    void branchListContainsMainAfterInitialCommit() throws Exception {
        RevCommit first = commitFile("Foo.java", "hi\n", "initial");
        args.setCommitId(first.getName());

        GitCommitAnalysis analysis = (GitCommitAnalysis) analyzer.analyze();

        assertFalse(analysis.getBranches().isEmpty());
    }
    @Test
    void fileContentAtRevisionReturnsCommittedContent() throws Exception {
        RevCommit first = commitFile("Foo.java", "v1\n", "initial");
        commitFile("Foo.java", "v2\n", "second");

        Optional<String> atFirst = analyzer.fileContentAtRevision("Foo.java", first.getName());

        assertTrue(atFirst.isPresent());
        assertEquals("v1\n", atFirst.get(), "fileContentAtRevision must read the file state frozen at that commit, not HEAD");
    }
    @Test
    void fileContentAtRevisionReturnsEmptyForUnresolvableRefName() throws Exception {
        commitFile("Foo.java", "hi\n", "init");

        Optional<String> result = analyzer.fileContentAtRevision("Foo.java", "refs/heads/does-not-exist");

        assertTrue(result.isEmpty());
    }
    @Test
    void fileContentAtRevisionReturnsEmptyForMissingFile() throws Exception {
        RevCommit first = commitFile("Foo.java", "hi\n", "init");

        Optional<String> result = analyzer.fileContentAtRevision("Nope.java", first.getName());

        assertTrue(result.isEmpty());
    }
    @Test
    void fileHistoryIsBoundedByMaxRevisions() throws Exception {
        commitFile("Foo.java", "v1\n", "first");
        commitFile("Foo.java", "v2\n", "second");
        commitFile("Foo.java", "v3\n", "third");

        List<FileRevisionInfo> history = analyzer.getFileHistory("Foo.java", 2);

        assertEquals(2, history.size(), "maxRevisions caps the returned history size, even when more revisions exist");
    }
    @Test
    void fileHistoryContentsAreOrderedNewestFirst() throws Exception {
        commitFile("Foo.java", "v1\n", "first");
        commitFile("Foo.java", "v2\n", "second");

        List<FileRevisionInfo> history = analyzer.getFileHistory("Foo.java", 10);

        assertEquals("v2\n", history.get(0).getContent(), "JGit's RevWalk iterates newest-first by default; history order must match");
        assertEquals("v1\n", history.get(1).getContent());
    }

    @Test
    void fileContentFromWorkingTreeReturnsCurrentContentEvenWhenUncommitted() throws Exception {
        commitFile("Foo.java", "committed\n", "init");
        writeFile("Foo.java", "uncommitted edit\n");

        Optional<String> content = analyzer.fileContentFromWorkingTree("Foo.java");

        assertTrue(content.isPresent());
        assertEquals("uncommitted edit\n", content.get(), "working-tree read must see the dirty file, not the committed version");
    }
    @Test
    void fileContentFromWorkingTreeReturnsEmptyForMissingFile() throws Exception {
        Optional<String> content = analyzer.fileContentFromWorkingTree("does-not-exist.java");

        assertTrue(content.isEmpty());
    }
    @Test
    void analyzeWithNoCommitIdFallsBackToUncommittedPath() throws Exception {
        commitFile("Foo.java", "committed\n", "init");
        writeFile("Foo.java", "dirty\n");

        CommitAnalysis analysis = analyzer.analyze();

        assertEquals(1, analysis.getFilesChanged());
        GitFileAnalysis file = (GitFileAnalysis) ((GitCommitAnalysis) analysis).getFiles().iterator().next();
        assertEquals(DiffEntry.ChangeType.MODIFY, file.getChangeType());
        assertEquals("committed\n", file.getContentBefore());
        assertEquals("dirty\n", file.getContentAfter());
    }
    @Test
    void uncommittedAnalysisIncludesUntrackedFilesWhenFlagEnabled() throws Exception {
        commitFile("Foo.java", "hi\n", "init");
        writeFile("Extra.java", "class Extra {}\n");

        CommitAnalysis analysis = analyzer.analyze();

        assertTrue(((GitCommitAnalysis) analysis).getFiles().stream()
                        .map(GitFileAnalysis.class::cast)
                        .anyMatch(f -> "Extra.java".equals(f.getNewPath())),
                "includeUntracked=true must surface new untracked files as ADD entries");
    }
    @Test
    void uncommittedAnalysisUsesCurrentUserAndUuidCommitId() throws Exception {
        commitFile("Foo.java", "hi\n", "init");

        GitCommitAnalysis analysis = (GitCommitAnalysis) analyzer.analyze();

        assertNotNull(analysis.getCommitId(), "uncommitted analysis synthesizes a UUID commit id");
        assertEquals(System.getProperty("user.name"), analysis.getAuthor());
        assertTrue(analysis.getMessage().startsWith("Uncommitted changes analysis at"));
        assertFalse(analysis.isMergeCommit());
    }

    @Test
    void uncommittedNoHeadOnFreshRepoReturnsStubAnalysis() throws Exception {
        writeFile("Fresh.java", "class Fresh {}\n");

        CommitAnalysis analysis = analyzer.analyze();

        assertNotNull(analysis.getCommitId());
        GitCommitAnalysis gca = (GitCommitAnalysis) analysis;
        assertEquals("Initial uncommitted changes (no commits yet)", gca.getMessage(),
                "a fresh repo with no HEAD routes through analyzeUncommittedNoHead");
        assertTrue(((GitCommitAnalysis) analysis).getFiles().stream()
                        .map(GitFileAnalysis.class::cast)
                        .anyMatch(f -> "Fresh.java".equals(f.getNewPath())),
                "untracked files in a fresh repo must still be visible when includeUntracked=true");
    }
    private RevCommit commitFile(String path, String content, String message) throws Exception {
        writeFile(path, content);
        try {
            git.add().addFilepattern(path).call();
            return git.commit().setMessage(message).setAuthor("Test Author", "test@example.com").call();
        } catch (GitAPIException err) {
            throw new IllegalStateException(err);
        }
    }
    private RevCommit deleteFileAndCommit(String path, String message) throws Exception {
        Files.delete(tempDir.resolve(path));
        try {
            git.rm().addFilepattern(path).call();
            return git.commit().setMessage(message).setAuthor("Test Author", "test@example.com").call();
        } catch (GitAPIException err) {
            throw new IllegalStateException(err);
        }
    }
    private void writeFile(String path, String content) throws Exception {
        Path target = tempDir.resolve(path);
        Files.createDirectories(target.getParent() != null ? target.getParent() : tempDir);
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
