package io.codiqo.maven.populator;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.ReportBuilder.ReportContext;
import io.codiqo.llm.client.LlmUsage;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CallerInfo;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChangeType;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.BlastRadiusAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.Bug;
import io.codiqo.llm.schema.LlmScoringResponse.Bugs;
import io.codiqo.llm.schema.LlmScoringResponse.ChangeClassification;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.RiskAssessment;
import io.codiqo.llm.schema.LlmScoringResponse.RiskLevel;
import io.codiqo.llm.schema.LlmScoringResponse.Tags;
import io.codiqo.llm.schema.LlmScoringResponse.VolumeScore;

/** Pins the whole console page byte for byte, so a change to its layout is a visible change to a golden file. */
class ConsoleReportBuilderOutputTest {
    private static final int FILES = 27;
    private static final int MAJOR_BUGS = 12;

    @Test
    void fullAnalysisRendersAsPinned() throws Exception {
        String report = new ConsoleReportBuilder(new RunArgs()).buildReport(ScoringResult.builder()
                .response(fullResponse())
                .usage(new LlmUsage(48211, 3120, 51331))
                .build(), fullRequest(), fullContext());

        GoldenOutput.assertMatches("console-full.txt", report);
    }
    @Test
    void emptyAnalysisRendersAsPinned() throws Exception {
        LlmScoringRequest bare = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().build())
                .fileChanges(Lists.newArrayList())
                .codeBlockChanges(Lists.newArrayList())
                .build();
        ReportContext context = ReportContext.builder().analysisDuration(Duration.ofSeconds(3)).build();

        String report = new ConsoleReportBuilder(new RunArgs())
                .buildReport(ScoringResult.builder().response(new LlmScoringResponse()).build(), bare, context);

