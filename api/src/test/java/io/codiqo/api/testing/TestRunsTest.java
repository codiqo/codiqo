package io.codiqo.api.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;

class TestRunsTest {
    @TempDir
    Path tempDir;

    @Test
    void sumsEverySuiteAndNamesTheSkippedTests() throws IOException {
        Path reports = Files.createDirectories(tempDir.resolve("surefire-reports"));
        Files.writeString(reports.resolve("TEST-a.ATest.xml"), """
                <testsuite name="a.ATest" tests="3" failures="1" errors="0" skipped="1" time="1.5">
                  <testcase classname="a.ATest" name="passes" time="0.5"/>
                  <testcase classname="a.ATest" name="fails" time="1.0"><failure message="no"/></testcase>
                  <testcase classname="a.ATest" name="disabled" time="0"><skipped/></testcase>
                </testsuite>""", StandardCharsets.UTF_8);
        Files.writeString(reports.resolve("TEST-b.BTest.xml"), """
                <testsuite name="b.BTest" tests="2" failures="0" errors="1" skipped="0" time="1,000.25">
                  <testcase classname="b.BTest" name="one" time="0.25"/>
                  <testcase classname="b.BTest" name="two" time="1000"><error message="boom"/></testcase>
                </testsuite>""", StandardCharsets.UTF_8);
        Files.writeString(reports.resolve("not-a-report.xml"), "<testsuite tests=\"99\"/>", StandardCharsets.UTF_8);

        TestRun run = TestRuns.read(List.of(reports.toFile(), tempDir.resolve("missing").toFile()), new NoopLog());

        assertEquals(5, run.getTests());
        assertEquals(1, run.getFailures());
        assertEquals(1, run.getErrors());
        assertEquals(1, run.getSkipped());
        assertEquals(Duration.ofMillis(1001750), run.getTestTime(), "an older surefire's grouped thousands are read as one number");
        assertEquals(List.of("a.ATest#disabled"), run.getSkippedTests());
    }
    /** a runner that wraps its suites in testsuites counts each suite once, not again through the wrapper */
    @Test
    void countsNestedSuitesOnce() throws IOException {
        Path reports = Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(reports.resolve("TEST-all.xml"), """
                <testsuites>
                  <testsuite name="outer" tests="4" failures="0" errors="0" skipped="0" time="2">
                    <testsuite name="inner" tests="4" failures="0" errors="0" skipped="0" time="2"/>
                  </testsuite>
                </testsuites>""", StandardCharsets.UTF_8);

        assertEquals(4, TestRuns.read(List.of(reports.toFile()), new NoopLog()).getTests());
    }
    /** a report truncated by a killed test JVM is left out; the whole ones still count */
    @Test
    void leavesOutAReportThatCannotBeRead() throws IOException {
        Path reports = Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(reports.resolve("TEST-whole.xml"), "<testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"1\"/>", StandardCharsets.UTF_8);
        Files.writeString(reports.resolve("TEST-cut.xml"), "<testsuite tests=\"7\" failures=\"0\" errors", StandardCharsets.UTF_8);

        assertEquals(2, TestRuns.read(List.of(reports.toFile()), new NoopLog()).getTests());
    }
    /** reports are output of the analysed project's build, so an external entity is never resolved */
    @Test
    void resolvesNoExternalEntity() throws IOException {
        File secret = Files.writeString(tempDir.resolve("secret.txt"), "s3cret", StandardCharsets.UTF_8).toFile();
        Path reports = Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(reports.resolve("TEST-xxe.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE t [<!ENTITY x SYSTEM "%s">]>
                <testsuite tests="1" failures="0" errors="0" skipped="1" time="0">
                  <testcase classname="&x;" name="n"><skipped/></testcase>
                </testsuite>""".formatted(secret.toURI()), StandardCharsets.UTF_8);

        TestRun run = TestRuns.read(List.of(reports.toFile()), new NoopLog());

        assertTrue(run.getSkippedTests().stream().noneMatch(test -> test.contains("s3cret")), run.getSkippedTests().toString());
    }
    /** a report cut after its suite's start tag has already been counted when the read fails, and must not count */
    @Test
    void leavesOutTheCountsOfAReportCutAfterItsSuiteStarted() throws IOException {
        Path reports = Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(reports.resolve("TEST-whole.xml"), "<testsuite tests=\"10\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"1\"/>", StandardCharsets.UTF_8);
        Files.writeString(reports.resolve("TEST-killed.xml"), """
                <testsuite tests="30" failures="30" errors="0" skipped="1" time="1">
                  <testcase classname="a.ATest" name="disabled"><skipped/></testcase>
                  <testcase classname="a.ATest" name="cut""", StandardCharsets.UTF_8);

        TestRun run = TestRuns.read(List.of(reports.toFile()), new NoopLog());

        assertEquals(10, run.getTests());
        assertEquals(0, run.getFailures());
        assertEquals(List.of(), run.getSkippedTests());
    }
    @Test
    void mostTestsFailingIsUnhealthy() {
        TestRun run = TestRun.builder().tests(40).failures(25).errors(5).skipped(0).testTime(Duration.ZERO).build();

        IllegalStateException err = assertThrows(IllegalStateException.class, () -> TestRuns.requireHealthy(run, 0.75, 20));
        assertTrue(err.getMessage().contains("30 of 40"), err.getMessage());
    }
    /** a project whose tests need a database or Docker the CI lacks turns the check off with a share of 1 or more */
    @Test
    void aShareOfOneOrMoreTurnsTheCheckOff() {
        TestRun allFailed = TestRun.builder().tests(40).failures(40).errors(0).skipped(0).testTime(Duration.ZERO).build();

        assertDoesNotThrow(() -> TestRuns.requireHealthy(allFailed, 1.0, 20));
        assertThrows(IllegalStateException.class, () -> TestRuns.requireHealthy(allFailed, 0.99, 20));
    }
    @Test
    void aFewFailuresOrTooFewTestsAreHealthy() {
        TestRun someFailed = TestRun.builder().tests(40).failures(10).errors(0).skipped(0).testTime(Duration.ZERO).build();
        TestRun tooFew = TestRun.builder().tests(10).failures(10).errors(0).skipped(0).testTime(Duration.ZERO).build();
        TestRun mostlySkipped = TestRun.builder().tests(100).failures(15).errors(0).skipped(90).testTime(Duration.ZERO).build();

        assertDoesNotThrow(() -> TestRuns.requireHealthy(someFailed, 0.75, 20));
        assertDoesNotThrow(() -> TestRuns.requireHealthy(tooFew, 0.75, 20), "ten tests prove nothing about the environment");
        assertDoesNotThrow(() -> TestRuns.requireHealthy(mostlySkipped, 0.75, 20), "the share is of executed tests, and only ten executed");
    }
    private static final class NoopLog implements Log {
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
