package io.codiqo.maven.populator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.ReportBuilder.ReportContext;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CallerInfo;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChangeType;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.RiskAssessment;

class ConsoleReportBuilderTest {
    @Test
    void rendersEverySectionAndLeavesNoUnresolvedExpression() {
        String report = new ConsoleReportBuilder(new RunArgs()).buildReport(result(), request(), context());

        assertTrue(report.contains("Codiqo — Commit Analysis"), "title missing");
        assertTrue(report.contains("0c643723"), "commit sha not shortened into the header");
        assertTrue(report.contains("Andrey"), "author missing");
        assertTrue(report.contains("62.47 × 0.95 + 2.81"), "score calculation not carried through verbatim");
        assertTrue(report.contains("key dimensions:"), "dimensions section missing");
        assertTrue(report.contains("Architecture Impact"), "assessed dimension missing");
        assertTrue(report.contains("changed files:"), "changed files section missing");
        assertTrue(report.contains("Foo.java"), "changed file row missing");
        assertTrue(report.contains("findings"), "findings section missing");
        assertTrue(report.contains("summary:"), "summary section missing");

        // callers are counted from the request, split prod/test
        assertTrue(report.contains("2 callers"), "caller total missing: blast line was " + blastLine(report));
        assertTrue(report.contains("prod: 1, test: 1"), "caller split missing: blast line was " + blastLine(report));

        assertFalse(report.contains("[("), "unresolved Thymeleaf expression left in the output");
        assertFalse(report.contains("${"), "unresolved variable left in the output");
    }
    /**
     * a dimension the model left unscored is "not touched by this change", not a failed gate — the
     * primitive qualityGateMet defaults to false, so reporting it verbatim would invent a failure
     */
    @Test
    void unassessedDimensionRendersAsDashRatherThanFailedGate() {
        LlmScoringResponse response = response();
        response.getQualityDimensions().setConcurrencyRisk(DimensionScore.builder().rationale("not touched").build());

        String report = new ConsoleReportBuilder(new RunArgs())
                .buildReport(ScoringResult.builder().response(response).build(), request(), context());

        String row = report.lines().filter(line -> line.contains("Concurrency Risk")).findFirst().orElse("");
        assertTrue(row.contains("-"), "unassessed dimension should render a dash, was: " + row);
        assertFalse(row.contains("FAILED"), "unassessed dimension must not be reported as a failed gate, was: " + row);
    }
    @Test
    void emptyAnalysisStillRendersWithoutBlowingUp() {
        LlmScoringRequest bare = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().build())
                .fileChanges(new ArrayList<>())
                .codeBlockChanges(new ArrayList<>())
                .build();

        String report = new ConsoleReportBuilder(new RunArgs())
                .buildReport(ScoringResult.builder().response(new LlmScoringResponse()).build(), bare, context());

