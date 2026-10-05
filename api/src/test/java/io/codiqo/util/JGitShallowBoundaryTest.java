package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.apache.commons.collections4.IterableUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitShallowBoundaryTest {
    @TempDir
    Path dir;

    @Test
    void aCloneFetchedFromAShallowCheckoutKeepsItsBoundary() throws Exception {
        File origin = dir.resolve("origin").toFile();
        try (Git git = Git.init().setDirectory(origin).setInitialBranch("main").call()) {
            for (int i = 0; i < 3; i++) {
                Files.writeString(origin.toPath().resolve("File.java"), "class File { int v = " + i + "; }", StandardCharsets.UTF_8);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("commit " + i).setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
            }
        }

        /** A depth-1 clone stands in for what a CI checkout with the default depth looks like. */
        File checkout = dir.resolve("checkout").toFile();
        try (Git shallow = Git.cloneRepository().setURI(origin.toURI().toString()).setDirectory(checkout).setDepth(1).call()) {
            Repository source = shallow.getRepository();
            Set<ObjectId> boundary = shallowCommits(source);
            assertEquals(1, boundary.size(), "precondition: the checkout is shallow");

            /** The analysis clone is made the way AnalyzeCommitMojo makes it: an empty repository plus a fetch. */
            try (Repository clone = new FileRepositoryBuilder().setGitDir(dir.resolve("clone/.git").toFile()).build()) {
                clone.create(false);
                try (Git cloneGit = Git.wrap(clone)) {
                    cloneGit.fetch().setRemote(checkout.toURI().toString()).setRefSpecs(new RefSpec("+refs/*:refs/*")).call();
                }
                assertTrue(shallowCommits(clone).isEmpty(), "a fetch alone loses the boundary");

                JGit.copyShallowBoundary(source, clone);

                assertEquals(boundary, shallowCommits(clone));
                try (RevWalk walk = new RevWalk(clone)) {
                    walk.markStart(walk.parseCommit(clone.resolve("refs/heads/main")));
                    assertEquals(1, IterableUtils.size(walk), "the walk stops at the boundary instead of failing on a missing parent");
                }
            }
        }
    }
    @Test
    void aFullHistoryLeavesNoBoundaryFile() throws Exception {
        File origin = dir.resolve("full").toFile();
        try (Git git = Git.init().setDirectory(origin).setInitialBranch("main").call()) {
            git.commit().setMessage("only").setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").setAllowEmpty(true).call();
            try (Repository clone = new FileRepositoryBuilder().setGitDir(dir.resolve("clone-full/.git").toFile()).build()) {
                clone.create(false);

                JGit.copyShallowBoundary(git.getRepository(), clone);

                assertFalse(new File(clone.getDirectory(), Constants.SHALLOW).exists());
            }
        }
    }

    private static Set<ObjectId> shallowCommits(Repository repo) throws Exception {
        try (ObjectReader reader = repo.newObjectReader()) {
            return Set.copyOf(reader.getShallowCommits());
        }
    }
}
