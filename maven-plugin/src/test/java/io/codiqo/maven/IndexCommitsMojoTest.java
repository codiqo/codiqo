package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
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
import io.codiqo.submit.CommitIndexer;
import io.codiqo.util.RepositoryUrls;

class IndexCommitsMojoTest {
    private static final Date EPOCH = new Date(0);
    private static final String BITBUCKET_SCM_URL = "scm:git:git@bitbucket.org:acme/sample-repo/tree/master";
    private static final int GIT_OBJECT_FANOUT_LENGTH = 2;

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
    void excludeAuthorFilterDropsByEmail() throws Exception {
        RevCommit kept = commitAs("a.txt", "v1", "kept", "Alice", "alice@example.com");
        RevCommit dropped = commitAs("a.txt", "v2", "dropped", "Bob", "bob@other.com");

        RunArgs filter = new RunArgs();
        filter.setExcludeAuthorEmails("bob@other.com");
        List<CommitModel> commits = extract(filter, "HEAD", EPOCH, "main");

        assertEquals(1, commits.size());
        assertEquals(kept.getName(), commits.get(0).getSha());
        assertFalse(commits.stream().anyMatch(c -> dropped.getName().equals(c.getSha())));
    }
    @Test
    void mergeCommitIsIncludedInExtraction() throws Exception {
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

        List<CommitModel> commits = extract(allCommits(), "HEAD", EPOCH, "main");
        CommitModel mergeCommit = commits.stream()
                .filter(c -> merge.getNewHead().getName().equals(c.getSha()))
                .findFirst().orElseThrow();

        assertTrue(mergeCommit.getIsMerge());
        assertEquals(2, mergeCommit.getParents().size());
        assertTrue(mergeCommit.getParents().contains(base.getName())
                || mergeCommit.getParents().stream().anyMatch(StringUtils::isNotEmpty));
    }
    @Test
    void missingAnalysisSelectionIncludesMergeCommits() throws Exception {
        RevCommit root = commit("a.txt", "base", "base");
        git.checkout().setCreateBranch(true).setName("feature").call();
        commit("b.txt", "feat", "feature work");
        git.checkout().setName("main").call();
        RevCommit linear = commit("c.txt", "main-side", "main work");

        MergeResult merge = git.merge()
                .include(repository.resolve("feature"))
                .setCommit(true)
                .setMessage("merge feature")
                .call();

        IndexCommitsMojo.MissingAnalysesSelection selection = IndexCommitsMojo.selectAnalyzableMissingAnalyses(
                repository,
                List.of(root.getName(), linear.getName(), merge.getNewHead().getName()));

        // merges flow through selection so the analyze step can exclude them (excluded=true row)
        assertEquals(List.of(root.getName(), linear.getName(), merge.getNewHead().getName()), selection.analyzableShas());
        assertEquals(0, selection.skippedMissingCommitCount());
        assertEquals(0, selection.skippedMissingParentCount());
    }
    @Test
    void missingAnalysisSelectionCountsMissingCommitShas() throws Exception {
        RevCommit kept = commit("a.txt", "v1", "initial");

        IndexCommitsMojo.MissingAnalysesSelection selection = IndexCommitsMojo.selectAnalyzableMissingAnalyses(
                repository,
                List.of("missing-sha", kept.getName()));

        assertEquals(List.of(kept.getName()), selection.analyzableShas());
        assertEquals(1, selection.skippedMissingCommitCount());
        assertEquals(0, selection.skippedMissingParentCount());
    }
    @Test
    void missingAnalysisSelectionSkipsFullShaAbsentFromObjectDb() throws Exception {
        RevCommit kept = commit("a.txt", "v1", "initial");
        String absentFullSha = StringUtils.repeat('b', 40);

        IndexCommitsMojo.MissingAnalysesSelection selection = IndexCommitsMojo.selectAnalyzableMissingAnalyses(
                repository,
                List.of(absentFullSha, kept.getName()));

        assertEquals(List.of(kept.getName()), selection.analyzableShas());
        assertEquals(1, selection.skippedMissingCommitCount());
        assertEquals(0, selection.skippedMissingParentCount());
    }
    @Test
    void missingAnalysisSelectionCountsCommitWithAbsentFirstParent() throws Exception {
        RevCommit parent = commit("a.txt", "v1", "root");
        RevCommit child = commit("a.txt", "v2", "child of root");

        // truncated history (shallow clone / removed branch): the first-parent object is gone locally
        deleteLooseObject(parent);

        try (Repository truncated = reopenRepository()) {
            IndexCommitsMojo.MissingAnalysesSelection selection = IndexCommitsMojo.selectAnalyzableMissingAnalyses(
                    truncated,
                    List.of(child.getName()));

            assertEquals(List.of(), selection.analyzableShas(), "a commit whose first parent is missing cannot be analyzed");
            assertEquals(0, selection.skippedMissingCommitCount());
            assertEquals(1, selection.skippedMissingParentCount());
        }
    }
    @Test
    void missingAnalysisSelectionPartitionsMixedBatch() throws Exception {
        RevCommit parent = commit("a.txt", "v1", "root");
        RevCommit child = commit("a.txt", "v2", "child of root");
        RevCommit standalone = commit("b.txt", "b1", "analyzable commit");
        String absentFullSha = StringUtils.repeat('c', 40);

        deleteLooseObject(parent);

        try (Repository truncated = reopenRepository()) {
            IndexCommitsMojo.MissingAnalysesSelection selection = IndexCommitsMojo.selectAnalyzableMissingAnalyses(
                    truncated,
                    List.of(absentFullSha, child.getName(), standalone.getName()));

            assertEquals(List.of(standalone.getName()), selection.analyzableShas());
            assertEquals(1, selection.skippedMissingCommitCount());
            assertEquals(1, selection.skippedMissingParentCount());
        }
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
    void duplicateSquashMergeCollapsedByPatchId() throws Exception {
        RevCommit base = commit("a.txt", "base", "base");

        commit("x.txt", "x", "main divergence");
        RevCommit first = commit("f.txt", "hello", "apply change (main)");

        git.checkout().setCreateBranch(true).setName("other").setStartPoint(base.getName()).call();
        commit("y.txt", "y", "other divergence");
        RevCommit second = commit("f.txt", "hello", "apply change (other)");

        git.checkout().setName("main").call();
        git.merge().include(repository.resolve("other")).setCommit(true).setMessage("merge other").call();

        List<CommitModel> commits = extract(allCommits(), "HEAD", EPOCH, "main");

        long dupCount = commits.stream()
                .filter(c -> first.getName().equals(c.getSha()) || second.getName().equals(c.getSha()))
                .count();
        assertEquals(1, dupCount, "duplicate squash-merge (same patch-id) must be counted once");
    }
    @Test
    void distinctChangesOnDivergentBranchesBothSurvive() throws Exception {
        RevCommit base = commit("a.txt", "base", "base");

        commit("x.txt", "x", "main divergence");
        RevCommit first = commit("f.txt", "hello", "apply change (main)");

        git.checkout().setCreateBranch(true).setName("other").setStartPoint(base.getName()).call();
        commit("y.txt", "y", "other divergence");
        RevCommit second = commit("g.txt", "world", "different change (other)");

        git.checkout().setName("main").call();
        git.merge().include(repository.resolve("other")).setCommit(true).setMessage("merge other").call();

        List<CommitModel> commits = extract(allCommits(), "HEAD", EPOCH, "main");

        long keptCount = commits.stream()
                .filter(c -> first.getName().equals(c.getSha()) || second.getName().equals(c.getSha()))
                .count();
        assertEquals(2, keptCount, "distinct changes (different patch-id) must both be kept in the same topology");
    }
    @Test
    void toUriNormalizesScpStyleGitUrls() throws Exception {
        URI uri = RepositoryUrls.toUri(BITBUCKET_SCM_URL);

        assertEquals("https://bitbucket.org/acme/sample-repo/tree/master", uri.toString());
    }
    @Test
    void toUriPreservesStandardUris() throws Exception {
        URI uri = RepositoryUrls.toUri("scm:git:https://bitbucket.org/acme/sample-repo.git");

        assertEquals("https://bitbucket.org/acme/sample-repo.git", uri.toString());
    }
    @Test
    void firstParentOnlyExcludesMergedInBranchCommits() throws Exception {
        RevCommit base = commit("a.txt", "base", "base");
        git.checkout().setCreateBranch(true).setName("feature").call();
        RevCommit featureWork = commit("b.txt", "feat", "feature work");
        git.checkout().setName("main").call();
        RevCommit mainWork = commit("c.txt", "main-side", "main work");
        MergeResult merge = git.merge()
                .include(repository.resolve("feature"))
                .setCommit(true)
                .setMessage("merge feature")
                .call();

        List<String> mainline = extract(new RunArgs(), "HEAD", EPOCH, "main")
                .stream().map(CommitModel::getSha).toList();
        assertTrue(mainline.contains(merge.getNewHead().getName()), "the merge node stays on the mainline");
        assertTrue(mainline.contains(mainWork.getName()));
        assertTrue(mainline.contains(base.getName()));
        assertFalse(mainline.contains(featureWork.getName()), "merged-in feature-branch commit is dropped in first-parent mode");

        List<String> all = extract(allCommits(), "HEAD", EPOCH, "main")
                .stream().map(CommitModel::getSha).toList();
        assertTrue(all.contains(featureWork.getName()), "feature-branch commit is indexed again when firstParentOnly=false");
    }
    @Test
    void mergeCommitPrFirstParentKeepsMergeNodeAndDropsEveryFeatureCommit() throws Exception {
        RevCommit base = commit("base.txt", "0", "base");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        RevCommit f1 = commit("f1.txt", "1", "PR commit 1");
        RevCommit f2 = commit("f2.txt", "2", "PR commit 2");
        RevCommit f3 = commit("f3.txt", "3", "PR commit 3");
        git.checkout().setName("main").call();
        RevCommit m1 = commit("m1.txt", "m", "mainline divergence");
        String mergeSha = git.merge().include(repository.resolve("feature"))
                .setCommit(true).setMessage("Merge pull request #1").call().getNewHead().getName();

        List<String> fp = shas(extract(new RunArgs(), "HEAD", EPOCH, "main"));
        assertTrue(fp.contains(mergeSha), "the PR merge node stays on the mainline spine");
        assertTrue(fp.contains(m1.getName()));
        assertTrue(fp.contains(base.getName()));
        assertFalse(fp.contains(f1.getName()), "PR feature commit 1 is dropped in first-parent mode");
        assertFalse(fp.contains(f2.getName()), "PR feature commit 2 is dropped");
        assertFalse(fp.contains(f3.getName()), "PR feature commit 3 is dropped");

        List<String> all = shas(extract(allCommits(), "HEAD", EPOCH, "main"));
        assertTrue(all.containsAll(List.of(f1.getName(), f2.getName(), f3.getName())),
                "all-commits mode still indexes every feature-branch commit");
    }
    @Test
    void squashPrIsASingleMainlineCommitAndTheUnmergedBranchStaysInvisible() throws Exception {
        commit("base.txt", "0", "base");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        RevCommit wip1 = commit("f.txt", "a", "wip 1");
        RevCommit wip2 = commit("f.txt", "ab", "wip 2");
        git.checkout().setName("main").call();
        // a squash-merge produces one NEW single-parent commit on main; the branch is never merged in
        RevCommit squash = commit("f.txt", "ab", "Squashed PR #2 (2 commits)");

        for (RunArgs mode : List.of(new RunArgs(), allCommits())) {
            List<String> got = shas(extract(mode, "HEAD", EPOCH, "main"));
            assertTrue(got.contains(squash.getName()), "the squash commit is a normal single-parent mainline commit");
            assertFalse(got.contains(wip1.getName()), "an unmerged PR-branch commit never reaches mainline");
            assertFalse(got.contains(wip2.getName()));
        }
    }
    @Test
    void rebasePrLinearCommitsAllSurviveAndFirstParentEqualsAllCommits() throws Exception {
        commit("base.txt", "0", "base");
        // a rebase-merge replays the PR's commits linearly onto the tip — they ARE mainline commits
        RevCommit r1 = commit("r1.txt", "1", "PR commit 1 (rebased)");
        RevCommit r2 = commit("r2.txt", "2", "PR commit 2 (rebased)");
        RevCommit r3 = commit("r3.txt", "3", "PR commit 3 (rebased)");

        List<String> fp = shas(extract(new RunArgs(), "HEAD", EPOCH, "main"));
        assertTrue(fp.containsAll(List.of(r1.getName(), r2.getName(), r3.getName())),
                "rebased PR commits are linear mainline commits — first-parent keeps ALL of them (a rebase is not collapsed)");
        assertEquals(shas(extract(allCommits(), "HEAD", EPOCH, "main")), fp,
                "with no merges present, first-parent and all-commits produce an identical set");
    }
    @Test
    void mixedStrategyHistoryFirstParentIsExactlyTheMainlineSpine() throws Exception {
        RevCommit base = commit("base.txt", "0", "base");
        RevCommit squash = commit("s.txt", "s", "Squashed PR #1");
        RevCommit rebase1 = commit("r1.txt", "1", "Rebased PR #2 commit 1");
        RevCommit rebase2 = commit("r2.txt", "2", "Rebased PR #2 commit 2");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        RevCommit feat = commit("f.txt", "f", "merge-commit PR work");
        git.checkout().setName("main").call();
        RevCommit m1 = commit("m.txt", "m", "mainline");
        String mergeSha = git.merge().include(repository.resolve("feature"))
                .setCommit(true).setMessage("Merge pull request #3").call().getNewHead().getName();

        List<String> fp = shas(extract(new RunArgs(), "HEAD", EPOCH, "main"));
        assertTrue(fp.containsAll(List.of(base.getName(), squash.getName(), rebase1.getName(),
                        rebase2.getName(), m1.getName(), mergeSha)),
                "spine = base + squash + both rebased commits + mainline commit + merge node");
        assertFalse(fp.contains(feat.getName()), "only the merge-commit PR's feature-branch commit is dropped");
    }
    @Test
    void firstParentBotAuthoredMergeIsCreditedToTheSideBranchAuthor() throws Exception {
        commit("base.txt", "0", "base");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        RevCommit devWork = commitAs("f.txt", "f", "dev PR work", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();
        commit("m.txt", "m", "mainline");
        // a CI bot / merge queue authors the integration merge
        String mergeSha = mergeAs("feature", "Merge pull request #9", "CI Bot", "bot@ci.com");

        RunArgs onlyDev = new RunArgs();
        onlyDev.setIncludeAuthorEmails("dev@corp.com");
        List<CommitModel> fp = extract(onlyDev, "HEAD", EPOCH, "main");

        CommitModel mergeNode = fp.stream().filter(c -> mergeSha.equals(c.getSha())).findFirst().orElseThrow();
        assertEquals("dev@corp.com", mergeNode.getAuthorEmail(),
                "the merge node is credited to the side-branch sole author, so the dev author filter keeps the PR");
        assertEquals("Dev", mergeNode.getAuthor());
        assertFalse(shas(fp).contains(devWork.getName()), "the dev feature commit itself stays off the first-parent spine");
    }
    @Test
    void mixedAuthorSideBranchMergeKeepsTheMergeAuthor() throws Exception {
        commit("base.txt", "0", "base");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit 1", "Dev", "dev@corp.com");
        commitAs("f2.txt", "2", "PR commit 2", "Other", "other@corp.com");
        git.checkout().setName("main").call();
        commit("m.txt", "m", "mainline");
        String mergeSha = mergeAs("feature", "Merge pull request #10", "CI Bot", "bot@ci.com");

        List<CommitModel> fp = extract(new RunArgs(), "HEAD", EPOCH, "main");

        CommitModel mergeNode = fp.stream().filter(c -> mergeSha.equals(c.getSha())).findFirst().orElseThrow();
        assertEquals("bot@ci.com", mergeNode.getAuthorEmail(), "no sole side-branch author — the merge author is kept");
    }
    private List<CommitModel> extract(RunArgs filter, String ref, Date cutoff, String branch) throws Exception {
        return CommitIndexer.extractCommits(repository, filter, ref, cutoff, branch);
    }
    private static RunArgs allCommits() {
        RunArgs toReturn = new RunArgs();
        toReturn.setFirstParentOnly(false);
        return toReturn;
    }
    private static List<String> shas(List<CommitModel> commits) {
        return commits.stream().map(CommitModel::getSha).toList();
    }
    private String mergeAs(String branch, String message, String authorName, String authorEmail) throws Exception {
        repository.getConfig().setString("user", null, "name", authorName);
        repository.getConfig().setString("user", null, "email", authorEmail);
        repository.getConfig().save();
        try {
            return git.merge().include(repository.resolve(branch)).setCommit(true).setMessage(message).call()
                    .getNewHead().getName();
        } finally {
            repository.getConfig().setString("user", null, "name", "Test Author");
            repository.getConfig().setString("user", null, "email", "test@example.com");
            repository.getConfig().save();
        }
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
    /**
     * reopen the on-disk repo with a fresh handle so selectAnalyzableMissingAnalyses reads the
     * object store directly: the repository opened in initRepo has an UnpackedObjectCache that
     * would still report a just-deleted loose object as present
     */
    private Repository reopenRepository() throws IOException {
        return new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();
    }
    private void deleteLooseObject(RevCommit commit) throws IOException {
        String sha = commit.getName();
        Path looseObject = tempDir.resolve(".git").resolve("objects")
                .resolve(sha.substring(0, GIT_OBJECT_FANOUT_LENGTH)).resolve(sha.substring(GIT_OBJECT_FANOUT_LENGTH));
        Files.delete(looseObject);
    }
}