        assertTrue(report.contains("(none)"), "empty sections should say so explicitly");
        assertFalse(report.contains("[("), "unresolved Thymeleaf expression left in the output");
    }
    /**
     * The header used to take its total from ChangeSummary.totalFilesChanged — which counts only files
     * with effective changes — while the table rendered every file in the payload, so the page
     * contradicted itself on 5 of 10 real commits. Worse, the prod count was that total minus a
     * payload-derived test count, which rendered "prod: -1" on a deletion-heavy commit. Both now come
     * from the rendered list, so the split sums to the row count and cannot go negative.
     */
    @Test
    void fileCountsComeFromTheRenderedListAndNeverGoNegative() {
        FileChange prod = FileChange.builder().path("Main.java").changeType(FileChangeType.MODIFIED).linesAdded(3).build();
        FileChange test1 = FileChange.builder().path("OneTest.java").changeType(FileChangeType.DELETED).isTest(true).linesDeleted(80).build();
        FileChange test2 = FileChange.builder().path("TwoTest.java").changeType(FileChangeType.DELETED).isTest(true).linesDeleted(40).build();
        FileChange config = FileChange.builder().path("pom.xml").changeType(FileChangeType.MODIFIED).isConfig(true).linesAdded(2).build();

        LlmScoringRequest request = LlmScoringRequest.builder()
                // deliberately far below the payload size, as the effective-change count is on a real deletion commit
                .changeSummary(ChangeSummary.builder().totalFilesChanged(1).totalLinesChanged(125).build())
                .fileChanges(new ArrayList<>(List.of(prod, test1, test2, config)))
                .codeBlockChanges(new ArrayList<>())
                .build();

        String report = new ConsoleReportBuilder(new RunArgs())
                .buildReport(ScoringResult.builder().response(response()).build(), request, context());
        String line = report.lines().filter(l -> l.startsWith("files:")).findFirst().orElse("");

        assertTrue(line.contains("4 changed"), "total must count the rendered files, was: " + line);
        assertTrue(line.contains("prod: 1"), "config must not be counted as prod, was: " + line);
        assertTrue(line.contains("test: 2"), "test split wrong, was: " + line);
        assertTrue(line.contains("config: 1"), "config split missing, was: " + line);
        assertFalse(line.contains("-1"), "file counts must never render negative, was: " + line);
    }
    /**
     * a commit reachable from many refs carries all of them. Rendering them inline put 18 branch names
     * on one ~500-char line in the first real run, which is unreadable and not what the reader wants.
     */
    @Test
    void manyBranchesAreCappedRatherThanListedInline() {
        ReportContext manyBranches = ReportContext.builder()
                .commitId("2639b2da9")
                .timestamp("2026-06-24 09:14")
                .analysisDuration(Duration.ofSeconds(28))
                .branches(new ArrayList<>(List.of("dev", "main", "prev-release", "fix-build", "ENB-31", "PAY-12997")))
                .build();

        String report = new ConsoleReportBuilder(new RunArgs()).buildReport(result(), request(), manyBranches);
        String header = report.lines().filter(line -> line.startsWith("commit:")).findFirst().orElse("");

        assertTrue(header.contains("+4 more"), "branch overflow not summarised, was: " + header);
        assertFalse(header.contains("PAY-12997"), "every branch still listed inline, was: " + header);
        assertTrue(header.length() < 120, "commit header should stay one readable line, was " + header.length() + " chars");
    }
    private static String blastLine(String report) {
        return report.lines().filter(line -> line.startsWith("blast:")).findFirst().orElse("<no blast line>");
    }
    private static ScoringResult result() {
        return ScoringResult.builder().response(response()).build();
    }
    private static LlmScoringResponse response() {
        LlmScoringResponse toReturn = new LlmScoringResponse();
        toReturn.setScore(62.0);
        toReturn.setScoreCalculation("62.47 × 0.95 + 2.81 = 62.16 ≈ 62");
        toReturn.setRequiresSeniorReview(7);
        toReturn.setSummary("Refactors the resteasy channel and moves filter wiring into the bootstrap module.");
        toReturn.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(0.95).build());
        toReturn.setRiskAssessment(RiskAssessment.builder().riskScore(59).build());
        toReturn.setQualityDimensions(QualityDimensions.builder()
                .architectureImpact(DimensionScore.builder().score(7).qualityGateMet(true).build())
                .build());
        return toReturn;
    }
    private static LlmScoringRequest request() {
        FileChange file = FileChange.builder()
                .path("bootstrap-core/src/main/java/com/turbospaces/Foo.java")
                .changeType(FileChangeType.MODIFIED)
                .language("java")
                .linesAdded(15)
                .linesDeleted(11)
                .build();

        List<CallerInfo> callers = new ArrayList<>(List.of(
                CallerInfo.builder().callerMethod("prodCaller").isTestCaller(false).build(),
                CallerInfo.builder().callerMethod("testCaller").isTestCaller(true).build()));

        CodeBlockChange block = CodeBlockChange.builder()
                .file(file.getPath())
                .signature("doWork()")
                .callers(callers)
                .build();

        return LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder()
                        .totalFilesChanged(1)
                        .totalLinesChanged(26)
                        .codeBlocksModified(1)
                        .build())
                .fileChanges(new ArrayList<>(List.of(file)))
                .codeBlockChanges(new ArrayList<>(List.of(block)))
                .build();
    }
    private static ReportContext context() {
        return ReportContext.builder()
                .commitId("0c6437232be1cda1dad83486a7a5794529786c4c")
                .author("Andrey Borisov")
                .authorEmail("andrey@xcxcxc.org")
                .timestamp("2026-07-10 10:56")
                .commitMessage("refactor resteasy channel\n\nlonger body that must not reach the header")
                .branches(new ArrayList<>(List.of("main")))
                .repositoryName("bootstrap-parent")
                .llmModel("kimi-k2.7-code:cloud")
                .analysisDuration(Duration.ofSeconds(258))
                .build();
    }
}
