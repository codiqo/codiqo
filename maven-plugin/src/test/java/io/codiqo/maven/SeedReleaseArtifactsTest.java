package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;

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

        AbstractAnalyzeMojo.seedReleaseArtifacts(repository.toFile(), target.toFile(), new RecordingLog());

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

        AbstractAnalyzeMojo.seedReleaseArtifacts(link.toFile(), target.toFile(), new RecordingLog());

        assertTrue(Files.isRegularFile(target.resolve(RELEASE)), "the release behind the link is seeded");
        assertFalse(Files.exists(target.resolve(SNAPSHOT)));
    }
    /**
     * A link further down the repository: walked without following it, the link itself was linked into the private
     * repository, which then reached the host's snapshots and metadata through it.
     */
    @Test
    void aDirectoryLinkInsideTheRepositoryIsSeededFileByFile() throws Exception {
        Path host = Files.createDirectories(tempDir.resolve("home/.m2/repository"));
        Files.createSymbolicLink(host.resolve("org"), repository.resolve("org"));
        Path target = Files.createDirectories(tempDir.resolve("private"));

        AbstractAnalyzeMojo.seedReleaseArtifacts(host.toFile(), target.toFile(), new RecordingLog());

        assertFalse(Files.isSymbolicLink(target.resolve("org")), "the private repository holds no link back into the host's");
        assertTrue(Files.isRegularFile(target.resolve(RELEASE)));
        assertFalse(Files.exists(target.resolve(SNAPSHOT)));
        assertFalse(Files.exists(target.resolve(METADATA)));
    }
    /** seeding is best-effort: a cache link whose target was not restored seeds nothing, and does not fail the commit */
    @Test
    void aDanglingRepositoryLinkSeedsNothingWithoutFailing() throws Exception {
        Path link = Files.createSymbolicLink(Files.createDirectories(tempDir.resolve("home/.m2")).resolve("repository"), tempDir.resolve("not-restored"));
        Path target = Files.createDirectories(tempDir.resolve("private"));

        RecordingLog log = new RecordingLog();
        AbstractAnalyzeMojo.seedReleaseArtifacts(link.toFile(), target.toFile(), log);

        try (Stream<Path> seeded = Files.list(target)) {
            assertEquals(0, seeded.count());
        }
        assertTrue(log.warned(link.toString()), log.warnings.toString());
    }
    @Test
    void aDanglingArtifactLinkIsSkipped() throws Exception {
        Files.createSymbolicLink(repository.resolve("org/example/lib/1.0/lib-1.0.pom"), tempDir.resolve("gone.pom"));
        Path target = Files.createDirectories(tempDir.resolve("private"));

        RecordingLog log = new RecordingLog();
        AbstractAnalyzeMojo.seedReleaseArtifacts(repository.toFile(), target.toFile(), log);

        assertTrue(Files.isRegularFile(target.resolve(RELEASE)), "the rest of the repository is still seeded");
        assertFalse(Files.exists(target.resolve("org/example/lib/1.0/lib-1.0.pom"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(log.warned("lib-1.0.pom"), log.warnings.toString());
    }
    private static final class RecordingLog implements Log {
        private final List<String> warnings = Lists.newArrayList();

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
            if (level == Level.WARN) {
                warnings.add(message);
            }
        }
        private boolean warned(String text) {
            return warnings.stream().anyMatch(warning -> Strings.CS.contains(warning, text));
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
