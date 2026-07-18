package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.JvmProjectSpec;
import io.codiqo.api.ProjectSpec;

class JavaLanguageSpecCoverageTest {
    @Test
    void expectsCoverageWhenMainClassesAndSurefireReportsExist(@TempDir Path dir) throws IOException {
        File classes = dir(dir, "target/classes");
        Files.createFile(classes.toPath().resolve("Foo.class"));
        File reports = dir(dir, "target/surefire-reports");
        Files.createFile(reports.toPath().resolve("TEST-fraud.FooTest.xml"));

        JvmProjectSpec project = mock(JvmProjectSpec.class);
        when(project.getOutputDirectory()).thenReturn(classes);

        assertTrue(JavaLanguageSpec.expectsCoverage(project));
    }

    @Test
    void expectsCoverageWhenMainClassesAndFailsafeReportsExist(@TempDir Path dir) throws IOException {
        File classes = dir(dir, "target/classes");
        Files.createFile(classes.toPath().resolve("Foo.class"));
        File reports = dir(dir, "target/failsafe-reports");
        Files.createFile(reports.toPath().resolve("TEST-it.FooIT.xml"));

        JvmProjectSpec project = mock(JvmProjectSpec.class);
        when(project.getOutputDirectory()).thenReturn(classes);

        assertTrue(JavaLanguageSpec.expectsCoverage(project));
    }

    @Test
    void expectsCoverageWhenGradleClassesAndTestResultsExist(@TempDir Path dir) throws IOException {
        File classes = dir(dir, "build/classes/java/main");
        Files.createFile(classes.toPath().resolve("Foo.class"));
        File reports = dir(dir, "build/test-results/test");
        Files.createFile(reports.toPath().resolve("TEST-fraud.FooTest.xml"));

        JvmProjectSpec project = mock(JvmProjectSpec.class);
        when(project.getOutputDirectory()).thenReturn(classes);

        assertTrue(JavaLanguageSpec.expectsCoverage(project));
    }

    @Test
    void doesNotExpectCoverageForGradleModuleWithoutTestResults(@TempDir Path dir) throws IOException {
        File classes = dir(dir, "build/classes/java/main");
        Files.createFile(classes.toPath().resolve("Foo.class"));
        dir(dir, "build/test-results/test");

        JvmProjectSpec project = mock(JvmProjectSpec.class);
        when(project.getOutputDirectory()).thenReturn(classes);

        assertFalse(JavaLanguageSpec.expectsCoverage(project));
    }

    @Test
    void doesNotExpectCoverageWhenTestSourcesRunNoTests(@TempDir Path dir) throws IOException {
        /**
         * modules whose only src/test source is a main()-style helper (dev-server starter, migration generator)
         * compile a test class but surefire runs nothing, so no report is written — must not be flagged.
         */
        File classes = dir(dir, "target/classes");
        Files.createFile(classes.toPath().resolve("Foo.class"));

        JvmProjectSpec project = mock(JvmProjectSpec.class);
        when(project.getOutputDirectory()).thenReturn(classes);

        assertFalse(JavaLanguageSpec.expectsCoverage(project));
    }

    @Test
    void doesNotExpectCoverageWithoutMainClasses(@TempDir Path dir) throws IOException {
        File classes = dir(dir, "target/classes");
        File reports = dir(dir, "target/surefire-reports");
        Files.createFile(reports.toPath().resolve("TEST-fraud.FooTest.xml"));

        JvmProjectSpec project = mock(JvmProjectSpec.class);
        when(project.getOutputDirectory()).thenReturn(classes);

        assertFalse(JavaLanguageSpec.expectsCoverage(project));
    }

    @Test
    void doesNotExpectCoverageForNonJvmProject() throws IOException {
        assertFalse(JavaLanguageSpec.expectsCoverage(mock(ProjectSpec.class)));
    }

    private static File dir(Path parent, String name) throws IOException {
        return Files.createDirectories(parent.resolve(name)).toFile();
    }
}
