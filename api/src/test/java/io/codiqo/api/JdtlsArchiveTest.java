package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.util.DownloadFailedException;

class JdtlsArchiveTest {
    private static final String ARCHIVE = "jdt-language-server-1.63.0-202610141200.tar.gz";
    /** the opening of what download.eclipse.org served for every path, latest.txt included, during its outage */
    private static final String SHUTDOWN_PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>Temporary shutdown.</title>
            """;

    /** nothing listens on download.eclipse.org in this test: the default build comes from codiqo's own release */
    @Test
    void theDefaultVersionIsCodiqosMirroredBuild() {
        RunArgs args = new RunArgs();

        assertEquals(RunArgs.JDTLS_MIRRORED_ARCHIVE, args.resolveJdtlsArchiveName());
        assertEquals("https://github.com/codiqo/codiqo/releases/download/jdtls-1.63.0/jdt-language-server-1.63.0-202610191322.tar.gz",
                args.jdtlsArchiveUrl().toString());
        assertEquals(Optional.of(RunArgs.JDTLS_MIRRORED_SHA256), args.jdtlsArchiveSha256());
        assertEquals(RunArgs.JDTLS_MIRRORED_VERSION, args.effectiveJdtlsVersion());
    }
    @Test
    void anotherVersionComesFromEclipseWithoutADigest() {
        RunArgs args = new RunArgs();
        args.setJdtlsVersion("1.62.0");
        args.setJdtlsArchiveName("jdt-language-server-1.62.0-202610131109.tar.gz");

        assertEquals("https://download.eclipse.org/jdtls/milestones/1.62.0/jdt-language-server-1.62.0-202610131109.tar.gz", args.jdtlsArchiveUrl().toString());
        assertEquals(Optional.empty(), args.jdtlsArchiveSha256());
    }
    @Test
    void theSnapshotsChannelIsNeverMirrored() {
        RunArgs args = new RunArgs();
        args.setJdtlsUseSnapshot(true);
        args.setJdtlsArchiveName("jdt-language-server-1.64.0-202610211200.tar.gz");

        assertEquals("https://download.eclipse.org/jdtls/snapshots/jdt-language-server-1.64.0-202610211200.tar.gz", args.jdtlsArchiveUrl().toString());
        assertEquals(Optional.empty(), args.jdtlsArchiveSha256());
    }
    @Test
    void latestNamesOneArchive() throws Exception {
        assertEquals(ARCHIVE, RunArgs.jdtlsArchiveName(latest(), ARCHIVE + "\n"));
    }
    @Test
    void aPageInsteadOfAnArchiveNameIsAFailedDownload() throws Exception {
        DownloadFailedException err = assertThrows(DownloadFailedException.class, () -> RunArgs.jdtlsArchiveName(latest(), SHUTDOWN_PAGE));

        assertTrue(err.getMessage().startsWith(latest() + " names no language-server archive: <!DOCTYPE html>"), err.getMessage());
        assertTrue(err.getMessage().length() < 300, err.getMessage());
    }
    @Test
    void anEmptyLatestIsAFailedDownload() {
        assertThrows(DownloadFailedException.class, () -> RunArgs.jdtlsArchiveName(latest(), ""));
    }
    /** nothing listens on download.eclipse.org in this test: a cached build of the pinned milestone needs no lookup */
    @Test
    void aCachedBuildOfThePinnedMilestoneIsUsedWithoutAsking(@TempDir Path cache) throws Exception {
        Files.createFile(cache.resolve("jdt-language-server-1.63.0-202610011200.tar.gz"));
        Files.createFile(cache.resolve(ARCHIVE));
        Files.createFile(cache.resolve("jdt-language-server-1.63.0-202610201200.tar.gz.tmp"));
        Files.createFile(cache.resolve("jdt-language-server-1.62.0-202610301200.tar.gz"));

        assertEquals(ARCHIVE, new RunArgs().pinnedJdtlsArchiveName(cache));
    }
    private static URL latest() throws Exception {
        return new RunArgs().jdtlsBaseUrl().addPathSegment("latest.txt").build().url();
    }
}
