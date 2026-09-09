package io.codiqo.maven.populator;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.plugin.logging.Log;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.ModuleQualityModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.client.model.ProjectQualityModel;
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
import io.codiqo.submit.AnalysisResultDump;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.SubmissionPopulator;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LlmScoringPopulator implements SubmissionPopulator {
    private final Log log;
    private final ExecutorService executor;
    private final boolean preferYaml;

    @Override
    public void accept(SubmissionContext ctx) {
        RunArgs args = ctx.getArgs();
        AnalysisSubmissionModel submission = ctx.getSubmissionModel();
        StopWatch stopWatch = StopWatch.createStarted();

        MavenMessageReporter reporter = new MavenMessageReporter(log);
        for (;;) {
            try (LlmScoringClient client = new LlmScoringClient(args, executor, reporter)) {
                SubmissionToRequestMapper mapper = new SubmissionToRequestMapper(args);
                LlmScoringRequest request = mapper.apply(submission);
                PromptContext promptContext = buildPromptContext(submission, args);

                Params params = ScoringClient.Params.builder()
                        .request(request)
                        .context(promptContext)
                        .handler(progressHandler(log)).build();
                ScoringResult result = client.score(params);

                stopWatch.stop();
                Duration duration = Duration.ofMillis(stopWatch.getTime());

                ctx.setLlmScoringResponse(result.getResponse());
                ctx.setLlmScoringResult(result);
                ctx.setLlmAnalysisDuration(duration);
                ctx.setLlmModel(args.getLlmModel());

                LlmScoringResponse response = result.getResponse();
                log.info(String.format(Locale.ROOT, "LLM Score: %.0f (%s) | Review: %d/10 | %dms | %d tokens | %d bugs",
                        response.getScore(),
                        response.getChangeClassification(),
                        response.getRequiresSeniorReview(),
                        duration.toMillis(),
                        result.getTotalTokens(),
                        response.getTotalBugCount()));

                // the console report is the point of a local scoring run; dumpAnalysis only governs writing to disk
                printConsoleReport(ctx, result, request, duration);

                if (args.isDumpAnalysis()) {
                    new AnalysisResultDump(args, preferYaml, ctx.getLogFactory().getLogger(AnalysisResultDump.class))
                            .accept(submission, result, duration);
                }
                return;
            } catch (Exception err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    /** streams the model's progress to the build log. Shared with ScoreFromFileMojo, which drives the same client */
    public static ScoringClient.StreamingHandler progressHandler(Log log) {
        return new ScoringClient.StreamingHandler() {
            @Override
            public void onContent(String delta) {
                if (StringUtils.isNotEmpty(delta)) {
                    log.info("LLM responding... (" + delta.length() + " chars)");
                }
            }
            @Override
            public void onToolCall(String toolName) {
                log.info("Tool call: " + toolName);
            }
        };
    }
    private void printConsoleReport(SubmissionContext ctx, ScoringResult result, LlmScoringRequest request, Duration duration) {
        ConsoleReportBuilder builder = new ConsoleReportBuilder(ctx.getArgs());
        AnalysisSubmissionModel submission = ctx.getSubmissionModel();
        CommitModel commit = submission.getCommit();

        ReportContext reportContext = ReportContext.builder()
                .commitId(commit.getSha())
                .author(commit.getAuthor())
                .authorEmail(commit.getAuthorEmail())
                .timestamp(commit.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)))
                .commitMessage(commit.getMessage())
                .branches(commit.getBranches())
                .mergeCommit(Boolean.TRUE.equals(commit.getIsMerge()))
                .revertCommit(Boolean.TRUE.equals(commit.getIsRevert()))
                .revertedCommitId(commit.getRevertedCommitId())
                /**
                 * the submission's project name first: a commit analysis indexes a throw away work tree,
                 * so the index root is a temporary directory name ("codiqo6290787775051848632")
                 */
                .repositoryName(StringUtils.defaultIfBlank(
                        submission.getProject().getName(),
                        Objects.nonNull(ctx.getIndex()) ? ctx.getIndex().getProjectRoot().getName() : null))
                .llmModel(ctx.getLlmModel())
                .analysisDuration(duration)
                .criticalViolationsByModule(extractCriticalViolations(submission))
                .build();

        for (String line : StringUtils.splitPreserveAllTokens(builder.buildReport(result, request, reportContext), CharUtils.LF)) {
            log.info(line);
        }
    }
    public static PromptContext buildPromptContext(AnalysisSubmissionModel submission, RunArgs args) {
        ProjectMetricsModel projectMetrics = submission.getProjectMetrics();
        ProjectQualityModel projectQuality = submission.getProjectQuality();

        long totalStatements = 0;
        int totalFiles = 0;
        int totalMethods = 0;
        int codeUnitsAffected = 0;
        int methodCapQuantileProd = 0;
        int methodCapQuantileTest = 0;
        int constructorCapQuantileProd = 0;
        int constructorCapQuantileTest = 0;

        if (Objects.nonNull(projectMetrics)) {
            totalStatements = Optional.ofNullable(projectMetrics.getTotalStatements()).orElse(0);
            totalFiles = Optional.ofNullable(projectMetrics.getTotalFiles()).orElse(0);
            totalMethods = Optional.ofNullable(projectMetrics.getTotalMethods()).orElse(0);
            methodCapQuantileProd = Optional.ofNullable(projectMetrics.getMethodCapQuantileProd()).orElse(0);
            methodCapQuantileTest = Optional.ofNullable(projectMetrics.getMethodCapQuantileTest()).orElse(0);
            constructorCapQuantileProd = Optional.ofNullable(projectMetrics.getConstructorCapQuantileProd()).orElse(0);
            constructorCapQuantileTest = Optional.ofNullable(projectMetrics.getConstructorCapQuantileTest()).orElse(0);
        }

        if (Objects.nonNull(projectQuality) && Objects.nonNull(projectQuality.getCodeUnitsAffected())) {
            codeUnitsAffected = projectQuality.getCodeUnitsAffected();
        }

        return PromptContext.withFullContext(args)
                .conventionGuidance(StringUtils.defaultString(submission.getAgentInstructions()))
                .projectTotalStatements(totalStatements)
                .projectTotalFiles(totalFiles)
                .projectTotalMethods(totalMethods)
                .codeUnitsAffected(codeUnitsAffected)
                .methodCapQuantileProd(methodCapQuantileProd)
                .methodCapQuantileTest(methodCapQuantileTest)
                .constructorCapQuantileProd(constructorCapQuantileProd)
                .constructorCapQuantileTest(constructorCapQuantileTest)
                .build();
    }
    public static Map<String, List<DiagnosticModel>> extractCriticalViolations(AnalysisSubmissionModel submission) {
        Map<String, List<DiagnosticModel>> toReturn = new HashMap<>();
        if (Objects.nonNull(submission.getProject())) {
            for (ModuleModel module : CollectionUtils.emptyIfNull(submission.getProject().getModules())) {
                ModuleQualityModel quality = module.getQuality();
                if (Objects.nonNull(quality) && CollectionUtils.isNotEmpty(quality.getCriticalViolations())) {
                    toReturn.put(module.getId(), quality.getCriticalViolations());
                }
            }
        }
        return toReturn;
    }
}
