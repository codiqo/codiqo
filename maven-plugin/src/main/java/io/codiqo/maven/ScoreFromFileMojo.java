package io.codiqo.maven;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.llm.HtmlReportBuilder;
import io.codiqo.llm.LlmResponseMapper;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.ReportBuilder.ReportContext;
import io.codiqo.llm.SubmissionToRequestMapper;
import io.codiqo.llm.client.LlmScoringClient;
import io.codiqo.llm.client.ScoringClient;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.maven.populator.LlmScoringPopulator;

@Mojo(name = "score-from-file", requiresProject = false)
public class ScoreFromFileMojo extends AbstractMojo {
    @Parameter(property = "codiqo.inputFile", required = true)
    private File inputFile;
    @Parameter(property = "codiqo.outputDirectory", defaultValue = "${project.build.directory}/codiqo")
    private File outputDirectory;
    @Parameter(property = "codiqo.llm.model", defaultValue = "gpt-oss:120b-cloud")
    private String llmModel;
    @Parameter(property = "codiqo.llm.apiKey")
    private String llmApiKey;
    @Parameter(property = "codiqo.llm.baseUrl", defaultValue = "https://ollama.com/v1")
    private String llmBaseUrl;
    @Parameter(property = "codiqo.llm.temperature", defaultValue = "0.3")
    private double llmTemperature;
    @Parameter(property = "codiqo.llm.maxTokens", defaultValue = "32767")
    private int llmMaxTokens;
    @Parameter(property = "codiqo.llm.nativeThinking", defaultValue = "true")
    private boolean llmNativeThinking;
    @Parameter(property = "codiqo.llm.enableWebSearchTool", defaultValue = "true")
    private boolean llmEnableWebSearchTool;
    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "300")
    private long readTimeoutSeconds;
    @Parameter(property = "codiqo.dumpAnalysis", defaultValue = "true")
    private boolean dumpAnalysis;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            AnalysisSubmissionModel submission = loadSubmission();
            RunArgs args = buildRunArgs();
            LlmScoringRequest request = new SubmissionToRequestMapper(args).apply(submission);
            PromptContext promptContext = LlmScoringPopulator.buildPromptContext(submission, args);

            getLog().info("scoring from file: " + inputFile.getAbsolutePath());
            getLog().info("LLM model: " + llmModel);
            getLog().info("base URL: " + llmBaseUrl);

            long startTime = System.currentTimeMillis();
            ScoringResult result;
            try (LlmScoringClient client = new LlmScoringClient(args)) {
                result = client.score(request, promptContext, new ScoringClient.StreamingHandler() {});
            }
            Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);

            LlmScoringResponse response = result.getResponse();
            getLog().info(String.format("LLM Score: %.0f (%s) | Review: %d/10 | %dms | %d tokens | %d bugs",
                    response.getScore(),
                    response.getChangeClassification(),
                    response.getRequiresSeniorReview(),
                    duration.toMillis(),
                    result.getTotalTokens(),
                    response.getTotalBugCount()));

            if (dumpAnalysis) {
                FileUtils.forceMkdir(outputDirectory);
                generateHtmlReport(submission, result, request, duration);
                dumpYamlOutput(submission, response, args);
            }
        } catch (Exception err) {
            throw new MojoFailureException(err);
        }
    }
    private AnalysisSubmissionModel loadSubmission() throws IOException {
        ObjectMapper yamlMapper = new YAMLMapper();
        yamlMapper.registerModule(new JavaTimeModule());
        yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return yamlMapper.readValue(inputFile, AnalysisSubmissionModel.class);
    }
    private RunArgs buildRunArgs() {
        RunArgs toReturn = new RunArgs();
        toReturn.setLlmModel(llmModel);
        toReturn.setLlmBaseUrl(llmBaseUrl);
        toReturn.setLlmTemperature(llmTemperature);
        toReturn.setLlmMaxTokens(llmMaxTokens);
        toReturn.setLlmNativeThinking(llmNativeThinking);
        toReturn.setLlmEnableWebSearchTool(llmEnableWebSearchTool);
        toReturn.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        toReturn.setDumpAnalysis(dumpAnalysis);
        Optional.ofNullable(outputDirectory).ifPresent(toReturn::setOutputDirectory);
        if (StringUtils.isNotEmpty(llmApiKey)) {
            if (llmApiKey.startsWith("env:")) {
                String envVar = llmApiKey.substring(4);
                toReturn.setLlmApiKey(System.getenv(envVar));
            } else {
                toReturn.setLlmApiKey(llmApiKey);
            }
        }
        return toReturn;
    }
    private void generateHtmlReport(AnalysisSubmissionModel submission, ScoringResult result, LlmScoringRequest request, Duration duration) throws IOException {
        HtmlReportBuilder builder = new HtmlReportBuilder();
        CommitModel commit = submission.getCommit();

        ReportContext.ReportContextBuilder contextBuilder = ReportContext.builder()
                .repositoryName(Optional.ofNullable(submission.getProject())
                        .map(p -> p.getName())
                        .orElse("unknown"))
                .llmModel(llmModel)
                .analysisDuration(duration);

        if (Objects.nonNull(commit)) {
            contextBuilder
                    .commitId(commit.getSha())
                    .author(commit.getAuthor())
                    .authorEmail(commit.getAuthorEmail())
                    .commitMessage(commit.getMessage())
                    .branches(Optional.ofNullable(commit.getBranches()).orElse(Collections.emptyList()))
                    .mergeCommit(Boolean.TRUE.equals(commit.getIsMerge()));
            if (Objects.nonNull(commit.getTimestamp())) {
                contextBuilder.timestamp(commit.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            }
        }

        String html = builder.buildReport(result, request, contextBuilder.build());

        String commitSha = extractCommitSha(submission);
        File htmlFile = new File(outputDirectory, "codiqo-analysis-" + commitSha + ".html");
        try (FileOutputStream stream = new FileOutputStream(htmlFile)) {
            try (BufferedOutputStream bufferedStream = new BufferedOutputStream(stream)) {
                bufferedStream.write(html.getBytes(StandardCharsets.UTF_8));
                bufferedStream.flush();
            }
        }
        getLog().info("HTML report: " + htmlFile.getAbsolutePath());
    }
    private void dumpYamlOutput(AnalysisSubmissionModel submission, LlmScoringResponse llmResponse, RunArgs args) throws IOException {
        Map<String, Object> combinedOutput = new LinkedHashMap<>();
        combinedOutput.put("submission", submission);

        AnalysisResultModel analysisResult = new AnalysisResultModel();
        LlmResponseMapper mapper = new LlmResponseMapper();
        mapper.mapToAnalysisResult(llmResponse, analysisResult);

        Map<String, Object> llmSection = new LinkedHashMap<>();
        llmSection.put("score", llmResponse.getScore());
        llmSection.put("scoreCalculation", llmResponse.getScoreCalculation());
        llmSection.put("summary", llmResponse.getSummary());
        llmSection.put("changeClassification", llmResponse.getChangeClassification());
        llmSection.put("requiresSeniorReview", llmResponse.getRequiresSeniorReview());
        llmSection.put("seniorReviewReasons", llmResponse.getSeniorReviewReasons());

        combinedOutput.put("llmScoring", llmSection);
        combinedOutput.put("analysisResult", analysisResult);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", args.getLlmModel());
        combinedOutput.put("metadata", metadata);

        ObjectMapper yamlMapper = new YAMLMapper();
        yamlMapper.setDefaultPropertyInclusion(Include.NON_NULL);
        yamlMapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);
        yamlMapper.registerModule(new JavaTimeModule());

        String commitSha = extractCommitSha(submission);
        File file = new File(outputDirectory, "codiqo-llm-analysis-" + commitSha + ".yaml");
        String output = yamlMapper.writeValueAsString(combinedOutput);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            try (BufferedOutputStream bufferedStream = new BufferedOutputStream(stream)) {
                bufferedStream.write(output.getBytes(StandardCharsets.UTF_8));
                bufferedStream.flush();
            }
        }
        getLog().info("YAML dump: " + file.getAbsolutePath());
    }
    private static String extractCommitSha(AnalysisSubmissionModel submission) {
        CommitModel commit = submission.getCommit();
        if (Objects.nonNull(commit) && Objects.nonNull(commit.getSha())) {
            String sha = commit.getSha();
            return sha.length() > 8 ? sha.substring(0, 8) : sha;
        }
        return "unknown";
    }
}
