package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;

class AnalyzeCommitMojoMergeGateTest {
    @TempDir
    Path tempDir;

    private Git git;
    private Repository repository;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();
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
    void soleAuthorPrMergeIsAnalyzableInFirstParentMode() throws Exception {
        String mergeSha = prMerge("Dev", "dev@corp.com", "Dev", "dev@corp.com");

        assertTrue(AnalyzeCommitMojo.mergeSkipReason(runArgs(mergeSha, true)).isEmpty(),
                "a two-parent PR merge with a sole side-branch author proceeds to analysis");
    }
    @Test
    void mixedAuthorPrMergeStaysExcluded() throws Exception {
        String mergeSha = prMerge("Dev", "dev@corp.com", "Other", "other@corp.com");

        Optional<String> reason = AnalyzeCommitMojo.mergeSkipReason(runArgs(mergeSha, true));
        assertEquals("merge side-branch commits have multiple authors", reason.orElseThrow());
    }
    @Test
    void oursStrategyMergeLandingNoMainlineChangesStaysExcluded() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit 1", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        ObjectId newHead = git.merge().include(repository.resolve("feature"))
                .setStrategy(MergeStrategy.OURS)
                .setCommit(true).setMessage("Merge branch 'feature' (discarded)").call().getNewHead();

        Optional<String> reason = AnalyzeCommitMojo.mergeSkipReason(runArgs(newHead.getName(), true));
        assertEquals("merge introduces no mainline changes", reason.orElseThrow(),
                "an ours-strategy merge keeps parent[0]'s tree — nothing landed, guaranteed zero score");
    }
    @Test
    void allCommitsModeKeepsUnconditionalMergeExclusion() throws Exception {
        String mergeSha = prMerge("Dev", "dev@corp.com", "Dev", "dev@corp.com");

        Optional<String> reason = AnalyzeCommitMojo.mergeSkipReason(runArgs(mergeSha, false));
        assertEquals("merge commit (multiple parents)", reason.orElseThrow(),
                "in all-commits mode the side-branch commits are indexed individually — analyzing the merge would double-count");
    }
    private RunArgs runArgs(String commitSha, boolean firstParentOnly) {
        RunArgs toReturn = new RunArgs();
        toReturn.setGit(repository);
        toReturn.setCommitId(commitSha);
        toReturn.setFirstParentOnly(firstParentOnly);
        return toReturn;
    }
    private String prMerge(String firstAuthor, String firstEmail, String secondAuthor, String secondEmail) throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit 1", firstAuthor, firstEmail);
        commitAs("f2.txt", "2", "PR commit 2", secondAuthor, secondEmail);
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");

        repository.getConfig().setString("user", null, "name", "CI Bot");
        repository.getConfig().setString("user", null, "email", "bot@ci.com");
        repository.getConfig().save();
        ObjectId newHead = git.merge().include(repository.resolve("feature")).setCommit(true)
                .setMessage("Merge pull request #1").call().getNewHead();
        return newHead.getName();
    }
    private RevCommit commitAs(String path, String content, String message, String authorName, String authorEmail)
            throws Exception {
        Files.writeString(tempDir.resolve(path), content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).setAuthor(authorName, authorEmail).call();
    }
}
