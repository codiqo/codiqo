package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
    /** two one-line commits against one: the author who changed the most lines is credited, not the merge author. */
    @Test
    void multiAuthorSideBranchCreditsTheAuthorWhoChangedMostLines() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit 1", "Dev", "dev@corp.com");
        commitAs("f2.txt", "2", "PR commit 2", "Dev", "dev@corp.com");
        commitAs("f3.txt", "3", "a drive-by fix", "Other", "other@corp.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("feature", "Merge pull request #6", "CI Bot", "bot@ci.com");

        assertEquals("dev@corp.com", JGit.creditedAuthor(repository, merge).getEmailAddress());
    }
    /** equal commit counts: the author of the larger change is credited. */
    @Test
    void equalCommitCountsAreBrokenByLinesChanged() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("small.txt", "one line", "small change", "Small", "small@corp.com");
        commitAs("big.txt", "a\nb\nc\nd\ne\nf\ng\nh", "big change", "Big", "big@corp.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("feature", "Merge pull request #7", "CI Bot", "bot@ci.com");

        assertEquals("big@corp.com", JGit.creditedAuthor(repository, merge).getEmailAddress());
    }
    /**
     * a human pull request with two bot autofix commits on top: more commits, fewer lines. Counting commits credited the
     * bot, and the default "*bot*" exclusion then dropped the developer's work entirely.
     */
    @Test
    void botAutofixCommitsDoNotOutweighTheHumanChange() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("feature.txt", "a\nb\nc\nd\ne\nf\ng\nh", "the feature", "Dev", "dev@corp.com");
        commitAs("fmt1.txt", "1", "apply formatting", "github-actions[bot]", "41898282+github-actions[bot]@users.noreply.github.com");
        commitAs("fmt2.txt", "2", "apply formatting", "github-actions[bot]", "41898282+github-actions[bot]@users.noreply.github.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("feature", "Merge pull request #9", "CI Bot", "bot@ci.com");

        assertEquals("dev@corp.com", JGit.creditedAuthor(repository, merge).getEmailAddress());
    }
    /** a bot that keeps the branch up to date authors back-merge nodes, which change no line of their own and do not count. */
    @Test
    void botBackMergesIntoTheBranchAreNotAuthorship() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("f1.txt", "1", "PR commit", "Dev", "dev@corp.com");
        for (int i = 0; i < 2; i++) {
            git.checkout().setName("main").call();
            commitAs("m" + i + ".txt", "m", "mainline progress", "Maintainer", "maintainer@corp.com");
            git.checkout().setName("feature").call();
            mergeAs("main", "Merge branch 'main' into feature", "mergify[bot]", "37929162+mergify[bot]@users.noreply.github.com");
        }
        git.checkout().setName("main").call();
        RevCommit merge = mergeAs("feature", "Merge pull request #10", "mergify[bot]", "37929162+mergify[bot]@users.noreply.github.com");

        assertEquals("dev@corp.com", JGit.creditedAuthor(repository, merge).getEmailAddress());
    }
    /**
     * a genuine tie goes to whoever opened the branch, never to the merge author: the credit must not depend on the
     * author filter, or the index and the analysis would judge the same merge differently.
     */
    @Test
    void aGenuineTieCreditsTheBranchsFirstAuthor() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("a.txt", "x", "one", "First", "first@corp.com");
        commitAs("b.txt", "x", "two", "Second", "second@corp.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("feature", "Merge pull request #8", "CI Bot", "bot@ci.com");

        assertEquals("first@corp.com", JGit.creditedAuthor(repository, merge).getEmailAddress());
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
    /** a bot merging its own bot-authored branch is credited to the bot, so the default exclusion applies to it. */
    @Test
    void botMergeOfBotWorkIsCreditedToTheBot() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("deps").call();
        git.checkout().setName("deps").call();
        commitAs("d1.txt", "1", "bump a dependency", "dependabot[bot]", "dependabot[bot]@users.noreply.github.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("deps", "Merge pull request #5", "CI Bot", "bot@ci.com");

        assertEquals("dependabot[bot]@users.noreply.github.com", JGit.creditedAuthor(repository, merge).getEmailAddress());
    }
    /** a bot-dominated side branch with one small human follow-up commit is the bot's work, whoever merges it. */
    @Test
    void botDominatedSideBranchWithAHumanFollowUpIsCreditedToTheBot() throws Exception {
        commitAs("base.txt", "0", "base", "Maintainer", "maintainer@corp.com");
        git.branchCreate().setName("seer").call();
        git.checkout().setName("seer").call();
        commitAs("s1.txt", "1", "fix: handle null", "sentry[bot]", "39604003+sentry[bot]@users.noreply.github.com");
        commitAs("s2.txt", "2", "add test", "sentry[bot]", "39604003+sentry[bot]@users.noreply.github.com");
        commitAs("s3.txt", "3", "address review", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();
        commitAs("m.txt", "m", "mainline", "Maintainer", "maintainer@corp.com");
        RevCommit merge = mergeAs("seer", "Merge pull request #6", "Dev", "dev@corp.com");

        assertEquals("39604003+sentry[bot]@users.noreply.github.com", JGit.creditedAuthor(repository, merge).getEmailAddress());
    }
    /** a plain commit and an octopus merge have no single side branch, so they keep their own author. */
    @Test
    void nonMergeAndOctopusKeepTheirOwnAuthor() throws Exception {
        RevCommit base = commitAs("a.txt", "0", "generated", "Release Bot", "release-bot@corp.com");
        assertEquals("release-bot@corp.com", JGit.creditedAuthor(repository, base).getEmailAddress());

        git.branchCreate().setName("f1").call();
        git.checkout().setName("f1").call();
        RevCommit side1 = commitAs("b.txt", "1", "side 1", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();
        git.branchCreate().setName("f2").call();
        git.checkout().setName("f2").call();
        RevCommit side2 = commitAs("c.txt", "2", "side 2", "Dev", "dev@corp.com");
        git.checkout().setName("main").call();

        RevCommit octopus = rawMerge(List.of(base, side1, side2), "octopus merge");
        assertEquals("octo@corp.com", JGit.creditedAuthor(repository, octopus).getEmailAddress());
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
