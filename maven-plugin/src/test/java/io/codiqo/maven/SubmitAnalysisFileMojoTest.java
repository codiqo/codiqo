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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.util.StdDateFormat;
import tools.jackson.dataformat.yaml.YAMLMapper;

import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.ProjectModel;

class SubmitAnalysisFileMojoTest {
    @TempDir
    Path tempDir;

    ObjectMapper mapper = YAMLMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL))
            .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true))
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

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
        assertThrows(Exception.class, () -> mapper.readValue(missing, AnalysisSubmissionModel.class));
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
