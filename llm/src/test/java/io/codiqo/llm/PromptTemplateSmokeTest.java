package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.PromptBuilder.UserMessageResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChangeType;

class PromptTemplateSmokeTest {
    private static final Log NOOP_LOG = new Log() {
        @Override
        public boolean isLoggable(Level level) { return false; }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) { }
        @Override
        public void log(Level level, String message, Object... formatArgs) { }
        @Override
        public int numErrors() { return 0; }
    };

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
                .path("pom.xml")
                .changeType(FileChangeType.MODIFIED)
                .language("xml")
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
                .fileChanges(Lists.newArrayList(eligible, ineligible))
                .codeBlockChanges(Collections.emptyList())
                .build();

        PromptContext ctx = PromptContext.builder().args(new RunArgs()).build();
        UserMessageResult result = builder.buildUserMessageWithScores(request, ctx);
        String rendered = result.getMessage();

        assertTrue(rendered.contains("Eligible files"), "table header missing");
        assertTrue(rendered.contains("| src/main/java/Foo.java | 15 | 11 |"), "eligible file row missing");
        assertFalse(rendered.contains("| pom.xml | 3 | 1 |"), "ineligible file leaked into the table");
    }
    @Test
    void systemPromptRendersFilteringRule() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        String rendered = builder.buildSystemPrompt(PromptContext.builder().args(new RunArgs()).build());

        assertTrue(rendered.contains("SEMANTIC LABELS ONLY"), "phase-2 STEP 1a heading missing");
        assertTrue(rendered.contains("blockKinds"), "blockKinds contract missing");
        assertTrue(rendered.contains("|B<n>|"), "annotation format explanation missing");
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
    void validationFeedbackRendersOneBulletPerFailureWithDistinctReasons() {
        ThymeleafPromptBuilder builder = new ThymeleafPromptBuilder(new RunArgs(), NOOP_LOG);
        FinalScoreCalculator.ValidationFailure unknownBlock = new FinalScoreCalculator.ValidationFailure(
                "RootApplicationFilter.java", FinalScoreCalculator.FailureReason.UNKNOWN_BLOCK,
                List.of("B9"), List.of("B1", "B2", "B3"));
        FinalScoreCalculator.ValidationFailure unknownDeleted = new FinalScoreCalculator.ValidationFailure(
                "Foo.java", FinalScoreCalculator.FailureReason.UNKNOWN_DELETED_LINE,
                List.of("95"), List.of("94", "97"));
        FinalScoreCalculator.ValidationReport report = new FinalScoreCalculator.ValidationReport(Lists.newArrayList(unknownBlock, unknownDeleted));

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
                .fileChanges(Lists.newArrayList(fileChange))
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
        FinalScoreCalculator.ValidationReport report = new FinalScoreCalculator.ValidationReport(Lists.newArrayList(unknownAdded));

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
}
