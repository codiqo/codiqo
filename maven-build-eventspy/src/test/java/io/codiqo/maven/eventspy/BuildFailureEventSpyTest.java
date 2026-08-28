package io.codiqo.maven.eventspy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildFailureEventSpyTest {
    private static final String DIAGNOSTICS = """
            /src/test/java/Foo.java:[86,34] cannot find symbol
              symbol:   class DatabaseConfig
            /src/test/java/Foo.java:[100,19] cannot find symbol
              symbol:   variable DatabaseFactory""";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(BuildFailureConfig.PROP_REPORT_FILE);
    }

    @Test
    void recordsMojoDetailAheadOfTheStackTrace() throws IOException {
        String report = writeReport(new RuntimeException("Failed to execute goal ... on project ebean-test",
                new MojoFailureException(null, "Compilation failure", DIAGNOSTICS)));

        assertTrue(report.contains(DIAGNOSTICS), report);
        assertTrue(report.indexOf("detail:") < report.indexOf("java.lang.RuntimeException"), report);
    }

    @Test
    void skipsDetailWhenTheMojoReportedNone() throws IOException {
        String report = writeReport(new RuntimeException("fork build died"));

        assertEquals(-1, report.indexOf("detail:"), report);
        assertTrue(report.contains("java.lang.RuntimeException: fork build died"), report);
    }

    private String writeReport(Exception error) throws IOException {
        Path reportFile = tempDir.resolve("build-failure.txt");
        System.setProperty(BuildFailureConfig.PROP_REPORT_FILE, reportFile.toString());

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getException()).thenReturn(error);

        new BuildFailureEventSpy().onEvent(event);

        return Files.readString(reportFile, StandardCharsets.UTF_8);
    }
}
