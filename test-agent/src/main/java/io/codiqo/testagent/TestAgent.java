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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The agent codiqo loads with {@code -javaagent} into every test JVM of the analysed project. Every interval it has
 * JaCoCo write the coverage collected since its last write, and records where the threads running tests are, in a file
 * of its own.
 *
 * <p>A test JVM killed by its timeout runs no shutdown hook: surefire ends a timed-out fork with {@code Runtime.halt},
 * and JaCoCo, which writes its data at exit, then left an empty file, so the commit lost the coverage of every test
 * that had finished. The writes during the run keep that coverage, and the record left behind names the test that never
 * finished. JaCoCo's agent appends to its file in both of codiqo's plugins, so each write adds only what changed since
 * the last one and the data is merged when it is read.
 *
 * <p>When the JVM shuts down with its hooks running, the record is kept only while a thread is still inside a test:
 * Gradle ends a timed-out test JVM with SIGTERM, which runs the hooks, so deleting the record on every exit would have
 * hidden that timeout, while a JVM whose tests finished has no thread left inside one. The timer is stopped first, so a
 * write it was about to make cannot put a deleted record back.
 *
 * <p>It runs on the analysed project's class path, so it uses nothing beyond the JDK, which also keeps it off the
 * project's own conventions for Guava, commons-lang3 and Lombok: any library it brought would be one more on that
 * project's tests. It is compiled for Java 8, the oldest JVM a JUnit 5 test run uses. It must never stop a test JVM: an
 * argument it cannot read leaves it inactive.
 */
public final class TestAgent implements AutoCloseable {
    public static final String RECORD_PREFIX = "codiqo-test-jvm-";
    public static final String RECORD_SUFFIX = ".txt";
    public static final String FIELD_SEPARATOR = "\t";
    public static final String NO_FRAME = "-";
    /** a record line's fields: the thread, the frame the framework invoked, the topmost frame outside the JDK */
    public static final int FIELDS = 3;

    private static final String ARG_INTERVAL = "interval=";
    private static final String ARG_DIRECTORY = ",dir=";
    private static final String THREAD_NAME = "codiqo-test-agent";
    private static final String[] JDK_PACKAGES = { "java.", "javax.", "jdk.", "sun.", "com.sun." };
    private static final String[] REFLECTION_PACKAGES = { "java.lang.reflect.", "java.lang.invoke.", "jdk.internal.reflect.", "sun.reflect." };
    private static final String[] TEST_FRAMEWORK_PACKAGES = { "org.junit.", "junit.", "org.testng.", "org.spockframework." };

    private final Path record;
    private final Path pending;
    private final ScheduledExecutorService timer;
    private boolean closed;
    private boolean reported;

