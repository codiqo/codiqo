package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JacocoExecFilesTest {
    private static final long FIRST_ID = 0x1234L;
    private static final long SECOND_ID = 0x5678L;

    @TempDir
    Path tempDir;

    @Test
    void aWholeFileIsReadWhole() throws IOException {
        Path exec = Files.write(tempDir.resolve("jacoco.exec"), twoClasses());
        ExecFileLoader loader = new ExecFileLoader();

        assertTrue(JacocoExecFiles.load(loader, exec.toFile()));

        assertEquals(2, loader.getExecutionDataStore().getContents().size());
    }
    /** a test JVM killed in the middle of a write leaves the last block cut: the blocks before it are kept */
    @Test
    void aFileCutInsideItsLastBlockKeepsTheBlocksBeforeIt() throws IOException {
        byte[] whole = twoClasses();
        Path exec = Files.write(tempDir.resolve("jacoco.exec"), Arrays.copyOf(whole, whole.length - 3));
        ExecFileLoader loader = new ExecFileLoader();

        assertFalse(JacocoExecFiles.load(loader, exec.toFile()));

        assertTrue(loader.getExecutionDataStore().contains("app/First"));
        assertFalse(loader.getExecutionDataStore().contains("app/Second"));
    }
    /** a test JVM of the same module that appended after the cut makes the rest read as garbage: what came before is kept */
    @Test
    void aFileCutInTheMiddleAndAppendedToKeepsTheBlocksBeforeTheCut() throws IOException {
        byte[] first = twoClasses();
        byte[] second = twoClasses();
        byte[] corrupt = new byte[first.length - 3 + second.length];
        System.arraycopy(first, 0, corrupt, 0, first.length - 3);
        System.arraycopy(second, 0, corrupt, first.length - 3, second.length);
        Path exec = Files.write(tempDir.resolve("jacoco.exec"), corrupt);
        ExecFileLoader loader = new ExecFileLoader();

        assertFalse(JacocoExecFiles.load(loader, exec.toFile()));

        assertTrue(loader.getExecutionDataStore().contains("app/First"));
    }
    /** each file is read on its own and merged: one cut file does not touch what the loader already holds */
    @Test
    void aCutFileAddsToWhatTheLoaderHolds() throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        assertTrue(JacocoExecFiles.load(loader, Files.write(tempDir.resolve("whole.exec"), twoClasses()).toFile()));
        byte[] whole = twoClasses();

        assertFalse(JacocoExecFiles.load(loader, Files.write(tempDir.resolve("cut.exec"), Arrays.copyOf(whole, whole.length - 3)).toFile()));

        assertEquals(2, loader.getExecutionDataStore().getContents().size());
    }
    @Test
    void aFileThatIsNotAnExecFileStillFails() throws IOException {
        Path exec = Files.writeString(tempDir.resolve("jacoco.exec"), "not coverage", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> JacocoExecFiles.load(new ExecFileLoader(), exec.toFile()));
    }
    private static byte[] twoClasses() throws IOException {
        ByteArrayOutputStream toReturn = new ByteArrayOutputStream();
        ExecutionDataWriter writer = new ExecutionDataWriter(toReturn);
        writer.visitSessionInfo(new SessionInfo("probe", 1L, 2L));
        writer.visitClassExecution(new ExecutionData(FIRST_ID, "app/First", new boolean[] { true, false }));
        writer.visitClassExecution(new ExecutionData(SECOND_ID, "app/Second", new boolean[] { true, true, true, false }));
        return toReturn.toByteArray();
    }
}
