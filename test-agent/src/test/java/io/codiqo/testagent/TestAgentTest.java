package io.codiqo.testagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestAgentTest {
    private static final StackTraceElement SOCKET_READ = frame("sun.nio.ch.NioSocketImpl", "park");
    private static final StackTraceElement STREAM_READ = frame("java.net.Socket$SocketInputStream", "read");
    private static final StackTraceElement TEST_METHOD = frame("app.HangTest", "hangsInARead");
    private static final StackTraceElement ACCESSOR = frame("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke");
    private static final StackTraceElement METHOD_INVOKE = frame("java.lang.reflect.Method", "invoke");
    private static final StackTraceElement JUNIT = frame("org.junit.platform.commons.util.ReflectionUtils", "invokeMethod");
    private static final StackTraceElement BOOTER = frame("org.apache.maven.surefire.booter.ForkedBooter", "main");

    @TempDir
    Path tempDir;

    /** a test stuck in the JDK: the frame JUnit invoked is also the topmost one outside the JDK */
    @Test
    void aTestStuckInTheJdkIsNamedByItsOwnFrame() {
        Map<Thread, StackTraceElement[]> stacks = new LinkedHashMap<>();
        stacks.put(new Thread("main"), new StackTraceElement[] { SOCKET_READ, STREAM_READ, TEST_METHOD, ACCESSOR, METHOD_INVOKE, JUNIT, BOOTER });

        assertEquals(List.of("main\tapp.HangTest.hangsInARead(HangTest.java:10)\tapp.HangTest.hangsInARead(HangTest.java:10)"), TestAgent.describe(stacks));
    }
    /** a test stuck in a library it calls: the invoked frame names the test, the top frame where it hangs */
    @Test
    void aTestStuckInALibraryNamesBothTheTestAndTheLibrary() {
        StackTraceElement library = frame("okhttp3.internal.http2.Http2Stream", "takeHeaders");
        Map<Thread, StackTraceElement[]> stacks = new LinkedHashMap<>();
        stacks.put(new Thread("Test worker"), new StackTraceElement[] { SOCKET_READ, library, TEST_METHOD, ACCESSOR, METHOD_INVOKE, JUNIT });

        assertEquals(List.of("Test worker\tapp.HangTest.hangsInARead(HangTest.java:10)\tokhttp3.internal.http2.Http2Stream.takeHeaders(Http2Stream.java:10)"), TestAgent.describe(stacks));
    }
    @Test
    void threadsThatRunNoTestAndDaemonThreadsAreLeftOut() {
        Thread daemon = new Thread("junit-timeout-watcher");
        daemon.setDaemon(true);
        Map<Thread, StackTraceElement[]> stacks = new LinkedHashMap<>();
        stacks.put(new Thread("app-worker"), new StackTraceElement[] { SOCKET_READ, frame("app.Server", "serve") });
        stacks.put(daemon, new StackTraceElement[] { SOCKET_READ, TEST_METHOD, JUNIT });

        assertEquals(List.of(), TestAgent.describe(stacks));
    }
    /** this test runs under JUnit, so the agent started here records this very thread */
    @Test
    void theAgentRecordsTheThreadRunningTheTests() throws Exception {
        Path records = tempDir.resolve("records");
        TestAgent.premain(TestAgent.argument(records.toString(), 1), null);

        File record = awaitRecord(records);
        List<String> lines = Files.readAllLines(record.toPath(), StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(Thread.currentThread().getName() + TestAgent.FIELD_SEPARATOR)), lines.toString());
    }
    /** an argument the agent cannot read must leave the test JVM running, with the agent inactive */
    @Test
    void anUnreadableArgumentLeavesTheAgentInactive() {
        TestAgent.premain("interval=abc", null);
        TestAgent.premain(null, null);
    }
    private static File awaitRecord(Path directory) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            File[] records = directory.toFile().listFiles((dir, name) -> name.startsWith(TestAgent.RECORD_PREFIX) && name.endsWith(TestAgent.RECORD_SUFFIX));
            if (Objects.nonNull(records) && records.length > 0) {
                return records[0];
            }
            Thread.sleep(100);
        }
        throw new IOException("no record in " + directory);
    }
    private static StackTraceElement frame(String className, String method) {
        return new StackTraceElement(className, method, className.substring(className.lastIndexOf('.') + 1) + ".java", 10);
    }
}
