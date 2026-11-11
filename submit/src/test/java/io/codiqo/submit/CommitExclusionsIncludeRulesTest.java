package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisExcludeCategory;

/** the include-rules decide from git alone, before the commit is reviewed, as the index step does */
class CommitExclusionsIncludeRulesTest {
    private static final PersonIdent AUTHOR = new PersonIdent("Author", "author@example.com");

    @TempDir
    Path tempDir;

    private Git git;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
    }
    @AfterEach
    void closeRepo() {
        git.close();
    }
    @Test
    void aCommitOnABranchTheRulesLeaveOutIsFiltered() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A {}"));
        args.setIncludeBranches("release/.*");

        assertEquals(AnalysisExcludeCategory.FILTERED_BY_RULES, CommitExclusions.beforeAnalysis(args, IndexedCommits.NONE).orElseThrow().getCategory());
    }
    @Test
    void aCommitByAnAuthorTheRulesLeaveOutIsFiltered() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A {}"));
        args.setIncludeAuthorEmails("someone-else@example.com");

        assertEquals(AnalysisExcludeCategory.FILTERED_BY_RULES, CommitExclusions.beforeAnalysis(args, IndexedCommits.NONE).orElseThrow().getCategory());
    }
    /** both author rules take precedence over the analysable-file gate, which runs only once the rules let a commit in */
    @Test
    void aCommitWithNothingAnalysableIsFilteredByTheRulesFirst() throws Exception {
        commit("src/main/java/a/A.java", "class A {}");
        RunArgs args = args(commit("README.md", "# A"));
        args.setIncludeAuthorEmails("someone-else@example.com");

        assertEquals(AnalysisExcludeCategory.FILTERED_BY_RULES, CommitExclusions.beforeAnalysis(args, IndexedCommits.NONE).orElseThrow().getCategory());
    }
    @Test
    void aCommitTheRulesKeepIsAnalysed() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A {}"));
        git.branchCreate().setName("release/1.1").call();
        args.setIncludeBranches("release/.*");
        args.setIncludeAuthorEmails(AUTHOR.getEmailAddress());

        assertTrue(CommitExclusions.beforeAnalysis(args, IndexedCommits.NONE).isEmpty());
    }
    private RunArgs args(RevCommit commit) {
        RunArgs toReturn = new RunArgs();
        toReturn.setGit(git.getRepository());
        toReturn.setCommitId(commit.getName());
        return toReturn;
    }
    private RevCommit commit(String path, String content) throws Exception {
        Path file = tempDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("add " + path).setAuthor(AUTHOR).setCommitter(AUTHOR).setSign(false).call();
    }
}
