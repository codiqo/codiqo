package io.codiqo.api.testing;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.testagent.TestAgent;
import lombok.experimental.UtilityClass;

/**
 * The test agent codiqo loads into every test JVM of the analysed project ({@link TestAgent}), and the records it
 * leaves behind.
 *
 * <p>The tests run on the test framework's own thread, never on a thread a timeout started: Spring's test
 * transactions, Spring Security's mock users and every extension that keeps state per thread live on that thread, and a
 * test method moved off it ran outside its transaction (its rows committed into the next test) and logged out. JUnit
 * then ends a hung test by interrupting it, which a blocking socket read or a monitor deadlock ignores, so such a test
 * runs until the whole test JVM is killed by the test-run timeout. Killed, it runs no shutdown hook: JaCoCo, which
 * writes its coverage at exit, left an empty file, and the build said nothing of the test that never finished.
 *
 * <p>The agent writes JaCoCo's coverage every {@link RunArgs#TEST_RECORD_INTERVAL} and records the threads running
 * tests in a file it deletes when the JVM exits normally, so a record that is still there after the build is a test JVM
 * that ended before its tests finished.
 */
@UtilityClass
public class TestRecords {
    /** how many test threads the reason names; a fork killed in the middle of a parallel run can hold many */
    private static final int MAX_NAMED = 5;
    /** where the reason starts naming code, which hidden-source mode leaves out ({@link #withoutLastSeen}) */
    private static final String LAST_SEEN = "; the tests were last seen in ";
    private static final String PREMAIN_CLASS = "Premain-Class";
    private static final IOFileFilter RECORDS = FileFilterUtils.and(
            FileFilterUtils.prefixFileFilter(TestAgent.RECORD_PREFIX),
            FileFilterUtils.suffixFileFilter(TestAgent.RECORD_SUFFIX));

    /**
     * The JVM argument that loads the agent with its records in the directory. Empty when the agent's classes are not a
     * jar, as when the plugin runs from an IDE's class output: {@code -javaagent} takes only a jar, and a test JVM given
     * a directory does not start at all.
     */
    public Optional<String> jvmArgument(File directory) {
        File jar = agentJar();
        if (isAgent(jar)) {
            return Optional.of("-javaagent:" + jar.getAbsolutePath() + "=" + TestAgent.argument(directory.getAbsolutePath(), RunArgs.TEST_RECORD_INTERVAL.getSeconds()));
        }
        return Optional.empty();
    }
    /**
     * Why the test run covers only part of the tests, when a test JVM left a record naming a test it was still in: the
     * test-run timeout killed it, or it crashed. The commit is still scored, from the tests that completed and the
     * coverage the agent wrote, and the reason names where each test thread was last seen, so the test that hung can be
     * found. A record that names no test is left out: every JVM started with the agent writes one, so a server a test
     * started in a JVM of its own, with the test JVM's arguments, and then killed would otherwise hold the commit.
     */
    public Optional<String> cutOff(Collection<File> directories, Log log) {
        int ended = 0;
        List<String> lastSeen = Lists.newArrayList();
        for (File directory : directories) {
            if (directory.isDirectory()) {
                for (File record : FileUtils.listFiles(directory, RECORDS, null)) {
                    List<String> threads = threads(record, log);
                    if (CollectionUtils.isNotEmpty(threads)) {
                        ended++;
                        lastSeen.addAll(threads);
                    }
                }
            }
        }

        if (ended > 0) {
            return Optional.of(String.format("test JVMs that ended before their tests finished, as the test-run timeout or a crash ends one: %d. The test counts "
                    + "and coverage cover only the tests that completed%s%s", ended, LAST_SEEN, Joiner.on("; ").join(lastSeen.stream().limit(MAX_NAMED).toList())));
        }
        return Optional.empty();
    }
    /**
     * The reason without the classes, methods, files and threads it names: in hidden-source mode every identifier is
     * hashed before it leaves the run, and a hash of a stack frame would tell nobody where the test hung. A reason that
     * names none comes back unchanged.
     */
    public String withoutLastSeen(String reason) {
        return StringUtils.substringBefore(reason, LAST_SEEN);
    }
    private static List<String> threads(File record, Log log) {
        List<String> toReturn = Lists.newArrayList();
        try {
            for (String line : FileUtils.readLines(record, StandardCharsets.UTF_8)) {
                List<String> fields = Splitter.on(TestAgent.FIELD_SEPARATOR).limit(TestAgent.FIELDS).splitToList(line);
                if (fields.size() == TestAgent.FIELDS) {
                    String invoked = fields.get(1);
                    String top = fields.get(2);
                    String thread = fields.iterator().next();
                    if (BooleanUtils.or(new boolean[] { top.equals(invoked), TestAgent.NO_FRAME.equals(top) })) {
                        toReturn.add(thread + " at " + invoked);
                    } else {
                        toReturn.add(thread + " at " + invoked + ", inside " + top);
                    }
                }
            }
        } catch (IOException err) {
            log.warn("test record %s could not be read: %s", record, err.getMessage());
        }
        return toReturn;
    }
    /** a jar whose manifest names the agent's entry point: a class directory, or a jar some tool rewrote, starts no agent */
    private static boolean isAgent(File jar) {
        if (jar.isFile()) {
            try (JarFile file = new JarFile(jar)) {
                return Optional.ofNullable(file.getManifest())
                        .map(manifest -> manifest.getMainAttributes().getValue(PREMAIN_CLASS))
                        .filter(TestAgent.class.getName()::equals)
                        .isPresent();
            } catch (IOException err) {
                /** the caller says the agent is left off, in the build's own log */
                return false;
            }
        }
        return false;
    }
    private static File agentJar() {
        for (;;) {
            try {
                return new File(TestAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (URISyntaxException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
}
