package io.codiqo.maven.populator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.plugin.logging.Log;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.client.model.ProjectQualityModel;
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
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LlmScoringPopulator implements SubmissionPopulator {
    private final Log log;

    @Override
    public void accept(SubmissionContext ctx) {
        RunArgs args = ctx.getArgs();
        AnalysisSubmissionModel submission = ctx.getSubmissionModel();
        StopWatch stopWatch = StopWatch.createStarted();

        try (LlmScoringClient client = new LlmScoringClient(args)) {
            SubmissionToRequestMapper mapper = new SubmissionToRequestMapper(args);
            LlmScoringRequest request = mapper.apply(submission);
            PromptContext promptContext = buildPromptContext(submission, args);

            ScoringResult result = client.score(request, promptContext, new ScoringClient.StreamingHandler() {
                private int contentChars = 0;

                @Override
                public void onContent(String delta) {
                    contentChars += delta.length();
                    if (contentChars % 1000 < delta.length()) {
                        log.info("LLM responding... (" + contentChars + " chars)");
                    }
                }
                @Override
                public void onToolCall(String toolName) {
                    log.info("Tool call: " + toolName);
                }
            });

            stopWatch.stop();
            Duration duration = Duration.ofMillis(stopWatch.getTime());

            ctx.setLlmScoringResponse(result.getResponse());
            ctx.setLlmScoringResult(result);
            ctx.setLlmAnalysisDuration(duration);
            ctx.setLlmModel(args.getLlmModel());

            LlmScoringResponse response = result.getResponse();
            log.info(String.format("LLM Score: %.0f (%s) | Review: %d/10 | %dms | %d tokens | %d bugs",
                    response.getScore(),
                    response.getChangeClassification(),
                    response.getRequiresSeniorReview(),
                    duration.toMillis(),
                    result.getTotalTokens(),
                    response.getTotalBugCount()));

            if (args.isDumpAnalysis()) {
                generateHtmlReport(ctx, result, request, duration);
                dumpResultYaml(submission, result, duration, args);
            }
        } catch (Exception err) {
            ExceptionUtils.wrapAndThrow(err);
        }
    }
    private void generateHtmlReport(SubmissionContext ctx, ScoringResult result, LlmScoringRequest request, Duration duration) throws IOException {
        HtmlReportBuilder builder = new HtmlReportBuilder(ctx.getArgs());
        AnalysisSubmissionModel submission = ctx.getSubmissionModel();
        CommitModel commit = submission.getCommit();

        ReportContext reportContext = ReportContext.builder()
                .commitId(commit.getSha())
                .author(commit.getAuthor())
                .authorEmail(commit.getAuthorEmail())
                .timestamp(commit.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .commitMessage(commit.getMessage())
                .branches(commit.getBranches())
                .mergeCommit(Boolean.TRUE.equals(commit.getIsMerge()))
                .repositoryName(ctx.getIndex().getProjectRoot().getName())
                .llmModel(ctx.getLlmModel())
                .analysisDuration(duration)
                .build();

        String html = builder.buildReport(result, request, reportContext);

        File outputDir = ctx.getArgs().getOutputDirectory();
        File htmlFile;
        if (Objects.nonNull(outputDir)) {
            FileUtils.forceMkdir(outputDir);
            htmlFile = new File(outputDir, "codiqo-analysis.html");
        } else {
            htmlFile = Files.createTempFile("codiqo-analysis-", ".html").toFile();
        }
        try (FileOutputStream stream = new FileOutputStream(htmlFile)) {
            try (BufferedOutputStream bufferedStream = new BufferedOutputStream(stream)) {
                bufferedStream.write(html.getBytes(StandardCharsets.UTF_8));
                bufferedStream.flush();
            }
        }
        log.info("HTML report: " + htmlFile.getAbsolutePath());
    }
    private void dumpResultYaml(AnalysisSubmissionModel submission, ScoringResult result, Duration duration, RunArgs args) throws IOException {
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
        analysisResult.setLlmAnalysis(LlmResponseMapper.mapLlmAnalysis(result, duration, args.getLlmModel()));

        ObjectMapper yamlMapper = new YAMLMapper();
        yamlMapper.setDefaultPropertyInclusion(Include.NON_NULL);
        yamlMapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);
        yamlMapper.registerModule(new JavaTimeModule());

        File outputDir = args.getOutputDirectory();
        File file;
        if (Objects.nonNull(outputDir)) {
            FileUtils.forceMkdir(outputDir);
            file = new File(outputDir, "codiqo-analysis.yaml");
        } else {
            file = Files.createTempFile("codiqo-analysis-", ".yaml").toFile();
        }
        String output = yamlMapper.writeValueAsString(analysisResult);
        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(file))) {
            stream.write(output.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }
        log.info("YAML analysis: " + file.getAbsolutePath());
    }
    public static PromptContext buildPromptContext(AnalysisSubmissionModel submission, RunArgs args) {
        ProjectMetricsModel projectMetrics = submission.getProjectMetrics();
        ProjectQualityModel projectQuality = submission.getProjectQuality();

        long totalLines = 0;
        int totalFiles = 0;
        int totalMethods = 0;
        int codeUnitsAffected = 0;

        if (Objects.nonNull(projectMetrics)) {
            totalLines = Optional.ofNullable(projectMetrics.getTotalLines()).orElse(BigDecimal.ZERO.intValue());
            totalFiles = Optional.ofNullable(projectMetrics.getTotalFiles()).orElse(BigDecimal.ZERO.intValue());
            totalMethods = Optional.ofNullable(projectMetrics.getTotalMethods()).orElse(BigDecimal.ZERO.intValue());
        }

        if (Objects.nonNull(projectQuality) && Objects.nonNull(projectQuality.getCodeUnitsAffected())) {
            codeUnitsAffected = projectQuality.getCodeUnitsAffected();
        }

        return PromptContext.withFullContext(args)
                .projectTotalLines(totalLines)
                .projectTotalFiles(totalFiles)
                .projectTotalMethods(totalMethods)
                .codeUnitsAffected(codeUnitsAffected)
                .build();
    }
}