        GoldenOutput.assertMatches("console-empty.txt", report);
    }
    /** dimensions object present but nothing in it, and findings without a single row */
    @Test
    void assessedNothingRendersAsPinned() throws Exception {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setQualityDimensions(new QualityDimensions());
        response.setBugs(new Bugs());
        response.setTags(Tags.builder().technical(Lists.newArrayList(List.of("http"))).build());
        response.setSummary("   ");
        ReportContext context = ReportContext.builder()
                .commitId("abc")
                .branches(Lists.newArrayList(List.of("dev", "main")))
                .analysisDuration(Duration.ofSeconds(3))
                .build();

        String report = new ConsoleReportBuilder(new RunArgs()).buildReport(ScoringResult.builder().response(response).build(), fullRequest(), context);

        GoldenOutput.assertMatches("console-assessed-nothing.txt", report);
    }
    private static LlmScoringResponse fullResponse() {
        LlmScoringResponse toReturn = new LlmScoringResponse();
        toReturn.setScore(62.5);
        toReturn.setChangeClassification(ChangeClassification.MEDIUM);
        toReturn.setScoreCalculation("62.47 × 0.95 + 2.81 = 62.16 ≈ 62");
        toReturn.setRequiresSeniorReview(7);
        toReturn.setSummary("Refactors the HTTP channel and moves   filter wiring into the core module, so that every\n"
                + "transport shares one pipeline. A deliberately overlong token follows: "
                + StringUtils.repeat("x", 110) + " and then the summary ends after a few more words to wrap again.");
        toReturn.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(0.9549).build());
        toReturn.setRiskAssessment(RiskAssessment.builder().riskScore(59).riskLevel(RiskLevel.MODERATE).build());
        toReturn.setBlastRadiusAnalysis(BlastRadiusAnalysis.builder().riskLevel(RiskLevel.HIGH).build());
        toReturn.setEffortBreakdown(EffortBreakdown.builder()
                .baseEffortScore(41.125)
                .volumeScore(VolumeScore.builder().totalVolumeScore(12.3456).build())
                .build());
        toReturn.setQualityDimensions(QualityDimensions.builder()
                .architectureImpact(DimensionScore.builder().score(7).qualityGateMet(true).build())
                .concurrencyRisk(DimensionScore.builder().rationale("not touched").build())
                .integrationSurface(DimensionScore.builder().score(10).qualityGateMet(false).build())
                .dataIntegrity(DimensionScore.builder().score(3).build())
                .observability(DimensionScore.builder().score(0).qualityGateMet(false).build())
                .testingCoverage(DimensionScore.builder().score(55).build())
                .build());
        toReturn.setTags(Tags.builder()
                .technical(Lists.newArrayList(Arrays.asList("netty", null, "pipeline")))
                .functional(Lists.newArrayList())
                .build());

        List<Bug> major = Lists.newArrayList();
        for (int i = 0; i < MAJOR_BUGS; i++) {
            major.add(Bug.builder().title("major finding " + i).file("core/src/main/java/com/example/Major" + i + ".java").line(i * 7).build());
        }
        toReturn.setBugs(Bugs.builder()
                .blocking(Lists.newArrayList(List.of(
                        Bug.builder()
                                .title("A blocking finding whose title is much longer than the column allows for")
                                .file("very/long/module/name/that/keeps/going/src/main/java/com/example/deeply/nested/Blocking.java")
                                .line(12)
                                .build(),
                        Bug.builder().title("no line or file").build())))
                .major(major)
                .minor(Lists.newArrayList(List.of(
                        Bug.builder().title("minor one").file("a.txt").line(1).build(),
                        Bug.builder().title("minor two").file("b.txt").build(),
                        Bug.builder().title("minor three").file("c.txt").line(3).build())))
                .build());
        return toReturn;
    }
    private static LlmScoringRequest fullRequest() {
        List<FileChange> files = Lists.newArrayList();
        for (int i = 0; i < FILES; i++) {
            files.add(FileChange.builder()
                    .path("module-" + i + "/src/main/java/com/example/Changed" + i + ".java")
                    .changeType(FileChangeType.MODIFIED)
                    .linesAdded(i * 3)
                    .linesDeleted(i % 4)
                    .isTest(i % 5 == 0)
                    .isConfig(i % 7 == 0)
                    .build());
        }
        files.add(FileChange.builder()
                .path("a/really/long/path/that/must/lose/its/head/because/it/exceeds/the/column/core/Foo.java")
                .changeType(FileChangeType.ADDED)
                .linesAdded(500)
                .build());

        CodeBlockChange block = CodeBlockChange.builder()
                .file(files.getLast().getPath())
                .signature("doWork()")
                .callers(Lists.newArrayList(List.of(
                        CallerInfo.builder().callerMethod("prodCaller").isTestCaller(false).build(),
                        CallerInfo.builder().callerMethod("prodCaller2").isTestCaller(false).build(),
                        CallerInfo.builder().callerMethod("testCaller").isTestCaller(true).build())))
                .build();
        CodeBlockChange second = CodeBlockChange.builder().file(files.getLast().getPath()).signature("other()").build();

        return LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder()
                        .totalFilesChanged(3)
                        .totalLinesChanged(1640)
                        .codeBlocksAdded(4)
                        .codeBlocksModified(9)
                        .build())
                .fileChanges(files)
                .codeBlockChanges(Lists.newArrayList(List.of(block, second)))
                .build();
    }
    private static ReportContext fullContext() {
        return ReportContext.builder()
                .commitId("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b")
                .author("Jane Developer")
                .authorEmail("jane@example.com")
                .timestamp("2026-07-10 10:56")
                .commitMessage("  refactor http channel so that every transport shares one pipeline and the filter wiring lives in core\n\nbody")
                .branches(Lists.newArrayList(List.of("main", "dev", "release")))
                .repositoryName("example-service")
                .llmModel("kimi-k2.7-code:cloud")
                .analysisDuration(Duration.ofSeconds(258))
                .build();
    }
}
