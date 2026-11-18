package io.codiqo.testagent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The agent codiqo loads with {@code -javaagent} into every test JVM of the analysed project. Every interval it has
 * JaCoCo write the coverage collected since its last write, and records where the threads running tests are, in a file
 * of its own that it deletes when the JVM exits normally.
 *
 * <p>A test JVM killed by its timeout runs no shutdown hook: surefire ends a timed-out fork with {@code Runtime.halt},
 * and JaCoCo, which writes its data at exit, then left an empty file, so the commit lost the coverage of every test
 * that had finished. The writes during the run keep that coverage, and the record left behind names the test that never
 * finished. JaCoCo's agent appends to its file in both of codiqo's plugins, so each write adds only what changed since
 * the last one and the data is merged when it is read.
 *
 * <p>It runs on the analysed project's class path, so it uses nothing beyond the JDK, which also keeps it off the
 * project's own conventions for Guava, commons-lang3 and Lombok: any library it brought would be one more on that
 * project's tests. It is compiled for Java 8, the oldest JVM a JUnit 5 test run uses. It must never
 * stop a test JVM: an argument it cannot read leaves it inactive.
 */
public final class TestAgent {
    public static final String RECORD_PREFIX = "codiqo-test-jvm-";
    public static final String RECORD_SUFFIX = ".txt";
    public static final String FIELD_SEPARATOR = "\t";
    public static final String NO_FRAME = "-";

    private static final String ARG_DIRECTORY = "dir";
    private static final String ARG_INTERVAL = "interval";
    private static final String THREAD_NAME = "codiqo-test-agent";
    private static final String[] JDK_PACKAGES = { "java.", "javax.", "jdk.", "sun.", "com.sun." };
    private static final String[] REFLECTION_PACKAGES = { "java.lang.reflect.", "java.lang.invoke.", "jdk.internal.reflect.", "sun.reflect." };
    private static final String[] TEST_FRAMEWORK_PACKAGES = { "org.junit.", "junit.", "org.testng.", "org.spockframework." };

    private final Path record;
    private final Path pending;
    private boolean reported;

