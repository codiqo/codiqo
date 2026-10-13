package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Sets;

class WorkingTreeCommitTest {
    @TempDir
    Path workTree;

    @Test
    void theWorkingTreeBecomesACommitOfHeadAndNothingTheDeveloperHasChanges() throws Exception {
        try (Git git = Git.init().setInitialBranch("main").setDirectory(workTree.toFile()).call()) {
            write(".gitignore", "build/\n");
            write("Foo.java", "class Foo {}\n");
            git.add().addFilepattern(".").call();
            RevCommit head = git.commit().setMessage("first").setAuthor("dev", "dev@example.test").setCommitter("dev", "dev@example.test").call();

            write("Foo.java", "class Foo { int x; }\n");
            write("Bar.java", "class Bar {}\n");
            write("build/Out.java", "class Out {}\n");
            Status before = git.status().call();

            String sha = WorkingTreeCommit.create(workTree, true);

            try (RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit snapshot = walk.parseCommit(ObjectId.fromString(sha));
                assertEquals(head, snapshot.getParent(0), "the changes are the commit's diff against HEAD");
                assertEquals(Set.of(".gitignore", "Foo.java", "Bar.java"), paths(git, snapshot), "untracked files are in it, ignored ones are not");
                assertEquals("class Foo { int x; }\n", content(git, snapshot, "Foo.java"));
            }
            assertEquals(head, git.getRepository().parseCommit(git.getRepository().resolve("HEAD")), "HEAD stays");
            Status after = git.status().call();
            assertEquals(before.getModified(), after.getModified());
            assertEquals(before.getUntracked(), after.getUntracked(), "nothing is added to the developer's index");
            assertTrue(after.getAdded().isEmpty());
        }
    }
    @Test
    void untrackedFilesAreLeftOutWhenTheAnalysisLeavesThemOut() throws Exception {
        try (Git git = Git.init().setInitialBranch("main").setDirectory(workTree.toFile()).call()) {
            write("Foo.java", "class Foo {}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first").setAuthor("dev", "dev@example.test").setCommitter("dev", "dev@example.test").call();
            write("Bar.java", "class Bar {}\n");

            String sha = WorkingTreeCommit.create(workTree, false);

            try (RevWalk walk = new RevWalk(git.getRepository())) {
                assertFalse(paths(git, walk.parseCommit(ObjectId.fromString(sha))).contains("Bar.java"));
            }
        }
    }
    private void write(String path, String content) throws Exception {
        Path file = workTree.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
    private static Set<String> paths(Git git, RevCommit commit) throws Exception {
        Set<String> toReturn = Sets.newHashSet();
        try (TreeWalk walk = new TreeWalk(git.getRepository())) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                toReturn.add(walk.getPathString());
            }
        }
        return toReturn;
    }
    private static String content(Git git, RevCommit commit, String path) throws Exception {
        try (TreeWalk walk = TreeWalk.forPath(git.getRepository(), path, commit.getTree())) {
            return new String(git.getRepository().open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        }
    }
}
