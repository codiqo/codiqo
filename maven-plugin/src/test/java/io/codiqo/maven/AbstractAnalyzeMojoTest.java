package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.stream.Stream;

import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.model.building.ModelProblem.Version;
import org.junit.jupiter.api.Test;

class AbstractAnalyzeMojoTest {
    @Test
    void firstSevereProblemReturnsEmptyWhenNoProblems() {
        assertTrue(Maven.severeProblem(Stream.empty()).isEmpty());
    }
    @Test
    void firstSevereProblemFiltersOutWarningsAndInfo() {
        Stream<ModelProblem> problems = Stream.of(
                problem("info", Severity.WARNING, "pom.xml", 1, 0, "mid"),
                problem("warn", Severity.WARNING, "pom.xml", 2, 0, "mid"));

        assertTrue(Maven.severeProblem(problems).isEmpty());
    }
    @Test
    void firstSevereProblemReturnsFormattedErrorWhenPresent() {
        Optional<String> result = Maven.severeProblem(Stream.of(
                problem("warn", Severity.WARNING, "pom.xml", 5, 0, "mid"),
                problem("boom", Severity.ERROR, "pom.xml", 12, 4, "io.codiqo:demo:1.0")));

        assertTrue(result.isPresent());
        assertEquals("broken POM at pom.xml:12:4 [modelId=io.codiqo:demo:1.0, severity=ERROR]: boom", result.get());
    }
    @Test
    void firstSevereProblemPrefersFatalOverError() {
        Optional<String> result = Maven.severeProblem(Stream.of(
                problem("err", Severity.ERROR, "pom.xml", 3, 0, "mid"),
                problem("fatal", Severity.FATAL, "pom.xml", 99, 0, "mid")));

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("severity=FATAL"));
        assertTrue(result.get().contains("fatal"));
    }
    @Test
    void firstSevereProblemBreaksTiesByLineNumber() {
        Optional<String> result = Maven.severeProblem(Stream.of(
                problem("late", Severity.ERROR, "pom.xml", 42, 0, "mid"),
                problem("early", Severity.ERROR, "pom.xml", 10, 0, "mid")));

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("early"));
        assertTrue(result.get().contains(":10"));
    }
    @Test
    void formatProblemRendersAllFieldsWhenPopulated() {
        String formatted = Maven.formatProblem(
                problem("msg", Severity.ERROR, "pom.xml", 12, 5, "io.codiqo:demo:1.0"));

        assertEquals("broken POM at pom.xml:12:5 [modelId=io.codiqo:demo:1.0, severity=ERROR]: msg", formatted);
    }
    @Test
    void formatProblemFallsBackToDefaultsWhenSourceAndModelIdBlank() {
        String formatted = Maven.formatProblem(
                problem("msg", Severity.ERROR, "", 0, 0, ""));

        assertEquals("broken POM at unknown [modelId=?, severity=ERROR]: msg", formatted);
    }
    @Test
    void formatProblemOmitsLineWhenZero() {
        String formatted = Maven.formatProblem(
                problem("msg", Severity.FATAL, "pom.xml", 0, 0, "mid"));

        assertEquals("broken POM at pom.xml [modelId=mid, severity=FATAL]: msg", formatted);
    }
    @Test
    void formatProblemOmitsColumnWhenZero() {
        String formatted = Maven.formatProblem(
                problem("msg", Severity.FATAL, "pom.xml", 7, 0, "mid"));

        assertEquals("broken POM at pom.xml:7 [modelId=mid, severity=FATAL]: msg", formatted);
    }
    private static ModelProblem problem(String message, Severity severity, String source, int line, int column, String modelId) {
        return new DefaultModelProblem(message, severity, Version.BASE, source, line, column, modelId, null);
    }
}
