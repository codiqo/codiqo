package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.schema.LlmScoringResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class LocalReviewerTest {
    private static final String REVIEW = """
            {"blocking":[],"major":[],"minor":[],"unitLabels":[],"observations":["adds a cancel endpoint"],"notChecked":[]}""";

    /** a coordinator that omits a severity or writes it as null still gives a review whose every severity can be listed */
    @Test
    void aMissingOrNullSeverityReadsAsEmpty() {
        LlmScoringResponse.Bugs bugs = LocalReviewer.readBugs("""
                {"blocking": [{"title": "t", "file": "A.java", "line": 3}], "minor": null}
                """);

        assertEquals(1, bugs.getBlocking().size());
        assertEquals(List.of(), bugs.getMajor());
        assertEquals(List.of(), bugs.getMinor());
    }
    /** the format the coordinator is asked for: the changed signatures as the list, the other two fields beside them */
    @Test
    void theCoordinatorWritesTheSignatureChangesFlat() {
        LlmScoringResponse.BlastRadiusAnalysis blast = LocalReviewer.readAssessment("""
                {"summary": "s", "blastRadiusAnalysis": {"moduleType": "CORE_LIBRARY", "signatureChanges": ["Mails.send(String, Locale)"],
                 "hasBreakingChanges": true, "breakingChangeType": "PARAMETER_ADDED", "explanation": "every caller of send passes a locale now"}}
                """).getBlastRadiusAnalysis();

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
        LlmScoringResponse.SignatureChanges typeOnly = LocalReviewer.readAssessment("""
                {"blastRadiusAnalysis": {"moduleType": "LEAF_APPLICATION", "signatureChanges": ["OrderEvent.note: String -> Masked<String>"],
                 "breakingChangeType": "PARAMETER_CHANGED", "explanation": "the one outside consumer passes the event through"}}
                """).getBlastRadiusAnalysis().getSignatureChanges();
        LlmScoringResponse.SignatureChanges flagOnly = LocalReviewer.readAssessment("""
                {"blastRadiusAnalysis": {"moduleType": "LEAF_APPLICATION", "signatureChanges": ["Digests.legacy(Order) removed"],
                 "hasBreakingChanges": false, "explanation": "the removed overload had no callers"}}
                """).getBlastRadiusAnalysis().getSignatureChanges();

        assertEquals(LlmScoringResponse.BreakingChangeType.PARAMETER_CHANGED, typeOnly.getBreakingChangeType());
        assertFalse(typeOnly.isHasBreakingChanges());
        assertEquals(List.of("Digests.legacy(Order) removed"), flagOnly.getChangedSignatures());
        assertFalse(flagOnly.isHasBreakingChanges());
    }
    /** a blastRadiusAnalysis without any signature field carries no signature changes, not a judgment that there are none */
    @Test
    void aBlastRadiusWithoutSignatureFieldsHasNoSignatureChanges() {
        LlmScoringResponse.BlastRadiusAnalysis blast = LocalReviewer.readAssessment("""
                {"blastRadiusAnalysis": {"moduleType": "LEAF_APPLICATION", "explanation": "a deployable service nothing else depends on"}}
                """).getBlastRadiusAnalysis();

        assertEquals(LlmScoringResponse.ModuleType.LEAF_APPLICATION, blast.getModuleType());
        assertNull(blast.getSignatureChanges());
    }
    /** a reviewer the coordinator continued after it answered, with a no-op prompt: its review is its first turn's answer */
    @Test
    void aReviewerContinuedAfterItsReviewKeepsTheReview() throws Exception {
        List<List<JsonNode>> turns = List.of(
                List.of(user("Review part of commit abc."), assistant("tool-calls", "Step 1 of 256."), assistant("stop", REVIEW)),
                List.of(user("Do not review anything. Answer with the single word yes or no."), assistant("stop", "no")));

        assertEquals(Optional.of(REVIEW), LocalReviewer.reviewerAnswer("ses_r", turns));
    }
    /** a reviewer cut off at its step cap, which the coordinator continued to finish: its review is its second turn's answer */
    @Test
    void aReviewerContinuedAfterItsStepCapAnswersInItsSecondTurn() throws Exception {
        List<List<JsonNode>> turns = List.of(
                List.of(user("Review part of commit abc."), assistant("tool-calls", "Step 82 of 128: read the bean definitions.")),
                List.of(user("Your previous run was cut off. Please finish the review."), assistant("stop", REVIEW)));

        assertEquals(Optional.of(REVIEW), LocalReviewer.reviewerAnswer("ses_r", turns));
    }
    /** a first turn that ended with an error, which the coordinator continued and got the review from, is not the review's failure */
    @Test
    void aReviewerContinuedAfterAnErrorAnswersInItsSecondTurn() throws Exception {
        List<List<JsonNode>> turns = List.of(
                List.of(user("Review part of commit abc."), failed("provider.timeout")),
                List.of(user("Please continue the review."), assistant("stop", REVIEW)));

        assertEquals(Optional.of(REVIEW), LocalReviewer.reviewerAnswer("ses_r", turns));
    }
    /** a reviewer cut off at its step cap and never continued is unanswered: its progress line is not an answer */
    @Test
    void aReviewerCutOffAtItsStepCapIsUnanswered() throws Exception {
        List<List<JsonNode>> turns = List.of(List.of(user("Review part of commit abc."), assistant("tool-calls", "Step 82 of 128: read the bean definitions.")));

        assertEquals(Optional.empty(), LocalReviewer.reviewerAnswer("ses_r", turns));
    }
    /** a reviewer whose last turn ended with an error and no turn answered fails with that error */
    @Test
    void aReviewerThatOnlyFailedFailsWithItsError() {
        List<List<JsonNode>> turns = List.of(List.of(user("Review part of commit abc."), failed("provider.timeout")));

        IOException err = assertThrows(IOException.class, () -> LocalReviewer.reviewerAnswer("ses_r", turns));
        assertEquals("session ses_r ended its turn with provider.timeout: no answer in time", err.getMessage());
    }
    /** the nested object is not this answer's format: an answer written that way fails instead of losing its signatures */
    @Test
    void theNestedSignatureChangesObjectFailsTheRead() {
        assertThrows(JacksonException.class, () -> LocalReviewer.readAssessment("""
                {"blastRadiusAnalysis": {"moduleType": "CORE_LIBRARY", "signatureChanges": {"hasBreakingChanges": true, "changedSignatures": ["Api.send(String)"]}}}
                """));
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
        LlmScoringResponse.Bugs bugs = LocalReviewer.readBugs("""
                {"blocking": [{"title": "t", "file": "A.java", "line": 3}], "major": [{"title": "u", "file": "B.java", "line": 5}]}
                """);
        List<SessionUsage> sessions = List.of(
                new SessionUsage("c", "coordinator", "m", 1000, 9000, 200, 50),
                new SessionUsage("r", "reviewer", "m", 500, 4000, 100, 0));
        LocalReview review = new LocalReview("abc", bugs, sessions, Duration.ofMinutes(4), "{}", null, 3, List.of(), List.of());

        assertEquals("2 bugs, 2 sessions, 3 reviewers, 1500 input / 350 output tokens", LocalReviewer.progressDetail(review));
    }
    private static JsonNode user(String text) {
        return JsonNodeFactory.instance.objectNode().put("type", "user").put("text", text);
    }
    private static JsonNode assistant(String finish, String text) {
        return JsonNodeFactory.instance.objectNode().put("type", "assistant").put("finish", finish).put("text", text);
    }
    private static JsonNode failed(String type) {
        ObjectNode toReturn = JsonNodeFactory.instance.objectNode().put("type", "assistant").put("finish", "error");
        toReturn.putObject("error").put("type", type).put("message", "no answer in time");
        return toReturn;
    }
}
