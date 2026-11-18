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
    /** every JVM started with the agent writes a record, so a server a test started and killed names no test and holds nothing */
    @Test
    void aRecordThatNamesNoTestIsLeftOut() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        record(records, "");

        assertEquals(Optional.empty(), TestRecords.cutOff(List.of(records.toFile()), new NoopLog()));
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
    /** hidden-source mode keeps what happened and leaves out the code it names */
    @Test
    void theReasonWithoutWhereTheTestsWereLastSeenNamesNoCode() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        record(records, "main\t" + HUNG_TEST + "\t" + HUNG_TEST);

        String hidden = TestRecords.withoutLastSeen(TestRecords.cutOff(List.of(records.toFile()), new NoopLog()).orElseThrow());

        assertEquals("test JVMs that ended before their tests finished, as the test-run timeout or a crash ends one: 1. The test counts and coverage "
                + "cover only the tests that completed", hidden);
        assertEquals("12 of 20 executed tests failed", TestRecords.withoutLastSeen("12 of 20 executed tests failed"));
    }
    @Test
    void theTestRunCarriesTheCutOff() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        record(records, "main\t" + HUNG_TEST + "\t" + HUNG_TEST);

        TestRun run = TestRuns.read(List.of(tempDir.resolve("reports").toFile()), List.of(records.toFile()), new NoopLog());

        assertTrue(run.getCutOff().isPresent());
        assertEquals(Optional.empty(), TestRuns.read(List.of(tempDir.resolve("reports").toFile()), new NoopLog()).getCutOff());
    }
    /** a run the build's deadline stopped says so first, then where its tests were when a test JVM left a record */
    @Test
    void aRunTheDeadlineStoppedSaysWhatStoppedItAndWhereTheTestsWere() throws IOException {
        Path records = Files.createDirectories(tempDir.resolve("records"));
        record(records, "main\t" + HUNG_TEST + "\t" + HUNG_TEST);
        TestRun recorded = TestRuns.read(List.of(), List.of(records.toFile()), new NoopLog());
        TestRun unrecorded = TestRuns.read(List.of(), new NoopLog());

        String withRecords = TestRuns.cutShort(recorded, "the test run reached the build's deadline (PT35M)").getCutOff().orElseThrow();
        String withoutRecords = TestRuns.cutShort(unrecorded, "the test run reached the build's deadline (PT35M)").getCutOff().orElseThrow();

        assertTrue(withRecords.startsWith("the test run reached the build's deadline (PT35M). test JVMs that ended"), withRecords);
        assertTrue(withRecords.endsWith("the tests were last seen in main at " + HUNG_TEST), withRecords);
        assertEquals("the test run reached the build's deadline (PT35M), so the test counts and coverage cover only the tests that completed", withoutRecords);
    }
    private static void record(Path directory, String line) throws IOException {
        Files.writeString(directory.resolve(TestAgent.RECORD_PREFIX + "1" + TestAgent.RECORD_SUFFIX), line, StandardCharsets.UTF_8);
    }
}
