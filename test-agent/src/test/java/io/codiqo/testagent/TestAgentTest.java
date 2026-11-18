package io.codiqo.testagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.junit.codiqo.fake.FakeRunner;
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
    private static final String HUNG = "app.HangTest.hangsInARead(HangTest.java:10)";
    private static final Duration AWAIT = Duration.ofSeconds(20);

    @TempDir
    Path tempDir;

    /** a test stuck in the JDK: the frame JUnit invoked is also the topmost one outside the JDK */
    @Test
    void aTestStuckInTheJdkIsNamedByItsOwnFrame() {
        Map<Thread, StackTraceElement[]> stacks = new LinkedHashMap<>();
        stacks.put(new Thread("main"), new StackTraceElement[] { SOCKET_READ, STREAM_READ, TEST_METHOD, ACCESSOR, METHOD_INVOKE, JUNIT, BOOTER });

        assertEquals(List.of("main\t" + HUNG + "\t" + HUNG), TestAgent.describe(stacks));
    }
    /** a test stuck in a library it calls: the invoked frame names the test, the top frame where it hangs */
    @Test
    void aTestStuckInALibraryNamesBothTheTestAndTheLibrary() {
        StackTraceElement library = frame("okhttp3.internal.http2.Http2Stream", "takeHeaders");
        Map<Thread, StackTraceElement[]> stacks = new LinkedHashMap<>();
        stacks.put(new Thread("Test worker"), new StackTraceElement[] { SOCKET_READ, library, TEST_METHOD, ACCESSOR, METHOD_INVOKE, JUNIT });

        assertEquals(List.of("Test worker\t" + HUNG + "\tokhttp3.internal.http2.Http2Stream.takeHeaders(Http2Stream.java:10)"), TestAgent.describe(stacks));
    }
    /**
     * JUnit runs parallel tests on daemon ForkJoin workers, so a daemon thread inside a test counts; the main thread only
     * joining them, JUnit's timeout watcher and a thread that runs no test do not
     */
    @Test
    void aTestOnADaemonWorkerCountsAndThreadsWaitingInTheJdkDoNot() {
        Thread worker = new Thread("ForkJoinPool-1-worker-1");
        worker.setDaemon(true);
        Thread watcher = new Thread("junit-jupiter-timeout-watcher");
        watcher.setDaemon(true);
        StackTraceElement park = frame("jdk.internal.misc.Unsafe", "park");
        Map<Thread, StackTraceElement[]> stacks = new LinkedHashMap<>();
        stacks.put(worker, new StackTraceElement[] { SOCKET_READ, STREAM_READ, TEST_METHOD, ACCESSOR, METHOD_INVOKE, JUNIT });
        stacks.put(new Thread("main"), new StackTraceElement[] { park, frame("java.util.concurrent.ForkJoinTask", "join"), JUNIT, BOOTER });
        stacks.put(watcher, new StackTraceElement[] { park, frame("java.util.concurrent.DelayQueue", "take"), frame("org.junit.jupiter.engine.extension.TimeoutExtension", "watch") });
        stacks.put(new Thread("app-worker"), new StackTraceElement[] { SOCKET_READ, frame("app.Server", "serve") });

        assertEquals(List.of("ForkJoinPool-1-worker-1\t" + HUNG + "\t" + HUNG), TestAgent.describe(stacks));
    }
    /** this test runs under JUnit, so the agent started here records this very thread */
    @Test
    void theAgentRecordsTheThreadRunningTheTests() throws Exception {
        Path records = tempDir.resolve("records");
        try (TestAgent agent = TestAgent.start(TestAgent.argument(records.toString(), 1)).orElseThrow()) {
            String thread = Thread.currentThread().getName() + TestAgent.FIELD_SEPARATOR;
            assertTrue(await(() -> lines(records).stream().anyMatch(line -> line.startsWith(thread))), lines(records).toString());
        }
        assertEquals(List.of(), recordFiles(records), "closing the agent deletes its record");
    }
    /** an argument the agent cannot read must leave the test JVM running, with the agent inactive */
    @Test
    void anUnreadableArgumentLeavesTheAgentInactive() {
        assertEquals(Optional.empty(), TestAgent.start("interval=abc,dir=" + tempDir));
        assertEquals(Optional.empty(), TestAgent.start("dir=" + tempDir));
        assertEquals(Optional.empty(), TestAgent.start(null));
    }
    /** the directory runs to the end of the argument, so a comma in a checkout's path does not cut it short */
    @Test
    void aDirectoryWithACommaIsKeptWhole() throws Exception {
        Path records = tempDir.resolve("a,b").resolve("records");
        try (TestAgent agent = TestAgent.start(TestAgent.argument(records.toString(), 1)).orElseThrow()) {
            assertTrue(await(() -> recordFiles(records).size() == 1), records.toString());
        }
    }
    /**
     * JUnit 4's {@code @Test(timeout)} and the preemptive timeouts leave a timed-out test on a daemon thread and move on:
     * only a non-daemon thread inside a test framework, surefire's main thread or Gradle's worker, means the run is still going
     */
    @Test
    void onlyANonDaemonThreadInsideATestMeansTheRunIsStillGoing() {
        Thread abandoned = new Thread("Time-limited test");
        abandoned.setDaemon(true);
        Map<Thread, StackTraceElement[]> finished = new LinkedHashMap<>();
        finished.put(abandoned, new StackTraceElement[] { SOCKET_READ, STREAM_READ, TEST_METHOD, ACCESSOR, METHOD_INVOKE, JUNIT });
        finished.put(new Thread("main"), new StackTraceElement[] { frame("java.lang.Shutdown", "exit"), frame("java.lang.System", "exit"), BOOTER });
        Map<Thread, StackTraceElement[]> running = new LinkedHashMap<>(finished);
        running.put(new Thread("Test worker"), new StackTraceElement[] { SOCKET_READ, TEST_METHOD, ACCESSOR, METHOD_INVOKE, JUNIT });

        assertFalse(TestAgent.testsRunning(finished));
        assertTrue(TestAgent.testsRunning(running));
    }
    /** Gradle ends a timed-out test JVM with SIGTERM, which runs the shutdown hooks: a test still running keeps the record */
    @Test
    void aTestJvmEndedWhileATestRunsKeepsItsRecord() throws Exception {
        Path records = tempDir.resolve("records");
        Process child = child(records, "hangs");
        try {
            assertTrue(await(() -> lines(records).stream().anyMatch(line -> line.contains("app.ChildTests.hangs"))), lines(records).toString());

            child.destroy();
            assertTrue(child.waitFor(AWAIT.toSeconds(), TimeUnit.SECONDS));

            assertTrue(lines(records).stream().anyMatch(line -> line.startsWith("main\tapp.ChildTests.hangs(")), lines(records).toString());
        } finally {
            child.destroyForcibly();
        }
    }
    /** a JVM whose tests finished has no thread left inside one, so its record goes, even after records were written */
    @Test
    void aTestJvmWhoseTestsFinishedLeavesNoRecord() throws Exception {
        Path records = tempDir.resolve("records");
        Process child = child(records, "finishesAfterTwoRecords");
        try {
            assertTrue(child.waitFor(AWAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());

            assertEquals(List.of(), recordFiles(records));
        } finally {
            child.destroyForcibly();
        }
    }
    /** a JVM running FakeRunner, which stands for a test framework, with the agent recording every second */
    private Process child(Path records, String testMethod) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-javaagent:" + agentJar() + "=" + TestAgent.argument(records.toString(), 1),
                "-cp", System.getProperty("java.class.path"), FakeRunner.class.getName(), "app.ChildTests", testMethod)
                .redirectErrorStream(true)
                .redirectOutput(tempDir.resolve("child-" + testMethod + ".log").toFile())
                .start();
    }
    /** {@code -javaagent} takes only a jar, and the tests run before the module is packaged: the classes are jarred here */
    private Path agentJar() throws Exception {
        Path classes = Paths.get(TestAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path toReturn = tempDir.resolve("agent.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", TestAgent.class.getName());
        try (OutputStream file = Files.newOutputStream(toReturn); JarOutputStream jar = new JarOutputStream(file, manifest); Stream<Path> files = Files.walk(classes)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace(File.separatorChar, '/')));
                jar.write(Files.readAllBytes(path));
                jar.closeEntry();
            }
        }
        return toReturn;
    }
    private static List<String> lines(Path records) throws IOException {
        List<String> toReturn = new ArrayList<>();
        for (Path record : recordFiles(records)) {
            toReturn.addAll(Files.readAllLines(record, StandardCharsets.UTF_8));
        }
        return toReturn;
    }
    private static List<Path> recordFiles(Path records) throws IOException {
        if (Files.isDirectory(records)) {
            try (Stream<Path> files = Files.list(records)) {
                return files.filter(path -> path.getFileName().toString().startsWith(TestAgent.RECORD_PREFIX))
                        .filter(path -> path.getFileName().toString().endsWith(TestAgent.RECORD_SUFFIX))
                        .toList();
            }
        }
        return List.of();
    }
    private static boolean await(Check check) throws Exception {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.holds()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
    private static StackTraceElement frame(String className, String method) {
        return new StackTraceElement(className, method, className.substring(className.lastIndexOf('.') + 1) + ".java", 10);
    }

    @FunctionalInterface
    private interface Check {
        boolean holds() throws Exception;
    }
}
