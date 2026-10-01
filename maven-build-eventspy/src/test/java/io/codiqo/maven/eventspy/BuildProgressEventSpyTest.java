package io.codiqo.maven.eventspy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildProgressEventSpyTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(BuildProgressConfig.PROP_PROGRESS_FILE);
    }

    @Test
    void recordsSessionSizeThenEachModuleStartAndOutcomeWithItsDuration() {
        BuildProgressEventSpy spy = new BuildProgressEventSpy();
        MavenSession session = mock(MavenSession.class);
        when(session.getProjects()).thenReturn(List.of(project("core"), project("util")));

        assertEquals(Optional.of("1000\tSESSION\t2"), spy.progressLine(sessionEvent(session), 1000L));
        assertEquals(Optional.of("1100\tSTARTED\torg.example:core"), spy.progressLine(event(ExecutionEvent.Type.ProjectStarted, "core"), 1100L));
        assertEquals(Optional.of("4100\tSUCCESS\torg.example:core\t3000"), spy.progressLine(event(ExecutionEvent.Type.ProjectSucceeded, "core"), 4100L));
        assertEquals(Optional.of("4200\tSKIPPED\torg.example:util\t0"), spy.progressLine(event(ExecutionEvent.Type.ProjectSkipped, "util"), 4200L));
    }
    @Test
    void ignoresMojoLevelEvents() {
        assertEquals(Optional.empty(), new BuildProgressEventSpy().progressLine(event(ExecutionEvent.Type.MojoStarted, "core"), 1000L));
    }
    @Test
    void appendsToTheConfiguredFile() throws IOException {
        Path progressFile = tempDir.resolve("progress.tsv");
        System.setProperty(BuildProgressConfig.PROP_PROGRESS_FILE, progressFile.toString());

        BuildProgressEventSpy spy = new BuildProgressEventSpy();
        spy.onEvent(event(ExecutionEvent.Type.ProjectStarted, "core"));
        spy.onEvent(event(ExecutionEvent.Type.ProjectFailed, "core"));

        List<String> lines = Files.readAllLines(progressFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), lines.toString());
        assertEquals("STARTED\torg.example:core", lines.get(0).split("\t", 2)[1]);
        assertEquals("FAILED", lines.get(1).split("\t")[1]);
    }
    @Test
    void writesNothingWithoutTheProperty() {
        new BuildProgressEventSpy().onEvent(event(ExecutionEvent.Type.ProjectStarted, "core"));

        assertFalse(Files.exists(tempDir.resolve("progress.tsv")));
    }
    private static ExecutionEvent sessionEvent(MavenSession session) {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.SessionStarted);
        when(event.getSession()).thenReturn(session);
        return event;
    }
    private static ExecutionEvent event(ExecutionEvent.Type type, String artifactId) {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(type);
        when(event.getProject()).thenReturn(project(artifactId));
        return event;
    }
    private static MavenProject project(String artifactId) {
        MavenProject project = new MavenProject();
        project.setGroupId("org.example");
        project.setArtifactId(artifactId);
        return project;
    }
}
