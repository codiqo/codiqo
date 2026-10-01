package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;

class ProgressStageTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsAStageThatSucceededWithItsDetail() throws IOException {
        RunArgs args = argsWritingTo(tempDir.resolve("progress.tsv"));

        try (ProgressStage stage = ProgressStage.start(args, "cpd")) {
            stage.detail("peak heap 5 GB/8 GB");
            stage.succeeded();
        }

        List<String[]> lines = read(args);
        assertEquals(List.of("STAGE_STARTED", "cpd"), List.of(lines.get(0)).subList(1, 3));
        assertEquals("STAGE_FINISHED", lines.get(1)[1]);
        assertEquals("cpd", lines.get(1)[2]);
        assertTrue(Long.parseLong(lines.get(1)[3]) >= 0);
        assertEquals("peak heap 5 GB/8 GB", lines.get(1)[4]);
    }
    @Test
    void recordsAStageLeftByAnExceptionAsFailed() throws IOException {
        RunArgs args = argsWritingTo(tempDir.resolve("progress.tsv"));

        assertThrows(IllegalStateException.class, () -> {
            try (ProgressStage stage = ProgressStage.start(args, "coverage")) {
                throw new IllegalStateException("no exec file");
            }
        });

        assertEquals("STAGE_FAILED", read(args).get(1)[1]);
    }
    @Test
    void writesNothingWithoutAProgressFile() {
        try (ProgressStage stage = ProgressStage.start(new RunArgs(), "index")) {
            stage.succeeded();
        }

        assertEquals(0, tempDir.toFile().list().length);
    }
    private static RunArgs argsWritingTo(Path file) {
        RunArgs toReturn = new RunArgs();
        toReturn.setBuildProgressFile(file.toFile());
        return toReturn;
    }
    private static List<String[]> read(RunArgs args) throws IOException {
        return Files.readAllLines(args.getBuildProgressFile().toPath(), StandardCharsets.UTF_8).stream()
                .map(line -> line.split("\t", -1))
                .toList();
    }
}
