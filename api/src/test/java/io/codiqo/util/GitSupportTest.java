package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSupportTest {
    private static final String FORTY_HEX = StringUtils.repeat('a', 40);

    @Test
    void openRepositoryResolvesRepoFromBaseDirectory(@TempDir Path tempDir) throws Exception {
        try (Git ignored = Git.init().setDirectory(tempDir.toFile()).call()) {
            try (Repository repo = JGit.openRepository(tempDir.toFile())) {
                assertNotNull(repo.getDirectory(),
                        "openRepository must point at the .git directory inside the given base directory");
                assertEquals(tempDir.toRealPath().toFile(),
                        repo.getWorkTree().toPath().toRealPath().toFile(),
                        "the returned repository must have its work tree at the given base directory");
            }
        }
    }

    @Test
    void detectsStandardRevertFooter() {
        String message = "Revert \"Broken feature\"\n\nThis reverts commit " + FORTY_HEX + ".\n";

        Optional<String> sha = JGit.detectRevertedSha(message);

        assertTrue(sha.isPresent());
        assertEquals(FORTY_HEX, sha.get());
    }
    @Test
    void returnsEmptyForPlainMessage() {
        assertFalse(JGit.detectRevertedSha("Add new feature").isPresent());
    }
    @Test
    void returnsEmptyFor39CharHex() {
        String message = "This reverts commit " + StringUtils.repeat('a', 39) + ".";

        assertFalse(JGit.detectRevertedSha(message).isPresent());
    }
    @Test
    void returnsEmptyForRevertWithoutTrailingPeriod() {
        String message = "This reverts commit " + FORTY_HEX;

        assertFalse(JGit.detectRevertedSha(message).isPresent());
    }
    @Test
    void capturesFirstShaWhenMultipleReverts() {
        String first = StringUtils.repeat('a', 40);
        String second = StringUtils.repeat('b', 40);
        String message = "Revert chain\n\nThis reverts commit " + first + ".\nThis reverts commit " + second + ".\n";

        Optional<String> sha = JGit.detectRevertedSha(message);

        assertTrue(sha.isPresent());
        assertEquals(first, sha.get());
    }
    @Test
    void acceptsRevertSentenceMidMessage() {
        String message = "Some prefix — This reverts commit " + FORTY_HEX + ". Trailing text.";

        Optional<String> sha = JGit.detectRevertedSha(message);

        assertTrue(sha.isPresent());
        assertEquals(FORTY_HEX, sha.get());
    }
}
