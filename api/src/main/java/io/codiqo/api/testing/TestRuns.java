package io.codiqo.api.testing;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.lang3.math.NumberUtils;

import com.google.common.collect.Lists;

import io.codiqo.api.logging.Log;
import lombok.experimental.UtilityClass;

/** Reads a build's JUnit XML reports into the commit's {@link TestRun}. */
@UtilityClass
public class TestRuns {
    public static final int MAX_SKIPPED_TESTS = 100;

    private static final String TEST_SUITE = "testsuite";
    private static final String TEST_CASE = "testcase";
    private static final String SKIPPED = "skipped";
    private static final IOFileFilter JUNIT_REPORTS = FileFilterUtils.and(
            FileFilterUtils.prefixFileFilter("TEST-"),
            FileFilterUtils.suffixFileFilter(FilenameUtils.EXTENSION_SEPARATOR_STR + "xml"));

    /** every report in the directories; a directory that does not exist holds none */
    public TestRun read(Collection<File> reportDirectories, Log log) {
        Totals totals = new Totals();
        for (File dir : reportDirectories) {
            if (dir.isDirectory()) {
                for (File report : FileUtils.listFiles(dir, JUNIT_REPORTS, null)) {
                    read(report, log).ifPresent(totals::merge);
                }
            }
        }
        return TestRun.builder()
                .tests(totals.tests)
                .failures(totals.failures)
                .errors(totals.errors)
                .skipped(totals.skipped)
                .testTime(Duration.ofMillis(Math.round(totals.seconds * DateUtils.MILLIS_PER_SECOND)))
                .skippedTests(totals.skippedTests)
                .build();
    }
    /**
     * Why the commit's score is held, when most executed tests failed. Such a run cannot tell a commit that broke a
     * shared test fixture from an environment the analysis could not rebuild, so the commit is reviewed and scored as
     * usual and the server holds the score out of every figure until someone accepts it. Holding, not halting: a halt
     * stopped every later commit of the project on one commit's own defect.
     */
    public Optional<String> heldReason(TestRun run, double holdShare, int minTests) {
        int executed = run.getTests() - run.getSkipped();
        /** a share of 1 or more turns the check off: a project whose tests need a database or Docker CI lacks would hold every commit */
        if (BooleanUtils.and(new boolean[] { holdShare < 1.0, executed >= minTests, run.failedShare() >= holdShare })) {
            return Optional.of(String.format(Locale.ROOT,
                    "%d of %d executed tests failed (%.0f%%, the hold threshold is %.0f%%): either the commit broke what most tests share or the analysis could not rebuild their environment",
                    run.getFailures() + run.getErrors(),
                    executed,
                    run.failedShare() * 100,
                    holdShare * 100));
        }
        return Optional.empty();
    }
    /**
     * A report that cannot be read is left out with a warning: one truncated file, from a test JVM killed mid-write,
     * must not fail the analysis of a commit whose other reports are whole. Its counts are kept apart until the whole
     * file has been read: a report cut after its suite's start tag has already given its totals, and a killed JVM's
     * {@code tests="30" failures="30"} merged on its own would halt the project as an unhealthy test run.
     */
    private static Optional<Totals> read(File report, Log log) {
        Totals totals = new Totals();
        try (InputStream in = Files.newInputStream(report.toPath())) {
            XMLStreamReader xml = factory().createXMLStreamReader(in);
            int suiteDepth = 0;
            String testCase = null;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = xml.getLocalName();
                    if (TEST_SUITE.equals(element)) {
                        /** only the outermost suite carries the totals; a nested suite repeats a share of them */
                        if (suiteDepth == 0) {
                            totals.add(xml);
                        }
                        suiteDepth++;
                    } else if (TEST_CASE.equals(element)) {
                        testCase = xml.getAttributeValue(null, "classname") + "#" + xml.getAttributeValue(null, "name");
                    } else if (SKIPPED.equals(element)) {
                        totals.skipped(testCase);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = xml.getLocalName();
                    if (TEST_SUITE.equals(element)) {
                        suiteDepth--;
                    } else if (TEST_CASE.equals(element)) {
                        testCase = null;
                    }
                }
            }
            xml.close();
            return Optional.of(totals);
        } catch (IOException | XMLStreamException err) {
            log.warn("test report %s left out of the test run: %s", report, err.getMessage());
            return Optional.empty();
        }
    }
    /** reports are build output the analysed project controls, so they get no DTD and no external entities */
    private static XMLInputFactory factory() {
        XMLInputFactory toReturn = XMLInputFactory.newFactory();
        toReturn.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        toReturn.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return toReturn;
    }
    private static final class Totals {
        private final List<String> skippedTests = Lists.newArrayList();
        private int tests;
        private int failures;
        private int errors;
        private int skipped;
        private double seconds;

        private void add(XMLStreamReader suite) {
            tests += count(suite, "tests");
            failures += count(suite, "failures");
            errors += count(suite, "errors");
            skipped += count(suite, SKIPPED);
            seconds += time(suite.getAttributeValue(null, "time"));
        }
        private void merge(Totals other) {
            tests += other.tests;
            failures += other.failures;
            errors += other.errors;
            skipped += other.skipped;
            seconds += other.seconds;
            other.skippedTests.forEach(this::skipped);
        }
        private void skipped(String testCase) {
            if (StringUtils.isNotEmpty(testCase) && skippedTests.size() < MAX_SKIPPED_TESTS) {
                skippedTests.add(testCase);
            }
        }
        private static int count(XMLStreamReader suite, String attribute) {
            return NumberUtils.toInt(suite.getAttributeValue(null, attribute), 0);
        }
        /** older surefire versions group thousands, "1,234.5", in the time they write */
        private static double time(String value) {
            return NumberUtils.toDouble(StringUtils.remove(value, ','), 0.0);
        }
    }
}
