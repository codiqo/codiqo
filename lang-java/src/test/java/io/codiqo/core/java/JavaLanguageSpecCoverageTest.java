package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.JvmProjectSpec;
import io.codiqo.api.ProjectSpec;

/**
 * When a module is entitled to be flagged for producing no coverage. The report directories come from the build tool
 * that owns the layout (surefire's and failsafe's {@code reportsDirectory}, every Gradle {@code Test} task's
 * {@code reports.junitXml}), so nothing here derives a path from the shape of the compiled-output directory.
 */
class JavaLanguageSpecCoverageTest {
    @Test
    void aModuleWithCompiledClassesAndAnExecutedTestReportExpectsCoverage(@TempDir Path dir) throws IOException {
        File classes = compiled(dir, "target/classes");
        File reports = reportsWith(dir, "target/surefire-reports", "TEST-com.example.FooTest.xml");

        assertTrue(JavaLanguageSpec.expectsCoverage(project(classes, reports)));
    }
    /** a module whose only executed tests belong to a second task or execution still ran tests */
    @Test
    void anyOneOfTheReportedDirectoriesIsEnough(@TempDir Path dir) throws IOException {
        File classes = compiled(dir, "build/classes/java/main");
        File unit = dir(dir, "build/test-results/test");
        File integration = reportsWith(dir, "build/test-results/integrationTest", "TEST-com.example.FooIT.xml");

        assertTrue(JavaLanguageSpec.expectsCoverage(project(classes, unit, integration)));
    }
    /**
     * modules whose only src/test source is a main()-style helper (dev-server starter, migration generator) compile a
     * test class but surefire runs nothing, so the directory is created and left empty — must not be flagged
     */
    @Test
    void anEmptyReportDirectoryMeansNoTestRan(@TempDir Path dir) throws IOException {
        File classes = compiled(dir, "target/classes");
        File reports = dir(dir, "target/surefire-reports");

        assertFalse(JavaLanguageSpec.expectsCoverage(project(classes, reports)));
    }
    @Test
    void aModuleWhoseBuildReportedNoTestDirectoryAtAllIsNotFlagged(@TempDir Path dir) throws IOException {
        File classes = compiled(dir, "target/classes");

        assertFalse(JavaLanguageSpec.expectsCoverage(project(classes)));
    }
    /**
     * the directory is named by the build tool and may simply not exist — a module that never ran a test task has no
     * report directory on disk
     */
    @Test
    void aReportedDirectoryThatWasNeverCreatedIsNotEvidence(@TempDir Path dir) throws IOException {
        File classes = compiled(dir, "target/classes");

        assertFalse(JavaLanguageSpec.expectsCoverage(project(classes, dir.resolve("target/surefire-reports").toFile())));
    }
    /** surefire writes plain-text and dump files beside the XML; only an executed test class produces TEST-*.xml */
    @Test
    void anIncidentalFileInTheReportDirectoryIsNotATestReport(@TempDir Path dir) throws IOException {
        File classes = compiled(dir, "target/classes");
        File reports = reportsWith(dir, "target/surefire-reports", "com.example.FooTest.txt");

        assertFalse(JavaLanguageSpec.expectsCoverage(project(classes, reports)));
    }
    /**
     * regression: the report location used to be guessed by walking up from target/classes, which for a Maven module
     * landed two directories ABOVE the module — outside the analysed repository entirely, where a stray TEST-*.xml
     * belonging to something else would make every module look like a module whose tests ran. nothing is derived from
     * the output directory any more, so a report sitting there is simply not consulted.
     */
    @Test
    void aReportOutsideTheReportedDirectoriesIsNeverConsulted(@TempDir Path dir) throws IOException {
        File classes = compiled(dir, "module/target/classes");
        reportsWith(dir, "test-results", "TEST-com.example.StrayTest.xml");

        assertFalse(JavaLanguageSpec.expectsCoverage(project(classes)));
    }
    @Test
    void aModuleWithoutCompiledClassesIsNotFlagged(@TempDir Path dir) throws IOException {
        File classes = dir(dir, "target/classes");
        File reports = reportsWith(dir, "target/surefire-reports", "TEST-com.example.FooTest.xml");

        assertFalse(JavaLanguageSpec.expectsCoverage(project(classes, reports)));
    }
    @Test
    void aNonJvmProjectIsNotFlagged() throws IOException {
        assertFalse(JavaLanguageSpec.expectsCoverage(mock(ProjectSpec.class)));
    }
    private static JvmProjectSpec project(File outputDirectory, File... testReportDirectories) {
        JvmProjectSpec toReturn = mock(JvmProjectSpec.class);
        when(toReturn.getOutputDirectory()).thenReturn(outputDirectory);
        when(toReturn.getTestReportDirectories()).thenReturn(List.of(testReportDirectories));
        return toReturn;
    }
    private static File compiled(Path parent, String name) throws IOException {
        File toReturn = dir(parent, name);
        Files.createFile(toReturn.toPath().resolve("Foo.class"));
        return toReturn;
    }
    private static File reportsWith(Path parent, String name, String reportFile) throws IOException {
        File toReturn = dir(parent, name);
        Files.createFile(toReturn.toPath().resolve(reportFile));
        return toReturn;
    }
    private static File dir(Path parent, String name) throws IOException {
        return Files.createDirectories(parent.resolve(name)).toFile();
    }
}