    private TestAgent(Path record) {
        this.record = record;
        this.pending = record.resolveSibling(record.getFileName() + ".tmp");
    }
    /** {@code dir=<directory>,interval=<seconds>}, as {@link #argument} writes it */
    public static void premain(String args, Instrumentation instrumentation) {
        try {
            Map<String, String> parsed = parse(args);
            Path directory = Paths.get(parsed.get(ARG_DIRECTORY));
            long interval = Long.parseLong(parsed.get(ARG_INTERVAL));
            Files.createDirectories(directory);

            TestAgent agent = new TestAgent(directory.resolve(RECORD_PREFIX + UUID.randomUUID() + RECORD_SUFFIX));
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread toReturn = new Thread(runnable, THREAD_NAME);
                toReturn.setDaemon(true);
                return toReturn;
            });
            timer.scheduleWithFixedDelay(agent::tick, interval, interval, TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(agent::delete, THREAD_NAME + "-exit"));
        } catch (IOException | RuntimeException err) {
            System.err.println("[codiqo] test agent inactive (" + args + "): " + err);
        }
    }
    /** the agent's argument for a record directory codiqo reads after the test run */
    public static String argument(String directory, long intervalSeconds) {
        return ARG_DIRECTORY + "=" + directory + "," + ARG_INTERVAL + "=" + intervalSeconds;
    }
    /**
     * One line per live thread that is running a test: its name, the frame the test framework invoked (the test or
     * lifecycle method) and the topmost frame outside the JDK (where it is stuck), separated by {@link #FIELD_SEPARATOR},
     * {@link #NO_FRAME} for a frame that is not there. Daemon threads and the agent's own are left out. Public so the
     * unit test can hand it made-up stacks.
     */
    public static List<String> describe(Map<Thread, StackTraceElement[]> stacks) {
        List<String> toReturn = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            Thread thread = entry.getKey();
            if (thread.isDaemon() || THREAD_NAME.equals(thread.getName())) {
                continue;
            }
            StackTraceElement[] stack = entry.getValue();
            int framework = topmostFrameworkFrame(stack);
            if (framework >= 0) {
                toReturn.add(thread.getName() + FIELD_SEPARATOR + invokedFrame(stack, framework) + FIELD_SEPARATOR + topApplicationFrame(stack));
            }
        }
        return toReturn;
    }
    private void tick() {
        try {
            flushCoverage();

            Files.write(pending, describe(Thread.getAllStackTraces()), StandardCharsets.UTF_8);
            Files.move(pending, record, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | ReflectiveOperationException | RuntimeException err) {
            /** a failing write repeats every interval: one line in the test output says why, the rest would be noise */
            if (reported) {
                return;
            }
            reported = true;
            System.err.println("[codiqo] test agent could not write its record: " + err);
        }
    }
    private void delete() {
        try {
            Files.deleteIfExists(pending);
            Files.deleteIfExists(record);
        } catch (IOException err) {
            System.err.println("[codiqo] test agent could not delete " + record + ": " + err);
        }
    }
    /**
     * JaCoCo's runtime is reached by name: its agent jar is on the system class path, and the project may not measure
     * coverage at all, in which case there is nothing to write. {@code dump(true)} writes what changed since the last
     * write and starts counting again, which leaves nothing out because JaCoCo appends to its file.
     */
    private static void flushCoverage() throws ReflectiveOperationException {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class<?> runtime;
        try {
            runtime = Class.forName("org.jacoco.agent.rt.RT", false, loader);
        } catch (ClassNotFoundException err) {
            return;
        }
        Object jacoco;
        try {
            jacoco = runtime.getMethod("getAgent").invoke(null);
        } catch (InvocationTargetException err) {
            /** IllegalStateException: the JaCoCo agent is on the class path but was not started in this JVM */
            return;
        }
        Class.forName("org.jacoco.agent.rt.IAgent", false, loader).getMethod("dump", boolean.class).invoke(jacoco, Boolean.TRUE);
    }
    private static Map<String, String> parse(String args) {
        Map<String, String> toReturn = new HashMap<>();
        for (String pair : Objects.requireNonNull(args, "no arguments").split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                toReturn.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        Objects.requireNonNull(toReturn.get(ARG_DIRECTORY), ARG_DIRECTORY);
        Objects.requireNonNull(toReturn.get(ARG_INTERVAL), ARG_INTERVAL);
        return toReturn;
    }
    /** the frame nearest the top of the stack that belongs to a test framework; -1 for a thread that runs no test */
    private static int topmostFrameworkFrame(StackTraceElement[] stack) {
        for (int i = 0; i < stack.length; i++) {
            if (startsWithAny(stack[i].getClassName(), TEST_FRAMEWORK_PACKAGES)) {
                return i;
            }
        }
        return -1;
    }
    /** the first frame above the framework's that is not reflection: the method the framework invoked */
    private static String invokedFrame(StackTraceElement[] stack, int framework) {
        for (int i = framework - 1; i >= 0; i--) {
            if (startsWithAny(stack[i].getClassName(), REFLECTION_PACKAGES)) {
                continue;
            }
            return format(stack[i]);
        }
        return NO_FRAME;
    }
    private static String topApplicationFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (startsWithAny(frame.getClassName(), JDK_PACKAGES)) {
                continue;
            }
            return format(frame);
        }
        return NO_FRAME;
    }
    /**
     * {@code class.method(File.java:16)}: {@link StackTraceElement#toString()} puts the class loader and module in front
     * from Java 9 ({@code app//}), which would make the same frame read differently on different JVMs
     */
    private static String format(StackTraceElement frame) {
        String location = Objects.nonNull(frame.getFileName()) ? frame.getFileName() + ":" + frame.getLineNumber() : "Unknown Source";
        return frame.getClassName() + "." + frame.getMethodName() + "(" + location + ")";
    }
    private static boolean startsWithAny(String className, String[] prefixes) {
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
