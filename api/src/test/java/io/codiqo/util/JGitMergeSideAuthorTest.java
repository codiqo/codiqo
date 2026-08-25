package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitMergeSideAuthorTest {
    /** mirrors the RunArgs default: any address carrying "bot" is rejected. */
    private static final Predicate<String> NO_BOTS =
            email -> !email.toLowerCase(Locale.ROOT).contains("bot");

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
    void soleAuthorPrMergeDerivesTheSideBranchAuthor() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit 1", "Dev", "dev@corp.com");
        commitAs("f2.txt", "2", "PR commit 2", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("feature", "Merge pull request #1", "CI Bot", "bot@ci.com");

        assertEquals(2, JGit.mergeSideCommits(repository, merge).size());

        Optional<PersonIdent> soleAuthor = JGit.mergeSideSoleAuthor(repository, merge);
        assertTrue(soleAuthor.isPresent());
        assertEquals("dev@corp.com", soleAuthor.get().getEmailAddress());
        assertEquals("Dev", soleAuthor.get().getName());
    }
    @Test
    void mixedAuthorSideBranchDerivesNoSoleAuthor() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit 1", "Dev", "dev@corp.com");
        commitAs("f2.txt", "2", "PR commit 2", "Other", "other@corp.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("feature", "Merge pull request #2", "CI Bot", "bot@ci.com");

        assertTrue(JGit.mergeSideSoleAuthor(repository, merge).isEmpty());
    }
    @Test
    void backMergeOfMainlineIntoThePrBranchStaysSoleAuthor() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit 1", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();
        commitAs("m1.txt", "m1", "mainline progress", "Maintainer", "maintainer@corp.com");
        git.checkout().setName("feature").call();
        mergeAs("main", "Merge branch 'main' into feature", "Dev", "dev@corp.com");
        commitAs("f2.txt", "2", "PR commit 2", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();
        RevCommit merge = mergeAs("feature", "Merge pull request #3", "CI Bot", "bot@ci.com");

        /**
         * mainline commits reachable through the back-merge are behind parent[0] and drop out of the
         * side set — only the dev's own commits (including the back-merge node) remain
         */
        Optional<PersonIdent> soleAuthor = JGit.mergeSideSoleAuthor(repository, merge);
        assertTrue(soleAuthor.isPresent());
        assertEquals("dev@corp.com", soleAuthor.get().getEmailAddress());
    }
    @Test
    void octopusMergeDerivesNoSoleAuthor() throws Exception {
        RevCommit base = commitAs("base.txt", "0", "base", "Dev", "dev@corp.com");
        git.branchCreate().setName("f1").call();
        git.checkout().setName("f1").call();
        RevCommit side1 = commitAs("a.txt", "1", "side 1", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();
        git.branchCreate().setName("f2").call();
        git.checkout().setName("f2").call();
        RevCommit side2 = commitAs("b.txt", "2", "side 2", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();

        RevCommit octopus = rawMerge(List.of(base, side1, side2), "octopus merge");
        assertTrue(JGit.mergeSideSoleAuthor(repository, octopus).isEmpty());
    }
    private RevCommit rawMerge(List<RevCommit> parents, String message) throws Exception {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(parents.iterator().next().getTree());
            builder.setParentIds(parents.stream().map(RevCommit::getId).toList());
            builder.setAuthor(new PersonIdent("Octo", "octo@corp.com"));
            builder.setCommitter(new PersonIdent("Octo", "octo@corp.com"));
            builder.setMessage(message);

            ObjectId commitId = inserter.insert(builder);
            inserter.flush();
            try (RevWalk walk = new RevWalk(repository)) {
                return walk.parseCommit(commitId);
            }
        }
    }
    /**
     * the case the default {@code *bot*} exclusion would otherwise break: a merge queue lands a PR written by two
     * people, so no sole side author exists and the node keeps the bot's identity. dropping it would drop their work.
     */
    @Test
    void botMergeOfHumanWorkIsAdmittedThroughItsSideBranch() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit 1", "Dev", "dev@corp.com");
        commitAs("f2.txt", "2", "PR commit 2", "Other", "other@corp.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("feature", "Merge pull request #4", "CI Bot", "bot@ci.com");

        assertTrue(JGit.isAuthorAdmitted(repository, merge, merge.getAuthorIdent(), NO_BOTS));
    }
    /** a bot merging its own bot-authored branch has no human behind it and stays excluded. */
    @Test
    void botMergeOfBotWorkStaysExcluded() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("deps").call();
        git.checkout().setName("deps").call();
        commitAs("d1.txt", "1", "bump a dependency", "dependabot[bot]", "dependabot[bot]@users.noreply.github.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("deps", "Merge pull request #5", "CI Bot", "bot@ci.com");

        assertFalse(JGit.isAuthorAdmitted(repository, merge, merge.getAuthorIdent(), NO_BOTS));
    }
    /** a plain bot commit has no side branch to look at, so the exclusion applies unchanged. */
    @Test
    void nonMergeBotCommitStaysExcluded() throws Exception {
        RevCommit commit = commitAs("a.txt", "0", "generated", "Release Bot", "release-bot@corp.com");

        assertFalse(JGit.isAuthorAdmitted(repository, commit, commit.getAuthorIdent(), NO_BOTS));
    }
    private RevCommit mergeAs(String branch, String message, String authorName, String authorEmail) throws Exception {
        repository.getConfig().setString("user", null, "name", authorName);
        repository.getConfig().setString("user", null, "email", authorEmail);
        repository.getConfig().save();

        // GitHub's "Create a merge commit" button always merges with --no-ff
        ObjectId newHead = git.merge().include(repository.resolve(branch))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true).setMessage(message).call()
                .getNewHead();
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(newHead);
        }
    }
    private RevCommit commitAs(String path, String content, String message, String authorName, String authorEmail)
            throws Exception {
        Files.writeString(tempDir.resolve(path), content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).setAuthor(authorName, authorEmail).call();
    }
}
