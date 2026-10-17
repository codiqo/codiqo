package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;

/**
 * The reviewer answers that excluded six commits of one project on 13 Oct 2026: a label written as a bare string, a lone
 * observation written as a string, an answer cut off mid-object, and the review topics and unchecked items two later
 * commits wrote where the labels go. A shape that says what the format says is read; any other fails the review.
 */
class ReviewerAnswerTest {
    private static final String GOOD = """
            {"unitLabels":[{"file":"server/Router.java","signature":"route()","category":"MECHANICAL","reason":"annotation-only"}],
             "observations":["Adds a router"]}""";
    private static final String BARE_LABEL = """
            {"unitLabels":["CreateFreeSpinsResponse.getFreeSpin(): MECHANICAL — annotation-only change, adds @JsonIgnore with no logic change"],
             "observations":["Adds a router"]}""";
    private static final String LONE_OBSERVATION = """
            {"unitLabels":[{"file":"server/Router.java","signature":"route()","category":"MECHANICAL","reason":"annotation-only"}],
             "observations":"Adds a router"}""";
    private static final String TOPICS_AS_LABELS = """
            {"unitLabels":["range-notation-fidelity-vs-toString","tests were not executed"],
             "observations":["Adds a router"]}""";
    private static final String NOT_CHECKED = """
            {"unitLabels":[{"file":"server/Router.java","signature":"route()","category":"MECHANICAL"}],
             "observations":["Adds a router"],
             "notChecked":["tests were not executed","the enum default comes from an external protobuf artifact"]}""";
    private static final String LONE_NOT_CHECKED = """
            {"unitLabels":[],"observations":[],"notChecked":"tests were not executed"}""";
    /** a production review, 14 Oct 2026: a reviewer's final answer that was one finding, with no labels at all */
    private static final String ONE_FINDING = """
            {"type":"LOGIC","title":"Non-idempotent insert","description":"fails on re-execution","file":"a/B.java","line":3,"confidence":"HIGH"}""";
    private static final String CUT = """
            {"unitLabels":[{"file":"server/Router.java","signature":"route()\"""";

    @Test
    void aReadableAnswerIsTakenAsItIs() {
        LocalReviewer.ReviewerAnswer answer = LocalReviewer.readReviewerAnswer(GOOD);

        assertEquals("route()", answer.getLabels().getFirst().getSignature());
        assertEquals(List.of("Adds a router"), answer.getObservations());
        assertEquals(List.of(), answer.getNotChecked(), "a reviewer that checked everything may leave the field out");
    }
    @Test
    void aLoneObservationWrittenAsAStringIsReadAsOne() {
        assertEquals(List.of("Adds a router"), LocalReviewer.readReviewerAnswer(LONE_OBSERVATION).getObservations());
    }
    @Test
    void whatTheReviewerCouldNotVerifyIsReadBesideItsLabels() {
        LocalReviewer.ReviewerAnswer answer = LocalReviewer.readReviewerAnswer(NOT_CHECKED);

        assertEquals(1, answer.getLabels().size());
        assertEquals(List.of("tests were not executed", "the enum default comes from an external protobuf artifact"), answer.getNotChecked());
        assertEquals(List.of("tests were not executed"), LocalReviewer.readReviewerAnswer(LONE_NOT_CHECKED).getNotChecked(), "a lone note says what a one-item list says");
    }
    @Test
    void aLabelWrittenAsABareStringFails() {
        assertThrows(JacksonException.class, () -> LocalReviewer.readReviewerAnswer(BARE_LABEL));
    }
    @Test
    void topicsWrittenWhereTheLabelsGoFail() {
        assertThrows(JacksonException.class, () -> LocalReviewer.readReviewerAnswer(TOPICS_AS_LABELS));
    }
    @Test
    void anAnswerCutOffMidObjectFails() {
        assertThrows(JacksonException.class, () -> LocalReviewer.readReviewerAnswer(CUT));
    }
    @Test
    void aNullLabelFails() {
        JacksonException err = assertThrows(JacksonException.class, () -> LocalReviewer.readReviewerAnswer("""
                {"unitLabels":[null],"observations":[]}"""));

        assertTrue(err.getOriginalMessage().contains("null entry"), err.getOriginalMessage());
    }
    @Test
    void anAnswerWithoutLabelsFailsRatherThanLabellingNothing() {
        JacksonException err = assertThrows(JacksonException.class, () -> LocalReviewer.readReviewerAnswer(ONE_FINDING));

        assertTrue(err.getOriginalMessage().contains("no \"unitLabels\" field"), err.getOriginalMessage());
    }
}
