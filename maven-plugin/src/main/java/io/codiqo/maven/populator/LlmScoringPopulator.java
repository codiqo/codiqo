package io.codiqo.maven.populator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
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
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.client.model.ProjectQualityModel;
import io.codiqo.llm.HtmlReportBuilder;
import io.codiqo.llm.LlmResponseMapper;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.ReportBuilder.ReportContext;
import io.codiqo.llm.SubmissionToRequestMapper;
import io.codiqo.llm.client.LlmScoringClient;
import io.codiqo.llm.client.ScoringClient;
import io.codiqo.llm.client.ScoringClient.Params;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.maven.logging.MavenMessageReporter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LlmScoringPopulator implements SubmissionPopulator {
    private final Log log;

    @Override
    public void accept(SubmissionContext ctx) {
        RunArgs args = ctx.getArgs();
        AnalysisSubmissionModel submission = ctx.getSubmissionModel();
        StopWatch stopWatch = StopWatch.createStarted();

        try (LlmScoringClient client = new LlmScoringClient(args, new MavenMessageReporter(log))) {
            SubmissionToRequestMapper mapper = new SubmissionToRequestMapper(args);
            LlmScoringRequest request = mapper.apply(submission);
            PromptContext promptContext = buildPromptContext(submission, args);

            Params params = ScoringClient.Params.builder()
                    .request(request)
                    .context(promptContext)
                    .handler(new ScoringClient.StreamingHandler() {
                        @Override
                        public void onContent(String delta) {
                            if (Objects.nonNull(delta)) {
                                if (delta.length() > 0) {
                                    log.info("LLM responding... (" + delta.length() + " chars)");
                                }
                            }
                        }
                        @Override
                        public void onToolCall(String toolName) {
                            log.info("Tool call: " + toolName);
                        }
                    }).build();
            ScoringResult result = client.score(params);

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
                .revertCommit(Boolean.TRUE.equals(commit.getIsRevert()))
                .revertedCommitId(commit.getRevertedCommitId())
                .repositoryName(ctx.getIndex().getProjectRoot().getName())
                .llmModel(ctx.getLlmModel())
                .analysisDuration(duration)
                .criticalViolationsByModule(extractCriticalViolations(submission))
                .build();

        String html = builder.buildReport(result, request, reportContext);

        String commitSha = commit.getSha();
        File outputDir = ctx.getArgs().getOutputDirectory();
        File htmlFile;
        if (Objects.nonNull(outputDir)) {
            FileUtils.forceMkdir(outputDir);
            htmlFile = new File(outputDir, "codiqo-analysis-" + commitSha + ".html");
        } else {
            htmlFile = Files.createTempFile("codiqo-analysis-", ".html").toFile();
        }
        try (OutputStream stream = Files.newOutputStream(htmlFile.toPath())) {
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

        String commitSha = submission.getCommit().getSha();
        File outputDir = args.getOutputDirectory();
        File file;
        if (Objects.nonNull(outputDir)) {
            FileUtils.forceMkdir(outputDir);
            file = new File(outputDir, "codiqo-analysis-" + commitSha + ".yaml");
        } else {
            file = Files.createTempFile("codiqo-analysis-", ".yaml").toFile();
        }
        String output = yamlMapper.writeValueAsString(analysisResult);
        try (BufferedOutputStream stream = new BufferedOutputStream(Files.newOutputStream(file.toPath()))) {
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
        int linesPerMethodQuantile = 0;

        if (Objects.nonNull(projectMetrics)) {
            totalLines = Optional.ofNullable(projectMetrics.getTotalLines()).orElse(0);
            totalFiles = Optional.ofNullable(projectMetrics.getTotalFiles()).orElse(0);
            totalMethods = Optional.ofNullable(projectMetrics.getTotalMethods()).orElse(0);
            linesPerMethodQuantile = Optional.ofNullable(projectMetrics.getLinesPerMethodQuantile()).orElse(0);
        }

        if (Objects.nonNull(projectQuality) && Objects.nonNull(projectQuality.getCodeUnitsAffected())) {
            codeUnitsAffected = projectQuality.getCodeUnitsAffected();
        }

        return PromptContext.withFullContext(args)
                .projectTotalLines(totalLines)
                .projectTotalFiles(totalFiles)
                .projectTotalMethods(totalMethods)
                .codeUnitsAffected(codeUnitsAffected)
                .linesPerMethodQuantile(linesPerMethodQuantile)
                .build();
    }
    public static Map<String, List<DiagnosticModel>> extractCriticalViolations(AnalysisSubmissionModel submission) {
        Map<String, List<DiagnosticModel>> toReturn = Maps.newHashMap();
        if (Objects.nonNull(submission.getProject()) && CollectionUtils.isNotEmpty(submission.getProject().getModules())) {
            for (ModuleModel module : submission.getProject().getModules()) {
                if (Objects.nonNull(module.getQuality()) && CollectionUtils.isNotEmpty(module.getQuality().getCriticalViolations())) {
                    toReturn.put(module.getId(), module.getQuality().getCriticalViolations());
                }
            }
        }
        return toReturn;
    }
}
