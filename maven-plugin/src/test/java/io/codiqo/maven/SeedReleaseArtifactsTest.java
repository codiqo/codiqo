package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;

/** the private local repository of a time-machine build is seeded with the host repository's releases only */
class SeedReleaseArtifactsTest {
    private static final String RELEASE = "org/example/lib/1.0/lib-1.0.jar";
    private static final String RELEASE_SOURCES = "org/example/lib/1.0/lib-1.0-sources.jar";
    private static final String RELEASE_BOOKKEEPING = "org/example/lib/1.0/_remote.repositories";
    private static final String METADATA = "org/example/lib/maven-metadata-central.xml";
    private static final String SNAPSHOT = "org/example/lib/2.0-SNAPSHOT/lib-2.0-SNAPSHOT.jar";

    @TempDir
    Path tempDir;

    private Path repository;

    @BeforeEach
    void fillRepository() throws Exception {
        repository = tempDir.resolve("cache/m2-repository");
        for (String path : new String[] { RELEASE, RELEASE_SOURCES, RELEASE_BOOKKEEPING, METADATA, SNAPSHOT }) {
            Path file = repository.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, path, StandardCharsets.UTF_8);
        }
    }
    @Test
    void releasesAreSeededAndEverythingMutableIsLeftBehind() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("private"));

        AbstractAnalyzeMojo.seedReleaseArtifacts(repository.toFile(), target.toFile(), new QuietLog());

        assertTrue(Files.isRegularFile(target.resolve(RELEASE)));
        assertFalse(Files.exists(target.resolve(RELEASE_SOURCES)), "a source JAR the language server must not see");
        assertFalse(Files.exists(target.resolve(RELEASE_BOOKKEEPING)));
        assertFalse(Files.exists(target.resolve(METADATA)));
        assertFalse(Files.exists(target.resolve(SNAPSHOT)), "snapshots are resolved again at the commit's own pins");
    }
    /**
     * A CI cache puts the repository in place as a symbolic link. Walked without following it, the link was visited
     * as one file and seeded as the private repository's own root, which failed every commit of a GitLab run with
     * FileAlreadyExistsException.
     */
    @Test
    void aRepositoryThatIsASymbolicLinkIsSeededFromWhatItPointsTo() throws Exception {
        Path link = Files.createSymbolicLink(Files.createDirectories(tempDir.resolve("home/.m2")).resolve("repository"), repository);
        Path target = Files.createDirectories(tempDir.resolve("private"));

        AbstractAnalyzeMojo.seedReleaseArtifacts(link.toFile(), target.toFile(), new QuietLog());

        assertTrue(Files.isRegularFile(target.resolve(RELEASE)), "the release behind the link is seeded");
        assertFalse(Files.exists(target.resolve(SNAPSHOT)));
    }
    private static final class QuietLog implements Log {
        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
