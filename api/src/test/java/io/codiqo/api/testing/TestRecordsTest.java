package io.codiqo.api.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.testagent.TestAgent;

class TestRecordsTest {
    private static final String HUNG_TEST = "app.HangTest.hangsInARead(HangTest.java:16)";

    @TempDir
    Path tempDir;

    /** the agent deletes its record when the JVM exits normally, so no record is a test run that finished */
    @Test
    void noRecordIsATestRunThatFinished() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        Files.writeString(records.resolve("TEST-app.HangTest.xml"), "<testsuite/>", StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), TestRecords.cutOff(List.of(records.toFile(), tempDir.resolve("missing").toFile()), new NoopLog()));
    }
    @Test
    void aRecordLeftBehindNamesTheTestThatNeverFinished() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        record(records, "main\t" + HUNG_TEST + "\t" + HUNG_TEST);

        String reason = TestRecords.cutOff(List.of(records.toFile()), new NoopLog()).orElseThrow();

        assertTrue(reason.contains("ended before their tests finished, as the test-run timeout or a crash ends one: 1."), reason);
        assertTrue(reason.endsWith("the tests were last seen in main at " + HUNG_TEST), reason);
    }
    /** a test stuck inside a library it calls: both the test and the library frame are named */
    @Test
    void aTestStuckInALibraryNamesTheLibraryToo() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        record(records, "Test worker\t" + HUNG_TEST + "\tokhttp3.internal.http2.Http2Stream.takeHeaders(Http2Stream.java:10)");

        String reason = TestRecords.cutOff(List.of(records.toFile()), new NoopLog()).orElseThrow();

        assertTrue(reason.endsWith("Test worker at " + HUNG_TEST + ", inside okhttp3.internal.http2.Http2Stream.takeHeaders(Http2Stream.java:10)"), reason);
    }
    @Test
    void aRecordWithNoTestThreadStillSaysTheRunWasCutOff() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        record(records, "");

        String reason = TestRecords.cutOff(List.of(records.toFile()), new NoopLog()).orElseThrow();

        assertTrue(reason.endsWith("no thread was running a test when it was last recorded"), reason);
    }
    /** -javaagent takes only a jar: run from a module's class output, the agent is left off rather than breaking the test JVM */
    @Test
    void theAgentArgumentNamesTheJarAndTheRecordDirectory() throws Exception {
        File agent = new File(TestAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File records = tempDir.resolve("records").toFile();

        Optional<String> argument = TestRecords.jvmArgument(records);

        assertEquals(agent.isFile(), argument.isPresent(), agent.toString());
        argument.ifPresent(value -> assertEquals("-javaagent:" + agent.getAbsolutePath() + "=" + TestAgent.argument(records.getAbsolutePath(), 60), value));
    }
    @Test
    void theTestRunCarriesTheCutOff() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        record(records, "main\t" + HUNG_TEST + "\t" + HUNG_TEST);

        TestRun run = TestRuns.read(List.of(tempDir.resolve("reports").toFile()), List.of(records.toFile()), new NoopLog());

        assertTrue(run.getCutOff().isPresent());
        assertEquals(Optional.empty(), TestRuns.read(List.of(tempDir.resolve("reports").toFile()), new NoopLog()).getCutOff());
    }
    private static void record(Path directory, String line) throws IOException {
        Files.writeString(directory.resolve(TestAgent.RECORD_PREFIX + "1" + TestAgent.RECORD_SUFFIX), line, StandardCharsets.UTF_8);
    }
}
