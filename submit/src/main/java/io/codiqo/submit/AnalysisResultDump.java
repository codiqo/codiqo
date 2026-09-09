package io.codiqo.submit;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Objects;
import java.util.ServiceLoader;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.MapperBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.StdDateFormat;
import tools.jackson.dataformat.yaml.YAMLMapper;

import io.codiqo.api.LlmResponseUploader;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.llm.LlmResponseMapper;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import lombok.RequiredArgsConstructor;

/**
 * Writes the analysis result document — the submission's own data with the model's response mapped onto it — and
 * offers it to whatever {@link LlmResponseUploader} is on the classpath. Shared by the two paths that score locally,
 * a commit analysis and a score-from-file replay, which produce the same document from the same inputs.
 */
@RequiredArgsConstructor
public class AnalysisResultDump {
    private static final String FILE_PREFIX = "codiqo-analysis-";

    private final RunArgs args;
    private final boolean preferYaml;
    private final Log log;

    public File accept(AnalysisSubmissionModel submission, ScoringResult result, Duration duration) throws IOException {
        AnalysisResultModel analysisResult = new AnalysisResultModel();

        analysisResult.setProject(submission.getProject());
        analysisResult.setCommit(submission.getCommit());
        analysisResult.setFiles(submission.getFiles());
        analysisResult.setDependencies(submission.getDependencies());
        analysisResult.setDuplication(submission.getDuplication());
        analysisResult.setProjectMetrics(submission.getProjectMetrics());
        analysisResult.setProjectQuality(submission.getProjectQuality());
        analysisResult.setFullProjectCoverage(submission.getFullProjectCoverage());
        analysisResult.setBuildFailure(submission.getBuildFailure());

        LlmResponseMapper.mapToAnalysisResult(result.getResponse(), analysisResult);
        analysisResult.setLlmAnalysis(LlmResponseMapper.mapLlmAnalysis(result, duration, args.getLlmModel()));

        String extension = preferYaml ? "yaml" : "json";
        String commitSha = submission.getCommit().getSha();

        File toReturn = target(FILE_PREFIX + commitSha, extension);
        try (BufferedOutputStream stream = new BufferedOutputStream(Files.newOutputStream(toReturn.toPath()))) {
            stream.write(mapper().writeValueAsString(analysisResult).getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }
        log.info("%s analysis: %s", extension, toReturn.getAbsolutePath());

        upload(LlmResponseUploader.objectName(submission.getProject().getCode(), commitSha, extension), toReturn);
        return toReturn;
    }
    /**
     * best-effort: the commit this document describes has already been scored and submitted, so a store that refuses
     * it is reported rather than allowed to fail the analysis afterwards
     */
    private void upload(String objectName, File file) {
        for (LlmResponseUploader uploader : ServiceLoader.load(LlmResponseUploader.class)) {
            try {
                uploader.upload(objectName, Files.readAllBytes(file.toPath()));
                log.info("uploaded %s (%s)", objectName, FileUtils.byteCountToDisplaySize(file.length()));
            } catch (IOException | RuntimeException err) {
                log.warn("could not upload %s: %s", objectName, ExceptionUtils.getRootCauseMessage(err));
            }
        }
    }
    private ObjectMapper mapper() {
        MapperBuilder<?, ?> builder = preferYaml ? YAMLMapper.builder() : JsonMapper.builder();
        return builder
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL))
                .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true))
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }
    /** a replay run without an output directory still has to produce the file an uploader is handed */
    private File target(String name, String extension) throws IOException {
        File outputDir = args.getOutputDirectory();
        if (Objects.nonNull(outputDir)) {
            FileUtils.forceMkdir(outputDir);
            return new File(outputDir, name + FilenameUtils.EXTENSION_SEPARATOR_STR + extension);
        }
        return Files.createTempFile(name + "-", FilenameUtils.EXTENSION_SEPARATOR_STR + extension).toFile();
    }
}
