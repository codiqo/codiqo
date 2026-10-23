package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.base.Joiner;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import io.codiqo.llm.client.LlmJson;
import io.codiqo.util.RequestAuthorizer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewAnswersTest {
    private static final String REVIEW = """
            {"blocking":[],"major":[],"minor":[],"unitLabels":[],"observations":["adds a cancel endpoint"],"notChecked":[]}""";
    private static final String OTHER_REVIEW = """
            {"blocking":[],"major":[],"minor":[],"unitLabels":[{"file":"a/Refunds.java","signature":"refund(Order)","category":"ROUTINE","reason":"adds a guard"}],
             "observations":["adds a refund endpoint"],"notChecked":[]}""";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private ReviewAnswers answers;
    private int nextId;

    @BeforeEach
    void start() throws Exception {
        RunArgs args = new RunArgs();
        args.setReviewAnswerAttempts(2);
        answers = ReviewAnswers.start(args, new NoopLog());
        answers.expectFromAnySession(AnswerTool.REVIEW, LocalReviewer::readReviewerAnswer);
    }
    @AfterEach
    void stop() {
        answers.close();
        http.close();
    }
    /** any process on the machine can reach the port, so only the review's own secret is served, and only over POST */
    @Test
    void onlyTheReviewsSecretIsServed() throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(answers.endpoint().getUrl()));
        assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), http.send(request.copy().header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer("guess"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED.code(), http.send(request.copy().header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(answers.endpoint().getSecret()))
                .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    @Test
    void theAnswerToolsAreListedWithTheirSchemas() throws Exception {
        assertEquals("2025-11-25", rpc("initialize", mapper.createObjectNode().put("protocolVersion", "2025-11-25")).path("result").path("protocolVersion").asString());

        JsonNode tools = rpc("tools/list", mapper.createObjectNode()).path("result").path("tools");
        assertEquals(List.of("submit_review", "submit_answer", "submit_labels", "submit_triage", ReviewAnswers.REVIEWS_TOOL),
                tools.valueStream().map(tool -> tool.path("name").asString()).toList());
        assertEquals(List.of("blocking", "major", "minor", "unitLabels", "observations", "notChecked"),
                tools.get(0).path("inputSchema").path("required").valueStream().map(JsonNode::asString).toList());
    }
    /**
     * A reviewer's first accepted review is its answer: the coordinator once continued a reviewer that had answered with
     * "reply with an empty report", and a reply taking the review's place would have dropped its findings.
     */
    @Test
    void aReviewIsTakenFromAnySessionAndTheFirstOneStands() throws Exception {
        JsonNode first = call("ses_r1", "submit_review", REVIEW);
        JsonNode second = call("ses_r1", "submit_review", OTHER_REVIEW);

        assertFalse(first.path("isError").asBoolean(), first.toString());
        assertTrue(text(second).contains("already accepted"), second.toString());
        assertEquals(List.of("adds a cancel endpoint"), answers.accepted("ses_r1", LocalReviewer.ReviewerAnswer.class).orElseThrow().getParsed().getObservations());
        assertEquals(Optional.empty(), answers.refusal("ses_r1"));
    }
    /** a refused call says what is wrong, and a corrected call in the same turn is the answer */
    @Test
    void aRefusedAnswerSaysWhatIsWrongAndCanBeCorrected() throws Exception {
        answers.expect("ses_fork", AnswerTool.LABELS, ReviewAnswersTest::labels);

        JsonNode refused = call("ses_fork", "submit_labels", "{\"labels\":[]}");
        assertTrue(refused.path("isError").asBoolean(), refused.toString());
        assertTrue(text(refused).contains("not labelled: [U1]") && text(refused).contains("call codiqo_submit_labels again"), text(refused));
        assertEquals(Optional.of("not labelled: [U1]"), answers.refusal("ses_fork"));

        JsonNode corrected = call("ses_fork", "submit_labels", "{\"labels\":[{\"id\":\"U1\",\"category\":\"ROUTINE\"}]}");
        assertFalse(corrected.path("isError").asBoolean(), corrected.toString());
        assertEquals("U1", answers.accepted("ses_fork", UnitLabels.class).orElseThrow().getParsed().getLabels().getFirst().getId());
        assertEquals(Optional.empty(), answers.refusal("ses_fork"), "an answer that was accepted in the end is not a refused one");
    }
    /** an agent that keeps sending a wrong answer gets the configured calls, and its answer stays refused after them */
    @Test
    void anAnswerRefusedOnEveryAttemptStaysRefused() throws Exception {
        answers.expect("ses_fork", AnswerTool.LABELS, ReviewAnswersTest::labels);

        call("ses_fork", "submit_labels", "{\"labels\":[]}");
        JsonNode last = call("ses_fork", "submit_labels", "{\"labels\":[]}");
        JsonNode late = call("ses_fork", "submit_labels", "{\"labels\":[{\"id\":\"U1\",\"category\":\"ROUTINE\"}]}");

        assertTrue(text(last).contains("That was the last call read"), text(last));
        assertTrue(late.path("isError").asBoolean() && text(late).contains("refused 2 times"), text(late));
        assertEquals(Optional.empty(), answers.accepted("ses_fork", UnitLabels.class));
        assertEquals(Optional.of("not labelled: [U1]"), answers.refusal("ses_fork"));
    }
    /** what Jackson cannot read is named by the answer's own path, never by the Java classes the path runs through */
    @Test
    void anUnreadableValueIsNamedByItsPathInTheAnswer() throws Exception {
        JsonNode refused = call("ses_r1", "submit_review", """
                {"blocking":[],"major":[],"minor":[],"unitLabels":["Router.route (ROUTINE)"],"observations":[],"notChecked":[]}""");

        assertTrue(text(refused).startsWith("Refused: unitLabels[0]: "), text(refused));
    }
    /**
     * A fork carries the coordinator's prompt, which names the coordinator's tool, so calling it by mistake is turned
     * away with the right tool's name and costs the fork none of its attempts.
     */
    @Test
    void aCallForAnotherToolThanTheOneAskedForIsTurnedAwayWithoutCounting() throws Exception {
        answers.expect("ses_fork", AnswerTool.LABELS, ReviewAnswersTest::labels);

        JsonNode refused = call("ses_fork", "submit_answer", "{\"tasks\":[]}");
        call("ses_fork", "submit_answer", "{\"tasks\":[]}");
        call("ses_fork", "submit_answer", "{\"tasks\":[]}");
        JsonNode corrected = call("ses_fork", "submit_labels", "{\"labels\":[{\"id\":\"U1\",\"category\":\"ROUTINE\"}]}");

        assertTrue(text(refused).contains("this turn is answered with codiqo_submit_labels, not codiqo_submit_answer"), text(refused));
        assertFalse(corrected.path("isError").asBoolean(), corrected.toString());
        assertTrue(answers.accepted("ses_fork", UnitLabels.class).isPresent());
    }
    /** the coordinator's answer merges the reviews, so it is turned away until the coordinator has read every one of them */
    @Test
    void theCoordinatorAnswersOnlyOnceItReadTheReviews() throws Exception {
        answers.expect("ses_coordinator", AnswerTool.ANSWER, arguments -> arguments);
        call("ses_r1", "submit_review", REVIEW);

        JsonNode unread = call("ses_coordinator", "submit_answer", "{\"tasks\":[]}");
        call("ses_coordinator", ReviewAnswers.REVIEWS_TOOL, "{\"unitLabels\":true}");
        JsonNode stillUnread = call("ses_coordinator", "submit_answer", "{\"tasks\":[]}");
        call("ses_coordinator", ReviewAnswers.REVIEWS_TOOL, "{}");
        JsonNode read = call("ses_coordinator", "submit_answer", "{\"tasks\":[]}");

        assertTrue(text(unread).contains("submitted reviews you have not read"), text(unread));
        assertTrue(text(stillUnread).contains("submitted reviews you have not read"), "the labels alone are not the reviews");
        assertFalse(read.path("isError").asBoolean(), read.toString());
        assertEquals(Optional.empty(), answers.refusal("ses_coordinator"), "neither turned-away call counts as a refused answer");
    }
    @Test
    void argumentsThatAreNotAnObjectAreRefused() {
        JsonNode refused = answers.answerCall("ses_r1", "submit_review", mapper.getNodeFactory().stringNode(REVIEW));

        assertTrue(text(refused).startsWith("Refused: the arguments are not a JSON object"), text(refused));
        assertEquals(Optional.of("the arguments are not a JSON object"), answers.refusal("ses_r1"));
    }
    /** the coordinator calling a fork's tool during its own review is turned away without any answer being touched */
    @Test
    void aSessionAskedForNothingIsTurnedAway() throws Exception {
        JsonNode refused = call("ses_unknown", "submit_labels", "{\"labels\":[]}");

        assertTrue(refused.path("isError").asBoolean() && text(refused).contains("nobody asked this session"), refused.toString());
        assertEquals(Optional.empty(), answers.refusal("ses_unknown"));
    }
    /**
     * The coordinator reads the reviews from the server, since a subagent's reply text is only the end of its turn: each
     * with its session, so it can tell the areas apart, and without the unit labels, which only the labelling turn asks for.
     */
    @Test
    void theCoordinatorReadsTheReviewsSubmitted() throws Exception {
        call("ses_r1", "submit_review", REVIEW);
        call("ses_r2", "submit_review", OTHER_REVIEW);

        JsonNode reviews = mapper.readTree(text(call("ses_coordinator", ReviewAnswers.REVIEWS_TOOL, "{}")));
        JsonNode labels = mapper.readTree(text(call("ses_fork", ReviewAnswers.REVIEWS_TOOL, "{\"unitLabels\":true}")));

        assertEquals(List.of("ses_r1", "ses_r2"), reviews.valueStream().map(review -> review.path("sessionID").asString()).toList());
        assertEquals(List.of("adds a cancel endpoint", "adds a refund endpoint"), reviews.valueStream().map(review -> review.path("observations").get(0).asString()).toList());
        assertFalse(reviews.get(1).has("unitLabels"), reviews.toString());
        assertEquals("refund(Order)", labels.get(1).path("unitLabels").get(0).path("signature").asString());
        assertFalse(labels.get(1).has("observations"), labels.toString());
    }
    /** an answer cannot be matched to its session without the session OpenCode names in the call */
    @Test
    void aCallThatNamesNoSessionIsAnError() throws Exception {
        ObjectNode params = mapper.createObjectNode().put("name", "submit_review");
        params.set("arguments", mapper.readTree(REVIEW));

        assertTrue(rpc("tools/call", params).path("error").path("message").asString().contains("named no calling session"));
        IllegalStateException err = assertThrows(IllegalStateException.class, () -> answers.accepted("ses_r1", LocalReviewer.ReviewerAnswer.class));
        assertTrue(err.getMessage().contains("no answer can be matched to its session"), err.getMessage());
    }
    @Test
    void aNotificationIsTakenWithoutAnAnswer() throws Exception {
        HttpResponse<String> response = post(mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized"));

        assertEquals(HttpResponseStatus.ACCEPTED.code(), response.statusCode());
    }
    /** a reader that fails on its own is Codiqo's failure: the review fails with it rather than the agent being told to retry */
    @Test
    void aReaderThatFailsOnItsOwnFailsTheReview() throws Exception {
        answers.expect("ses_fork", AnswerTool.LABELS, arguments -> {
            throw new IllegalStateException("index out of date");
        });

        JsonNode failed = call("ses_fork", "submit_labels", "{\"labels\":[]}");

        assertTrue(text(failed).contains("error on Codiqo's side"), text(failed));
        IllegalStateException err = assertThrows(IllegalStateException.class, () -> answers.accepted("ses_fork", UnitLabels.class));
        assertEquals("index out of date", err.getCause().getMessage());
    }
    private JsonNode call(String sessionId, String tool, String arguments) throws Exception {
        ObjectNode params = mapper.createObjectNode().put("name", tool);
        params.set("arguments", mapper.readTree(arguments));
        params.putObject("_meta").put("ai.opencode/sessionID", sessionId).put("progressToken", 1);
        return rpc("tools/call", params).path("result");
    }
    private JsonNode rpc(String method, ObjectNode params) throws Exception {
        ObjectNode request = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", ++nextId).put("method", method);
        request.set("params", params);
        HttpResponse<String> response = post(request);
        assertEquals(HttpResponseStatus.OK.code(), response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
    private HttpResponse<String> post(JsonNode message) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(answers.endpoint().getUrl()))
                .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(answers.endpoint().getSecret()))
                .header(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString())
                .POST(HttpRequest.BodyPublishers.ofString(message.toString()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }
    /** a labelling reader for one listed unit, U1 */
    private static UnitLabels labels(JsonNode arguments) {
        UnitLabels toReturn = LlmJson.responseMapper().treeToValue(arguments, UnitLabels.class);
        List<String> problems = UnitLabelling.problems(List.of(new ReviewUnit("U1", "a/Service.java", "handle(Request)", "MODIFY", "12-30")), toReturn);
        if (CollectionUtils.isNotEmpty(problems)) {
            throw new ReviewAnswers.RefusedAnswerException(Joiner.on("; ").join(problems));
        }
        return toReturn;
    }
}
