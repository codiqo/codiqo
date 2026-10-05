package io.codiqo.submit.hotspots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.submit.hotspots.GitChurn.FileChurn;

class GitChurnTest {
    @TempDir
    Path dir;

    @Test
    void historyFollowsARenameAndSkipsMerges() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            write("Foo.java", "class Foo { int a; }");
            commit(git, "add foo");

            write("Foo.java", "class Foo { int a; int b; }");
            commit(git, "Fix the bug in foo");

            Files.move(dir.resolve("Foo.java"), dir.resolve("Bar.java"));
            git.rm().addFilepattern("Foo.java").call();
            commit(git, "rename foo to bar");

            git.branchCreate().setName("side").call();
            git.checkout().setName("side").call();
            write("Side.java", "class Side {}");
            commit(git, "side work");
            git.checkout().setName("main").call();
            write("Bar.java", "class Bar { int a; int b; int c; }");
            commit(git, "grow bar");
            write("Bar.java", "class Bar { int a; int b; int c; int d; }");
            commit(git, "add debug logging and a key prefix");
            git.merge().include(git.getRepository().resolve("side")).setMessage("merge side, fixing nothing").call();
            RevCommit tip = git.getRepository().parseCommit(git.getRepository().resolve("HEAD"));

            Map<String, FileChurn> churn = GitChurn.collect(git.getRepository(), tip.getName());

            assertEquals(new FileChurn(5, 5, 1), churn.get("Bar.java"));
            assertEquals(new FileChurn(1, 1, 0), churn.get("Side.java"));
            assertFalse(churn.containsKey("Foo.java"));
        }
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }
    private static void commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
    }
}
