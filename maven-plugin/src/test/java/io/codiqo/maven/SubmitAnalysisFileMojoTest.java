package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.ProjectModel;

class SubmitAnalysisFileMojoTest {
    @TempDir
    Path tempDir;

    ObjectMapper mapper = new YAMLMapper();

    public SubmitAnalysisFileMojoTest() {
        mapper.setDefaultPropertyInclusion(Include.NON_NULL);
        mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.registerModule(new JavaTimeModule());
    }
    @Test
    void readSubmissionRoundTripsYamlIntoModel() throws Exception {
        AnalysisSubmissionModel original = sampleSubmission();
        File yamlFile = writeYaml(original, tempDir.resolve("codiqo-submission-abc.yaml"));

        AnalysisSubmissionModel parsed = mapper.readValue(yamlFile, AnalysisSubmissionModel.class);

        assertNotNull(parsed);
        assertEquals(original.getProject().getCode(), parsed.getProject().getCode());
        assertEquals(original.getProject().getName(), parsed.getProject().getName());
        assertEquals(original.getCommit().getSha(), parsed.getCommit().getSha());
        assertEquals(original.getCommit().getAuthorEmail(), parsed.getCommit().getAuthorEmail());
    }
    @Test
    void readSubmissionFailsWithClearMessageWhenFileMissing() {
        File missing = tempDir.resolve("does-not-exist.yaml").toFile();
        assertThrows(IOException.class, () -> mapper.readValue(missing, AnalysisSubmissionModel.class));
    }
    private File writeYaml(AnalysisSubmissionModel submission, Path path) throws IOException {
        Files.writeString(path, mapper.writeValueAsString(submission), StandardCharsets.UTF_8);
        return path.toFile();
    }
    private static AnalysisSubmissionModel sampleSubmission() {
        ProjectModel project = new ProjectModel();
        project.setCode("codiqo-test");
        project.setName("Codiqo Test");

        CommitModel commit = new CommitModel();
        commit.setSha("0123456789abcdef0123456789abcdef01234567");
        commit.setMessage("test commit");
        commit.setAuthor("Tester");
        commit.setAuthorEmail("tester@example.com");
        commit.setTimestamp(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        AnalysisSubmissionModel submission = new AnalysisSubmissionModel();
        submission.setProject(project);
        submission.setCommit(commit);
        return submission;
    }
}
