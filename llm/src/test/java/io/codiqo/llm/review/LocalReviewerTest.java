package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.client.LlmJson;
import io.codiqo.llm.schema.LlmScoringResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

class LocalReviewerTest {
    /** a coordinator that omits a severity or writes it as null still gives a review whose every severity can be listed */
    @Test
    void aMissingOrNullSeverityReadsAsEmpty() {
        LlmScoringResponse.Bugs bugs = LocalReviewer.readBugs(tree("""
                {"blocking": [{"title": "t", "file": "A.java", "line": 3}], "minor": null}
                """));

        assertEquals(1, bugs.getBlocking().size());
        assertEquals(List.of(), bugs.getMajor());
        assertEquals(List.of(), bugs.getMinor());
    }
    /** the format the coordinator is asked for: the changed signatures as the list, the other two fields beside them */
    @Test
    void theCoordinatorWritesTheSignatureChangesFlat() {
        LlmScoringResponse.BlastRadiusAnalysis blast = LocalReviewer.readAssessment(tree("""
                {"summary": "s", "blastRadiusAnalysis": {"moduleType": "CORE_LIBRARY", "signatureChanges": ["Mails.send(String, Locale)"],
                 "hasBreakingChanges": true, "breakingChangeType": "PARAMETER_ADDED", "explanation": "every caller of send passes a locale now"}}
                """)).getBlastRadiusAnalysis();

        assertEquals(LlmScoringResponse.ModuleType.CORE_LIBRARY, blast.getModuleType());
        assertTrue(blast.getSignatureChanges().isHasBreakingChanges());
        assertEquals(List.of("Mails.send(String, Locale)"), blast.getSignatureChanges().getChangedSignatures());
        assertEquals(LlmScoringResponse.BreakingChangeType.PARAMETER_ADDED, blast.getSignatureChanges().getBreakingChangeType());
        assertEquals("every caller of send passes a locale now", blast.getExplanation());
    }
    /**
     * the two shapes that failed when the nested object was asked for: the list, with only one of the other fields beside
     * it; a left-out hasBreakingChanges reads as not breaking, as it did inside the nested object
     */
    @Test
    void theShapesWrittenForTheNestedObjectAreTheFormatNow() {
        LlmScoringResponse.SignatureChanges typeOnly = LocalReviewer.readAssessment(tree("""
                {"blastRadiusAnalysis": {"moduleType": "LEAF_APPLICATION", "signatureChanges": ["OrderEvent.note: String -> Masked<String>"],
                 "breakingChangeType": "PARAMETER_CHANGED", "explanation": "the one outside consumer passes the event through"}}
                """)).getBlastRadiusAnalysis().getSignatureChanges();
        LlmScoringResponse.SignatureChanges flagOnly = LocalReviewer.readAssessment(tree("""
                {"blastRadiusAnalysis": {"moduleType": "LEAF_APPLICATION", "signatureChanges": ["Digests.legacy(Order) removed"],
                 "hasBreakingChanges": false, "explanation": "the removed overload had no callers"}}
                """)).getBlastRadiusAnalysis().getSignatureChanges();

        assertEquals(LlmScoringResponse.BreakingChangeType.PARAMETER_CHANGED, typeOnly.getBreakingChangeType());
        assertFalse(typeOnly.isHasBreakingChanges());
        assertEquals(List.of("Digests.legacy(Order) removed"), flagOnly.getChangedSignatures());
        assertFalse(flagOnly.isHasBreakingChanges());
    }
    /** a blastRadiusAnalysis without any signature field carries no signature changes, not a judgment that there are none */
    @Test
    void aBlastRadiusWithoutSignatureFieldsHasNoSignatureChanges() {
        LlmScoringResponse.BlastRadiusAnalysis blast = LocalReviewer.readAssessment(tree("""
                {"blastRadiusAnalysis": {"moduleType": "LEAF_APPLICATION", "explanation": "a deployable service nothing else depends on"}}
                """)).getBlastRadiusAnalysis();

        assertEquals(LlmScoringResponse.ModuleType.LEAF_APPLICATION, blast.getModuleType());
        assertNull(blast.getSignatureChanges());
    }
    /** a coordinator once collapsed every reviewer's labels into their category names; the rest must still read */
    @Test
    void aMalformedFieldTakenFromElsewhereDoesNotCostTheRestOfTheAnswer() {
        LlmScoringResponse read = LocalReviewer.readAssessment(tree("""
                {"summary": "Adds a cancel endpoint", "taskComplexity": 6, "blocking": [], "taskTypes": ["feature", "test"],
                 "blockCategories": ["ROUTINE", "MECHANICAL"]}
                """));

        assertEquals("Adds a cancel endpoint", read.getSummary());
        assertEquals(6, read.getTaskComplexity());
        assertEquals(List.of(LlmScoringResponse.TaskType.FEATURE, LlmScoringResponse.TaskType.TEST), read.getTaskTypes());
        assertTrue(CollectionUtils.isEmpty(read.getBlockCategories()), "the malformed labels are dropped, not half-read");
    }
    /** the nested object is not this answer's format: an answer written that way fails instead of losing its signatures */
    @Test
    void theNestedSignatureChangesObjectFailsTheRead() {
        assertThrows(JacksonException.class, () -> LocalReviewer.readAssessment(tree("""
                {"blastRadiusAnalysis": {"moduleType": "CORE_LIBRARY", "signatureChanges": {"hasBreakingChanges": true, "changedSignatures": ["Api.send(String)"]}}}
                """)));
    }
    /** each turn the agents take has its own timeout, so the wait covers every turn: the review, the labelling and the triage */
    @Test
    void theWaitCoversEveryTurn() {
        RunArgs args = new RunArgs();
        args.setReviewTimeout(Duration.ofMinutes(30));
        args.setReviewStartupTimeout(Duration.ofMinutes(1));

        assertEquals(Duration.ofMinutes(1 + 30 * 3), LocalReviewer.longestReview(args));
    }
    /** the review's progress line counts every severity and adds reasoning to the output tokens, as the usage does */
    @Test
    void theProgressLineSummarisesTheReview() {
        LlmScoringResponse.Bugs bugs = LocalReviewer.readBugs(tree("""
                {"blocking": [{"title": "t", "file": "A.java", "line": 3}], "major": [{"title": "u", "file": "B.java", "line": 5}]}
                """));
        List<SessionUsage> sessions = List.of(
                new SessionUsage("c", "coordinator", "m", 1000, 9000, 200, 50),
                new SessionUsage("r", "reviewer", "m", 500, 4000, 100, 0));
        LocalReview review = new LocalReview("abc", bugs, sessions, Duration.ofMinutes(4), "{}", null, 3, List.of(), List.of());

        assertEquals("2 bugs, 2 sessions, 3 reviewers, 1500 input / 350 output tokens", LocalReviewer.progressDetail(review));
    }
    private static JsonNode tree(String arguments) {
        return LlmJson.responseMapper().readTree(arguments);
    }
}
