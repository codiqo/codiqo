package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.llm.client.LlmJson;
import tools.jackson.core.JacksonException;

/**
 * The reviewer answers that excluded six production commits: a label written as a bare string, a lone observation
 * written as a string, an answer cut off mid-object, and the review topics and unchecked items two later commits wrote
 * where the labels go. A shape that says what the format says is read; any other is refused. An answer cut off
 * mid-object no longer reaches the reader: OpenCode parses a tool call's arguments before the call is made.
 */
class ReviewerAnswerTest {
    private static final String GOOD = """
            {"unitLabels":[{"file":"server/Router.java","signature":"route()","category":"MECHANICAL","reason":"annotation-only"}],
             "observations":["Adds a router"]}""";
    private static final String BARE_LABEL = """
            {"unitLabels":["CreateInvoiceResponse.getOrder(): MECHANICAL — annotation-only change, adds @JsonIgnore with no logic change"],
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
    /** a production review: a reviewer's final answer that was one finding, with no labels at all */
    private static final String ONE_FINDING = """
            {"type":"LOGIC","title":"Non-idempotent insert","description":"fails on re-execution","file":"a/B.java","line":3,"confidence":"HIGH"}""";

    @Test
    void aReadableAnswerIsTakenAsItIs() {
        LocalReviewer.ReviewerAnswer answer = read(GOOD);

        assertEquals("route()", answer.getLabels().getFirst().getSignature());
        assertEquals(List.of("Adds a router"), answer.getObservations());
        assertEquals(List.of(), answer.getNotChecked(), "a reviewer that checked everything may leave the field out");
    }
    @Test
    void aLoneObservationWrittenAsAStringIsReadAsOne() {
        assertEquals(List.of("Adds a router"), read(LONE_OBSERVATION).getObservations());
    }
    @Test
    void whatTheReviewerCouldNotVerifyIsReadBesideItsLabels() {
        LocalReviewer.ReviewerAnswer answer = read(NOT_CHECKED);

        assertEquals(1, answer.getLabels().size());
        assertEquals(List.of("tests were not executed", "the enum default comes from an external protobuf artifact"), answer.getNotChecked());
        assertEquals(List.of("tests were not executed"), read(LONE_NOT_CHECKED).getNotChecked(), "a lone note says what a one-item list says");
    }
    @Test
    void aLabelWrittenAsABareStringFails() {
        assertThrows(JacksonException.class, () -> read(BARE_LABEL));
    }
    @Test
    void topicsWrittenWhereTheLabelsGoFail() {
        assertThrows(JacksonException.class, () -> read(TOPICS_AS_LABELS));
    }
    @Test
    void aNullLabelFails() {
        JacksonException err = assertThrows(JacksonException.class, () -> read("""
                {"unitLabels":[null],"observations":[]}"""));

        assertTrue(err.getOriginalMessage().contains("null entry"), err.getOriginalMessage());
    }
    @Test
    void anAnswerWithoutLabelsFailsRatherThanLabellingNothing() {
        JacksonException err = assertThrows(JacksonException.class, () -> read(ONE_FINDING));

        assertTrue(err.getOriginalMessage().contains("no \"unitLabels\" field"), err.getOriginalMessage());
    }
    private static LocalReviewer.ReviewerAnswer read(String arguments) {
        return LocalReviewer.readReviewerAnswer(LlmJson.responseMapper().readTree(arguments));
    }
}
