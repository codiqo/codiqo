package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitBranchAndChangesTest {
    @TempDir
    Path tempDir;

    private Git git;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch("dev").setDirectory(tempDir.toFile()).call();
    }
    @AfterEach
    void closeRepo() {
        git.close();
    }
    /** a CI checkout of one commit is detached: its SHA must never be reported as the project's default branch */
    @Test
    void aDetachedHeadWithoutADefaultNamesNoBranch() throws Exception {
        RevCommit first = commit("src/A.java", "class A {}");
        commit("src/B.java", "class B {}");

        assertEquals(Optional.of("dev"), JGit.currentBranchOrDefault(git.getRepository()));

        git.checkout().setName(first.getName()).call();
        assertEquals(Optional.empty(), JGit.currentBranchOrDefault(git.getRepository()));
    }
    /** the names a commit changed against its first parent, and the whole tree for a root commit */
    @Test
    void theChangedFileNamesAreTheFirstParentDelta() throws Exception {
        RevCommit root = commit("src/A.java", "class A {}");
        commit(".github/workflows/ci.yml", "on: push");
        RevCommit deletion = deleteAndCommit("src/A.java");

        assertEquals(List.of("A.java"), JGit.changedFileNames(git.getRepository(), root.getName()));
        assertEquals(List.of("ci.yml"), JGit.changedFileNames(git.getRepository(), git.getRepository().resolve("HEAD~1").getName()));
        assertEquals(List.of("A.java"), JGit.changedFileNames(git.getRepository(), deletion.getName()));
    }
    private RevCommit commit(String path, String content) throws Exception {
        Path file = tempDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("add " + path).setSign(false).call();
    }
    private RevCommit deleteAndCommit(String path) throws Exception {
        git.rm().addFilepattern(path).call();
        return git.commit().setMessage("remove " + path).setSign(false).call();
    }
}
