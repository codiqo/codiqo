package io.codiqo.maven;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.maven.populator.SubmissionContext;

@Mojo(name = "dump-commit-analysis",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class DumpCommitAnalysisMojo extends AnalyzeCommitMojo {
    @Override
    protected void doLlmScoring(SubmissionContext ctx) throws Exception {
        if (Objects.isNull(outputDirectory)) {
            throw new MojoFailureException("codiqo.outputDirectory is required for dump-commit-analysis");
        }

        FileUtils.forceMkdir(outputDirectory);

        AnalysisSubmissionModel submission = ctx.getSubmissionModel();
        String commitSha = ctx.getAnalysis().getCommitId();
        String fileName = "codiqo-submission-" + commitSha + ".yaml";

        ObjectMapper mapper = new YAMLMapper();
        mapper.setDefaultPropertyInclusion(Include.NON_NULL);
        mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.registerModule(new JavaTimeModule());

        File file = new File(outputDirectory, fileName);
        String output = mapper.writeValueAsString(submission);
        try (OutputStream stream = Files.newOutputStream(file.toPath())) {
            try (BufferedOutputStream bufferedStream = new BufferedOutputStream(stream)) {
                bufferedStream.write(output.getBytes(StandardCharsets.UTF_8));
                bufferedStream.flush();
            }
        }

        getLog().info("offline analysis dumped to " + file.toPath().normalize().toAbsolutePath().toString());
    }
}
