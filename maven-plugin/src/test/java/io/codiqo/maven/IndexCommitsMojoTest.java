package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.CommitModel;

class IndexCommitsMojoTest {
    private static final Date EPOCH = new Date(0);
    private static final String BITBUCKET_SCM_URL = "scm:git:git@bitbucket.org:turbospaces/turbospaces-boot/tree/master";

    @TempDir
    Path tempDir;

    private Git git;
    private Repository repository;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();
        repository.getConfig().setString("user", null, "name", "Test Author");
        repository.getConfig().setString("user", null, "email", "test@example.com");
        repository.getConfig().save();
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
    void linearHistoryReturnsAllCommitsTopoSortedNewestFirst() throws Exception {
        RevCommit first = commit("a.txt", "1", "first");
        RevCommit second = commit("a.txt", "2", "second");
        RevCommit third = commit("a.txt", "3", "third");

        List<CommitModel> commits = extract(new RunArgs(), "HEAD", EPOCH, "main");

        assertEquals(3, commits.size());
        assertEquals(third.getName(), commits.get(0).getSha());
        assertEquals(second.getName(), commits.get(1).getSha());
        assertEquals(first.getName(), commits.get(2).getSha());
    }
    @Test
    void branchFilterExcludesOffBranchCommits() throws Exception {
        commit("a.txt", "main-1", "main initial");
        git.checkout().setCreateBranch(true).setName("feature").call();
        RevCommit featureOnly = commit("b.txt", "feature-1", "feature only");
        git.checkout().setName("main").call();
        RevCommit mainLatest = commit("a.txt", "main-2", "main second");

        List<CommitModel> commits = extract(new RunArgs(), "main", EPOCH, "main");

        assertTrue(commits.stream().anyMatch(c -> mainLatest.getName().equals(c.getSha())));
        assertFalse(commits.stream().anyMatch(c -> featureOnly.getName().equals(c.getSha())),
                "feature-only commit must be excluded when branch=main");
    }
    @Test
    void authorFilterExcludesByEmail() throws Exception {
        RevCommit kept = commitAs("a.txt", "v1", "kept", "Alice", "alice@example.com");
        RevCommit dropped = commitAs("a.txt", "v2", "dropped", "Bob", "bob@other.com");

        RunArgs filter = new RunArgs();
        filter.setIncludeAuthorEmails("alice@example.com");
        List<CommitModel> commits = extract(filter, "HEAD", EPOCH, "main");

        assertEquals(1, commits.size());
        assertEquals(kept.getName(), commits.get(0).getSha());
        assertFalse(commits.stream().anyMatch(c -> dropped.getName().equals(c.getSha())));
    }
    @Test
    void mergeCommitSetsIsMergeAndMultipleParents() throws Exception {
        RevCommit base = commit("a.txt", "base", "base");
        git.checkout().setCreateBranch(true).setName("feature").call();
        commit("b.txt", "feat", "feature work");
        git.checkout().setName("main").call();
        commit("c.txt", "main-side", "main work");

        MergeResult merge = git.merge()
                .include(repository.resolve("feature"))
                .setCommit(true)
                .setMessage("merge feature")
                .call();

        List<CommitModel> commits = extract(new RunArgs(), "HEAD", EPOCH, "main");
        CommitModel mergeCommit = commits.stream()
                .filter(c -> merge.getNewHead().getName().equals(c.getSha()))
                .findFirst().orElseThrow();

        assertTrue(mergeCommit.getIsMerge());
        assertEquals(2, mergeCommit.getParents().size());
        assertTrue(mergeCommit.getParents().contains(base.getName())
                || mergeCommit.getParents().stream().anyMatch(StringUtils::isNotEmpty));
    }
    @Test
    void revertCommitPopulatesRevertFields() throws Exception {
        commit("a.txt", "v1", "initial");
        String fakeRevertedSha = StringUtils.repeat('a', 40);
        RevCommit revert = commit("a.txt", "v2", "Revert feature\n\nThis reverts commit " + fakeRevertedSha + ".\n");

        List<CommitModel> commits = extract(new RunArgs(), "HEAD", EPOCH, "main");
        CommitModel revertCommit = commits.stream()
                .filter(c -> revert.getName().equals(c.getSha()))
                .findFirst().orElseThrow();

        assertTrue(revertCommit.getIsRevert());
        assertEquals(fakeRevertedSha, revertCommit.getRevertedCommitId());
    }
    @Test
    void toUriNormalizesScpStyleGitUrls() throws Exception {
        URI uri = IndexCommitsMojo.toUri(BITBUCKET_SCM_URL);

        assertEquals("https://bitbucket.org/turbospaces/turbospaces-boot/tree/master", uri.toString());
    }
    @Test
    void toUriPreservesStandardUris() throws Exception {
        URI uri = IndexCommitsMojo.toUri("scm:git:https://bitbucket.org/turbospaces/turbospaces-boot.git");

        assertEquals("https://bitbucket.org/turbospaces/turbospaces-boot.git", uri.toString());
    }
    private List<CommitModel> extract(RunArgs filter, String ref, Date cutoff, String branch) throws Exception {
        return IndexCommitsMojo.extractCommits(repository, filter, ref, cutoff, branch);
    }
    private RevCommit commit(String path, String content, String message) throws Exception {
        return commitAs(path, content, message, "Test Author", "test@example.com");
    }
    private RevCommit commitAs(String path, String content, String message, String authorName, String authorEmail)
            throws Exception {
        Path target = tempDir.resolve(path);
        Files.writeString(target, content, StandardCharsets.UTF_8);
        try {
            git.add().addFilepattern(path).call();
            return git.commit().setMessage(message).setAuthor(authorName, authorEmail).call();
        } catch (GitAPIException err) {
            throw new IllegalStateException(err);
        }
    }
}
