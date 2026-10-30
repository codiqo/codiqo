package io.codiqo.jdtls;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class RuntimeWithoutSourcesTest {
    @TempDir
    Path dir;

    @Test
    void namesTheFeatureReleaseAndTheRealPathBehindASymlink() throws IOException {
        Path jdk = Files.createDirectory(dir.resolve("temurin-25"));
        Path toolCache = Files.createSymbolicLink(dir.resolve("tool-cache-25"), jdk);

        Map<String, Object> runtime = JdtLspProcess.runtimeWithoutSources(toolCache.toFile(), 25, dir);

        assertEquals("JavaSE-25", runtime.get("name"));
        assertEquals(jdk.toRealPath().toString(), runtime.get("path"));
        assertEquals(true, runtime.get("default"));
    }
    @Test
    void sourcesIsAnArchiveWithNoEntries() throws IOException {
        Map<String, Object> runtime = JdtLspProcess.runtimeWithoutSources(dir.toFile(), Runtime.version().feature(), dir);

        try (ZipFile zip = new ZipFile(new File((String) runtime.get("sources")))) {
            assertEquals(0, zip.size());
        }
    }
}
