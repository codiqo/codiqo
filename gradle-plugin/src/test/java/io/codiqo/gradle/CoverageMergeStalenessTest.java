package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.jacoco.core.data.ExecutionDataWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;
import io.codiqo.gradle.model.ModuleData;

/**
 * The merged exec file must inherit the OLDEST contributing part's timestamp. JavaLanguageSpec.captureJacocoCoverage
 * aborts when the coverage file predates the project's latest modification, and that guard is the only thing standing
 * between a leftover part from a previous checkout and one commit being scored with another commit's coverage.
 */
class CoverageMergeStalenessTest {
    private static final String JACOCO_DIR = "jacoco";
    private static final String MERGED_EXEC = "codiqo.exec";
    private static final int PART_AGE_DAYS = 3;

    @TempDir
    Path tempDir;

    @Test
    void mergedFileInheritsTheOldestPartTimestamp() throws Exception {
        File jacocoDir = tempDir.resolve(JACOCO_DIR).toFile();
        assertTrue(jacocoDir.mkdirs());

        long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(PART_AGE_DAYS);
        long recent = System.currentTimeMillis();

        writeEmptyExec(new File(jacocoDir, AnalysisEngine.EXEC_PART_PREFIX + "test.exec"), old);
        writeEmptyExec(new File(jacocoDir, AnalysisEngine.EXEC_PART_PREFIX + "integrationTest.exec"), recent);

        File merged = new File(jacocoDir, MERGED_EXEC);
        AnalysisEngine.mergeCoverageParts(moduleAt(merged), NoopLog.INSTANCE);

        assertTrue(merged.isFile(), "merge did not produce " + merged);
        assertEquals(old, merged.lastModified(),
                "merged exec must carry the oldest part's time so the staleness guard can still fire");
    }
    @Test
    void mergeIsSkippedWhenNoPartsExist() throws Exception {
        File jacocoDir = tempDir.resolve(JACOCO_DIR).toFile();
        assertTrue(jacocoDir.mkdirs());

        File merged = new File(jacocoDir, MERGED_EXEC);
        AnalysisEngine.mergeCoverageParts(moduleAt(merged), NoopLog.INSTANCE);

        assertFalse(merged.exists(), "a module whose tests never ran must not get an empty exec file");
    }
    private static ModuleData moduleAt(File merged) {
        ModuleData toReturn = new ModuleData();
        toReturn.setArtifactId("demo");
        toReturn.setCoveragePath(merged.getAbsolutePath());
        return toReturn;
    }
    private static void writeEmptyExec(File file, long lastModified) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            new ExecutionDataWriter(out);
        }
        assertTrue(file.setLastModified(lastModified));
    }
    private static class NoopLog implements Log {
        private static final Log INSTANCE = new NoopLog();

        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public void log(Level level, String message, Object... formatArgs) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