    private TestAgent(Path record) {
        this.record = record;
        this.pending = record.resolveSibling(record.getFileName() + ".tmp");
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread toReturn = new Thread(runnable, THREAD_NAME);
            toReturn.setDaemon(true);
            return toReturn;
        });
    }
    /** {@code interval=<seconds>,dir=<directory>}, as {@link #argument} writes it */
    public static void premain(String args, Instrumentation instrumentation) {
        start(args);
    }
    /**
     * The directory goes last and runs to the end of the argument, so a path holding a comma reaches the agent whole: a
     * checkout under {@code /ci/ws/a,b} would otherwise send the records somewhere codiqo never reads.
     */
    public static String argument(String directory, long intervalSeconds) {
        return ARG_INTERVAL + intervalSeconds + ARG_DIRECTORY + directory;
    }
    /**
     * Starts the agent, or leaves it inactive with one line on standard error when the argument cannot be read. The
     * first record is written at once, so a JVM that dies within its first interval is not taken for one that finished.
     * Public so the unit test can close the agent it starts; {@link #premain} is the agent's entry point.
     */
    public static Optional<TestAgent> start(String args) {
        int directory = Objects.nonNull(args) ? args.indexOf(ARG_DIRECTORY) : -1;
        if (directory > 0 && args.startsWith(ARG_INTERVAL)) {
            try {
                long interval = Long.parseLong(args.substring(ARG_INTERVAL.length(), directory).trim());
                Path records = Paths.get(args.substring(directory + ARG_DIRECTORY.length()));
                Files.createDirectories(records);

                TestAgent toReturn = new TestAgent(records.resolve(RECORD_PREFIX + UUID.randomUUID() + RECORD_SUFFIX));
                toReturn.write(new ArrayList<>());
                toReturn.timer.scheduleWithFixedDelay(toReturn::tick, interval, interval, TimeUnit.SECONDS);
                Runtime.getRuntime().addShutdownHook(new Thread(toReturn::exit, THREAD_NAME + "-exit"));
                return Optional.of(toReturn);
            } catch (IOException | RuntimeException err) {
                System.err.println("[codiqo] test agent inactive (" + args + "): " + err);
                return Optional.empty();
            }
        }
        System.err.println("[codiqo] test agent inactive (" + args + "): expected " + argument("<directory>", 60));
        return Optional.empty();
    }
    /**
     * One line per live thread that is inside a test: its name, the frame the test framework invoked (the test or
     * lifecycle method) and the topmost frame outside the JDK (where it is stuck), separated by {@link #FIELD_SEPARATOR},
     * {@link #NO_FRAME} for a top frame that is not there. A thread counts when a test framework called into code that is
     * not the JDK's: daemon threads included, since JUnit runs parallel tests on daemon ForkJoin workers, and threads only
     * waiting in the JDK left out (the main thread joining those workers, JUnit's timeout watcher). Public so the unit
     * test can hand it made-up stacks.
     */
    public static List<String> describe(Map<Thread, StackTraceElement[]> stacks) {
        List<String> toReturn = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            String thread = entry.getKey().getName();
            StackTraceElement[] stack = entry.getValue();
            int framework = topmostFrameworkFrame(stack);
            if (framework < 0 || thread.startsWith(THREAD_NAME)) {
                continue;
            }
            Optional<StackTraceElement> invoked = invokedFrame(stack, framework).filter(frame -> isApplication(frame.getClassName()));
            invoked.ifPresent(frame -> toReturn.add(thread + FIELD_SEPARATOR + format(frame) + FIELD_SEPARATOR + topApplicationFrame(stack)));
        }
        return toReturn;
    }
    /** stops the agent and deletes its record; the unit test's way to end what it started */
    @Override
    public synchronized void close() {
        closed = true;
        timer.shutdownNow();
        delete();
    }
    private synchronized void tick() {
        if (closed) {
            return;
        }
        try {
            flushCoverage();
            write(describe(Thread.getAllStackTraces()));
        } catch (IOException | ReflectiveOperationException | RuntimeException err) {
            /** a failing write repeats every interval: one line in the test output says why, the rest would be noise */
            if (reported) {
                return;
            }
            reported = true;
            System.err.println("[codiqo] test agent could not write its record: " + err);
        }
    }
    /** a JVM shutting down with a thread still inside a test did not finish its tests, whatever ended it */
    private synchronized void exit() {
        closed = true;
        timer.shutdownNow();

        List<String> running = describe(Thread.getAllStackTraces());
        if (running.isEmpty()) {
            delete();
            return;
        }
        try {
            write(running);
        } catch (IOException err) {
            System.err.println("[codiqo] test agent could not write its record: " + err);
        }
    }
    private void write(List<String> lines) throws IOException {
        Files.write(pending, lines, StandardCharsets.UTF_8);
        Files.move(pending, record, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
    private static Optional<StackTraceElement> invokedFrame(StackTraceElement[] stack, int framework) {
        for (int i = framework - 1; i >= 0; i--) {
            if (startsWithAny(stack[i].getClassName(), REFLECTION_PACKAGES)) {
                continue;
            }
            return Optional.of(stack[i]);
        }
        return Optional.empty();
    }
    private static String topApplicationFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (isApplication(frame.getClassName())) {
                return format(frame);
            }
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
    private static boolean isApplication(String className) {
        for (String prefix : JDK_PACKAGES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return true;
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
