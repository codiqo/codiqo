package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.util.DownloadFailedException;

class JdtlsArchiveTest {
    private static final String ARCHIVE = "jdt-language-server-1.63.0-202610141200.tar.gz";
    /** the opening of what download.eclipse.org served for every path, latest.txt included, on 21 Oct 2026 */
    private static final String SHUTDOWN_PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>Temporary shutdown.</title>
            """;

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
