package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;


import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.PromptBuilder.UserMessageResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChangeType;

class PromptTemplateSmokeTest {
    private static final Log NOOP_LOG = NoopLog.INSTANCE;

    @Test
    void userPromptRendersPerFileTargetTable() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);

        FileChange eligible = FileChange.builder()
                .path("src/main/java/Foo.java")
                .changeType(FileChangeType.MODIFIED)
                .language("java")
                .linesAdded(15)
                .linesDeleted(11)
                .linesJustificationRequired(true)
                .diff("dummy")
                .build();
        FileChange ineligible = FileChange.builder()
                .path("src/main/resources/application.yaml")
                .changeType(FileChangeType.MODIFIED)
                .language("yaml")
                .linesAdded(3)
                .linesDeleted(1)
                .linesJustificationRequired(false)
                .diff("dummy")
                .build();

        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder()
                        .linesAdded(18)
                        .linesDeleted(12)
                        .totalLinesChanged(30)
                        .totalFilesChanged(2)
                        .codeBlocksModified(1)
                        .codeBlocksAdded(0)
                        .build())
                .fileChanges(new ArrayList<>(List.of(eligible, ineligible)))
                .codeBlockChanges(Collections.emptyList())
                .build();

        PromptContext ctx = PromptContext.builder().args(new RunArgs()).build();
        UserMessageResult result = builder.buildUserMessageWithScores(request, ctx);
        String rendered = result.getMessage();

        assertTrue(rendered.contains("Eligible files"), "table header missing");
        assertTrue(rendered.contains("| src/main/java/Foo.java | 15 | 11 |"), "eligible file row missing");
        assertFalse(rendered.contains("| src/main/resources/application.yaml | 3 | 1 |"), "ineligible file leaked into the table");
    }
    @Test
    void userPromptGroundsChangedLineCoverageAndCpdSplit() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);

        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder()
                        .linesAdded(13).linesDeleted(2).totalLinesChanged(15).totalFilesChanged(1).build())
                .fileChanges(new ArrayList<>(List.of(FileChange.builder()
                        .path("Foo.java").changeType(FileChangeType.MODIFIED).language("java")
                        .linesAdded(13).linesDeleted(2).linesJustificationRequired(true).diff("dummy").build())))
                .codeBlockChanges(Collections.emptyList())
                .coverage(LlmScoringRequest.CoverageInfo.builder()
                        .changedLineCoverage(7.6923076923076925)
                        .addedLineCoverage(0.0)
                        .modifiedLineCoverage(100.0)
                        .build())
                .duplication(LlmScoringRequest.DuplicationInfo.builder()
                        .changedLineCpdPercent(2.5)
                        .addedLineCpdPercent(0.0)
                        .modifiedLineCpdPercent(25.0)
                        .build())
                .build();

        String rendered = builder.buildUserMessageWithScores(request, PromptContext.builder().args(new RunArgs()).build()).getMessage();

        assertTrue(rendered.contains("**Changed lines covered:** 7.7%"), "changed-line coverage not grounded from deterministic value");
        assertTrue(rendered.contains("**Added lines (new code) covered:** 0.0%"), "added-line coverage missing");
        assertTrue(rendered.contains("**Modified lines (rewritten code) covered:** 100.0%"), "modified-line coverage missing");
        assertTrue(rendered.contains("| Changed-line duplication (deterministic) | 2.5% of changed lines |"), "changed-line CPD missing");
        assertTrue(rendered.contains("| Added-line duplication (deterministic) | 0.0% of added lines |"), "added-line CPD missing");
        assertTrue(rendered.contains("| Modified-line duplication (deterministic) | 25.0% of modified lines |"), "modified-line CPD missing");
    }
    @Test
    void userPromptShowsNaForAbsentChangedLineCategories() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);

        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder()
                        .linesAdded(10).linesDeleted(0).totalLinesChanged(10).totalFilesChanged(1).build())
                .fileChanges(new ArrayList<>(List.of(FileChange.builder()
                        .path("Foo.java").changeType(FileChangeType.ADDED).language("java")
                        .linesAdded(10).linesDeleted(0).linesJustificationRequired(true).diff("dummy").build())))
                .codeBlockChanges(Collections.emptyList())
                .coverage(LlmScoringRequest.CoverageInfo.builder()
                        .changedLineCoverage(0.0)
                        .addedLineCoverage(0.0)
                        .modifiedLineCoverage(null)
                        .build())
                .duplication(LlmScoringRequest.DuplicationInfo.builder()
                        .changedLineCpdPercent(null)
                        .addedLineCpdPercent(null)
                        .modifiedLineCpdPercent(null)
                        .build())
                .build();

        String rendered = builder.buildUserMessageWithScores(request, PromptContext.builder().args(new RunArgs()).build()).getMessage();

        assertTrue(rendered.contains("**Added lines (new code) covered:** 0.0%"), "added-line coverage should still render");
        assertTrue(rendered.contains("**Modified lines (rewritten code) covered:** n/a"), "absent modified lines should render n/a, not 0%");
        assertTrue(rendered.contains("| Changed-line duplication (deterministic) | n/a"), "absent CPD should render n/a");
    }
    @Test
    void userPromptRendersSlimMovedCandidateTable() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        String moveDiff = String.join("\n",
                "--- a/Foo.java",
                "+++ b/Foo.java",
                "@@ -10,3 +10,1 @@",
                " context",
                "-registry.register(handler, priority);",
                " context2",
                "@@ -30,1 +28,3 @@",
                " context3",
                "+registry.register(handler, priority);",
                " context4");
        FileChange fc = FileChange.builder()
                .path("Foo.java")
                .changeType(FileChangeType.MODIFIED)
                .language("java")
                .linesAdded(1)
                .linesDeleted(1)
                .linesJustificationRequired(true)
                .diff(moveDiff)
                .build();
        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder()
                        .linesAdded(1)
                        .linesDeleted(1)
                        .totalLinesChanged(2)
                        .totalFilesChanged(1)
                        .build())
                .fileChanges(new ArrayList<>(List.of(fc)))
                .codeBlockChanges(Collections.emptyList())
                .build();

        String rendered = builder.buildUserMessageWithScores(request, PromptContext.builder().args(new RunArgs()).build()).getMessage();

        assertTrue(rendered.contains("| Id | Deleted (file:line) | Reappears as (file:line) |"), "slim 3-column header missing");
        assertTrue(rendered.contains("| M1 | Foo.java:11 | Foo.java:29 |"), "candidate row missing");
        assertFalse(rendered.contains("| M1 | Foo.java:11 | Foo.java:29 | `"), "content column must not be rendered");
        assertTrue(rendered.contains("movedPairs"), "pointer to movedPairs for extra relocations missing");
    }
    @Test
    void systemPromptRendersFilteringRule() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        String rendered = builder.buildSystemPrompt(PromptContext.builder().args(new RunArgs()).build());

        assertTrue(rendered.contains("SEMANTIC LABELS ONLY"), "phase-2 STEP 1a heading missing");
        assertTrue(rendered.contains("blockKinds"), "blockKinds contract missing");
        assertTrue(rendered.contains("|B<n>|"), "annotation format explanation missing");
        assertTrue(rendered.contains("movedPairs"), "movedPairs judgment missing");
    }
    @Test
    void systemPromptRendersTaskClassificationGuidance() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        String rendered = builder.buildSystemPrompt(PromptContext.builder().args(new RunArgs()).build());

        assertTrue(rendered.contains("TASK CLASSIFICATION"), "task classification section missing");
        assertTrue(rendered.contains("taskTypes"), "taskTypes instruction missing");
        assertTrue(rendered.contains("taskComplexity"), "taskComplexity instruction missing");
        assertTrue(rendered.contains("taskComplexityRationale"), "taskComplexityRationale instruction missing");
        assertTrue(rendered.contains("feature|bug_fix|refactor|test|docs|chore|infra|dep_update|security_patch|performance|deduplication|style|data_migration"),
                "taskType enum values missing from JSON schema example");
        assertTrue(rendered.contains("Junior-appropriate") && rendered.contains("Senior-appropriate"),
                "seniority band anchors missing");
    }
    @Test
    void userPromptRendersDegradedBannerOnlyWithBuildFailure() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        PromptContext ctx = PromptContext.builder().args(new RunArgs()).build();

        LlmScoringRequest degraded = degradedRequest();
        String rendered = builder.buildUserMessageWithScores(degraded, ctx).getMessage();

        assertTrue(rendered.contains("DEGRADED ANALYSIS — BUILD FAILED AT THIS COMMIT"), "degraded banner missing");
        assertTrue(rendered.contains("BUILD_FAILURE"), "failure category missing");
        assertTrue(rendered.contains("[ERROR] cannot find symbol"), "failure reason missing");
        assertTrue(rendered.contains("symbol: class CurrencyMultiplier"), "failure detail excerpt missing");

        degraded.setBuildFailure(null);
        String normal = builder.buildUserMessageWithScores(degraded, ctx).getMessage();
        assertFalse(normal.contains("DEGRADED ANALYSIS"), "degraded banner leaked into a normal analysis");
    }
    @Test
    void systemPromptRendersDegradedModeSection() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        String rendered = builder.buildSystemPrompt(PromptContext.builder().args(new RunArgs()).build());

        assertTrue(rendered.contains("DEGRADED MODE (build-failed commits)"), "degraded mode section missing");
        assertTrue(rendered.contains("Quality multiplier must not exceed 1.0"), "degraded multiplier cap rule missing");
    }

    @Test
    void validationFeedbackRendersOneBulletPerFailureWithDistinctReasons() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        FinalScoreCalculator.ValidationFailure unknownBlock = new FinalScoreCalculator.ValidationFailure(
                "RootApplicationFilter.java", FinalScoreCalculator.FailureReason.UNKNOWN_BLOCK,
                List.of("B9"), List.of("B1", "B2", "B3"));
        FinalScoreCalculator.ValidationFailure unknownDeleted = new FinalScoreCalculator.ValidationFailure(
                "Foo.java", FinalScoreCalculator.FailureReason.UNKNOWN_DELETED_LINE,
                List.of("95"), List.of("94", "97"));
        FinalScoreCalculator.ValidationReport report = new FinalScoreCalculator.ValidationReport(new ArrayList<>(List.of(unknownBlock, unknownDeleted)));

        String rendered = builder.buildValidationFeedback(report);

        long bulletLines = rendered.lines().filter(line -> line.startsWith("- `")).count();
        assertEquals(2, bulletLines, "expected one bullet per failure");

        String blockLine = findBulletFor(rendered, unknownBlock.getFilePath());
        assertTrue(blockLine.contains("B9"), "offending block id missing");
        assertTrue(blockLine.contains("B1, B2, B3"), "valid block ids missing");

        String deletedLine = findBulletFor(rendered, unknownDeleted.getFilePath());
        assertNotEquals(blockLine, deletedLine, "distinct FailureReasons must render distinct messages");
    }
    @Test
    void userPromptAnnotatesDiffLineNumbersAndRestoresOriginal() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        String diff = String.join("\n",
                "--- a/Foo.java",
                "+++ b/Foo.java",
                "@@ -10,3 +10,3 @@",
                " context",
                "-old line",
                "+new line");
        FileChange fileChange = FileChange.builder()
                .path("src/main/java/Foo.java")
                .changeType(FileChangeType.MODIFIED)
                .language("java")
                .linesAdded(1)
                .linesDeleted(1)
                .linesJustificationRequired(true)
                .diff(diff)
                .build();
        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder()
                        .linesAdded(1)
                        .linesDeleted(1)
                        .totalLinesChanged(2)
                        .totalFilesChanged(1)
                        .build())
                .fileChanges(new ArrayList<>(List.of(fileChange)))
                .codeBlockChanges(Collections.emptyList())
                .build();

        String rendered = builder.buildUserMessageWithScores(request, PromptContext.builder().args(new RunArgs()).build()).getMessage();

        assertTrue(rendered.contains("-11|B1|old line"), "deleted line not annotated with number and block id in requestJson");
        assertTrue(rendered.contains("+11|B1|new line"), "added line not annotated with number and block id in requestJson");
        assertEquals(diff, fileChange.getDiff(), "original diff must be restored after prompt building");
    }
    @Test
    void validationFeedbackRendersUnknownLineFailuresWithValidNumbers() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        FinalScoreCalculator.ValidationFailure unknownAdded = new FinalScoreCalculator.ValidationFailure(
                "Foo.java", FinalScoreCalculator.FailureReason.UNKNOWN_ADDED_LINE,
                List.of("281", "282"), List.of("288", "290", "292"));
        FinalScoreCalculator.ValidationReport report = new FinalScoreCalculator.ValidationReport(new ArrayList<>(List.of(unknownAdded)));

        String rendered = builder.buildValidationFeedback(report);

        assertTrue(rendered.contains("281, 282"), "offending numbers missing from feedback");
        assertTrue(rendered.contains("288, 290, 292"), "valid numbers missing from feedback");
        assertTrue(rendered.contains("+N|"), "feedback must point the model at the number prefixes");
    }
    private static String findBulletFor(String rendered, String filePath) {
        return rendered.lines()
                .filter(line -> line.startsWith("- `") && line.contains(filePath))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bullet found for " + filePath));
    }
    private static LlmScoringRequest degradedRequest() {
        return LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder()
                        .linesAdded(5).linesDeleted(2).totalLinesChanged(7).totalFilesChanged(1).build())
                .fileChanges(new ArrayList<>(List.of(FileChange.builder()
                        .path("Foo.java").changeType(FileChangeType.MODIFIED).language("java")
                        .linesAdded(5).linesDeleted(2).linesJustificationRequired(true).diff("dummy").build())))
                .codeBlockChanges(Collections.emptyList())
                .buildFailure(LlmScoringRequest.BuildFailureInfo.builder()
                        .reason("[ERROR] cannot find symbol")
                        .category("BUILD_FAILURE")
                        .detail("symbol: class CurrencyMultiplier")
                        .build())
                .build();
    }
}
