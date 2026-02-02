package io.codiqo.maven;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
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
import io.codiqo.llm.VolumeScoreCalculator;
import io.codiqo.llm.client.LlmScoringClient;
import io.codiqo.llm.client.ScoringClient;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.maven.populator.LlmScoringPopulator;

@Mojo(name = "score-from-file", requiresProject = false)
public class ScoreFromFileMojo extends AbstractMojo {
    private static final String ENV_PREFIX = "env:";

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

    @Parameter(property = "codiqo.llm.enableWebSearchTool", defaultValue = "false")
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
            getLog().info("files in submission: " + submission.getFiles().size());
            getLog().info("code block changes: " + request.getCodeBlockChanges().size());
            getLog().info("total callers: " + request.getCodeBlockChanges().stream().mapToInt(m -> m.getCallerCount()).sum());

            VolumeScoreCalculator volumeCalc = new VolumeScoreCalculator(args);
            VolumeScoreCalculator.CpdPreComputed cpdPre = volumeCalc.calculateCpdPenalty(request);
            VolumeScoreCalculator.StaticAnalysisPreComputed saPre = volumeCalc.calculateStaticAnalysisPenalty(request);
            getLog().info("CPD effectivePenalty: " + cpdPre.getEffectivePenalty());
            getLog().info("CPD category: " + cpdPre.getCategory());
            getLog().info("CPD recommendedImpact: " + cpdPre.getRecommendedImpact());
            getLog().info("static analysis total: " + saPre.getErrorCount());
            getLog().info("static analysis introduced: " + saPre.getIntroducedCount());
            getLog().info("static analysis preExisting: " + saPre.getPreExistingCount());
            getLog().info("static analysis recommendedImpact: " + saPre.getRecommendedImpact());

            StopWatch stopWatch = StopWatch.createStarted();
            ScoringResult result;
            try (LlmScoringClient client = new LlmScoringClient(args)) {
                result = client.score(request, promptContext, new ScoringClient.StreamingHandler() {
                    private int contentChars = 0;

                    @Override
                    public void onContent(String delta) {
                        contentChars += delta.length();
                        if (contentChars % 1000 < delta.length()) {
                            getLog().info("LLM responding... (" + contentChars + " chars)");
                        }
                    }
                    @Override
                    public void onToolCall(String toolName) {
                        getLog().info("tool call: " + toolName);
                    }
                });
            }
            stopWatch.stop();

            LlmScoringResponse response = result.getResponse();
            getLog().info("--- LLM SCORING RESULTS ---");
            getLog().info("duration: " + stopWatch);
            getLog().info("tokens total: " + result.getTotalTokens());
            getLog().info("tokens prompt: " + result.getPromptTokens());
            getLog().info("tokens completion: " + result.getCompletionTokens());
            getLog().info("score: " + response.getScore());
            getLog().info("classification: " + response.getChangeClassification());
            getLog().info("score calculation: " + response.getScoreCalculation());
            getLog().info("senior review: " + response.getRequiresSeniorReview() + "/10");
            getLog().info("summary: " + response.getSummary());
            if (Objects.nonNull(response.getBugs())) {
                getLog().info("bugs blocking: " + CollectionUtils.size(response.getBugs().getBlocking()));
                getLog().info("bugs major: " + CollectionUtils.size(response.getBugs().getMajor()));
                getLog().info("bugs minor: " + CollectionUtils.size(response.getBugs().getMinor()));
            }

            if (dumpAnalysis) {
                FileUtils.forceMkdir(outputDirectory);
                generateHtmlReport(args, submission, result, request, stopWatch);
                dumpResultYaml(submission, result, stopWatch, args);
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
        toReturn.setLlmEnableWebSearchTool(llmEnableWebSearchTool);
        toReturn.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        toReturn.setDumpAnalysis(dumpAnalysis);
        Optional.ofNullable(outputDirectory).ifPresent(toReturn::setOutputDirectory);
        if (StringUtils.isNotEmpty(llmApiKey)) {
            if (llmApiKey.startsWith(ENV_PREFIX)) {
                String envVar = llmApiKey.substring(ENV_PREFIX.length());
                toReturn.setLlmApiKey(System.getenv(envVar));
            } else {
                toReturn.setLlmApiKey(llmApiKey);
            }
        }
        return toReturn;
    }
    private void generateHtmlReport(
            RunArgs args,
            AnalysisSubmissionModel submission,
            ScoringResult result,
            LlmScoringRequest request,
            StopWatch stopWatch) throws IOException {
        HtmlReportBuilder builder = new HtmlReportBuilder(args);
        CommitModel commit = submission.getCommit();

        ReportContext.ReportContextBuilder contextBuilder = ReportContext.builder()
                .repositoryName(submission.getProject().getName())
                .llmModel(llmModel)
                .analysisDuration(Duration.ofMillis(stopWatch.getTime()));

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

        String commitSha = submission.getCommit().getSha();
        File htmlFile = new File(outputDirectory, "codiqo-analysis-" + commitSha + ".html");
        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(htmlFile))) {
            stream.write(html.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }
        getLog().info("HTML report: " + htmlFile.getAbsolutePath());
    }
    private void dumpResultYaml(AnalysisSubmissionModel submission, ScoringResult result, StopWatch stopWatch, RunArgs args) throws IOException {
        AnalysisResultModel analysisResult = new AnalysisResultModel();

        analysisResult.setProject(submission.getProject());
        analysisResult.setCommit(submission.getCommit());
        analysisResult.setFiles(submission.getFiles());
        analysisResult.setDependencies(submission.getDependencies());
        analysisResult.setDuplication(submission.getDuplication());
        analysisResult.setProjectMetrics(submission.getProjectMetrics());
        analysisResult.setProjectQuality(submission.getProjectQuality());
        analysisResult.setFullProjectCoverage(submission.getFullProjectCoverage());

        LlmResponseMapper mapper = new LlmResponseMapper();
        mapper.mapToAnalysisResult(result.getResponse(), analysisResult);
        analysisResult.setLlmAnalysis(LlmResponseMapper.mapLlmAnalysis(result, Duration.ofMillis(stopWatch.getTime()), args.getLlmModel()));

        ObjectMapper yamlMapper = new YAMLMapper();
        yamlMapper.setDefaultPropertyInclusion(Include.NON_NULL);
        yamlMapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);
        yamlMapper.registerModule(new JavaTimeModule());

        String commitSha = submission.getCommit().getSha();
        File file = new File(outputDirectory, "codiqo-analysis-" + commitSha + ".yaml");
        String output = yamlMapper.writeValueAsString(analysisResult);
        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(file))) {
            stream.write(output.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }
        getLog().info("YAML analysis: " + file.getAbsolutePath());
    }
}
