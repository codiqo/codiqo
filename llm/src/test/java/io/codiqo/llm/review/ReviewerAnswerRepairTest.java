package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reviewer answers that excluded six production commits on 13 Oct 2026, each a review the agents had finished: a
 * label written as a bare string, a lone observation written as a string, and an answer cut off mid-object; and the
 * review topics and unchecked items two later commits wrote where the labels go.
 */
class ReviewerAnswerRepairTest {
    private static final String SESSION = "ses_reviewer";
    private static final String FORK = "ses_fork";
    private static final String UNREADABLE_MESSAGE = "msg_unreadable";
    private static final String GOOD = """
            {"unitLabels":[{"file":"server/Router.java","signature":"route()","category":"MECHANICAL","reason":"annotation-only"}],
             "observations":["Adds a router"]}""";
    private static final String BARE_LABEL = """
            {"unitLabels":["CreateCouponResponse.getFreeSpin(): MECHANICAL — annotation-only change, adds @JsonIgnore with no logic change"],
             "observations":["Adds a router"]}""";
    private static final String LONE_OBSERVATION = """
            {"unitLabels":[{"file":"server/Router.java","signature":"route()","category":"MECHANICAL","reason":"annotation-only"}],
             "observations":"Adds a router"}""";
    /** the shape of the 13 Oct 2026 failures: review topics and unchecked items written where the labels go */
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

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> prompts = Lists.newCopyOnWriteArrayList();
    /** the reviewer's answer after each correction: index 0 is what a correction prompt leaves behind first */
    private final List<String> corrections = Lists.newCopyOnWriteArrayList();
    private final List<String> forkPrompts = Lists.newCopyOnWriteArrayList();
    private final List<String> forkedBefore = Lists.newCopyOnWriteArrayList();
    private final List<String> forks = Lists.newCopyOnWriteArrayList();
    /** what the fork answers to its final-answer prompt */
    private String forkAnswer;
    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        url = RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
    }
    @AfterEach
    void stop() {
        server.stop(0);
    }
    @Test
    void aLabelWrittenAsABareStringIsCorrectedOnce() throws Exception {
        corrections.add(GOOD);

        LocalReviewer.ReviewerAnswer answer = read(BARE_LABEL, true);

        assertEquals(1, prompts.size(), "one correction turn");
        assertTrue(prompts.get(0).startsWith("Your last answer could not be read: Cannot construct instance of"), prompts.get(0));
        assertEquals("route()", answer.getLabels().get(0).getSignature());
        assertTrue(answer.isCorrected());
    }
    @Test
    void aLoneObservationWrittenAsAStringIsReadWithoutACorrection() throws Exception {
        LocalReviewer.ReviewerAnswer answer = read(LONE_OBSERVATION, true);

        assertEquals(List.of("Adds a router"), answer.getObservations());
        assertEquals(0, prompts.size(), "nothing to correct: the string says what a one-item list says");
    }
    @Test
    void topicsWrittenWhereTheLabelsGoAreCorrectedNeverTakenAsLabels() throws Exception {
        corrections.add(NOT_CHECKED);

        LocalReviewer.ReviewerAnswer answer = read(TOPICS_AS_LABELS, true);

        assertEquals(1, prompts.size(), "one correction turn");
        assertTrue(prompts.get(0).contains("Cannot construct instance of"), prompts.get(0));
        assertEquals(1, answer.getLabels().size());
        assertEquals(List.of("tests were not executed", "the enum default comes from an external protobuf artifact"), answer.getNotChecked());
        assertTrue(answer.isCorrected());
    }
    @Test
    void whatTheReviewerCouldNotVerifyIsReadBesideItsLabels() throws Exception {
        LocalReviewer.ReviewerAnswer answer = read(NOT_CHECKED, true);

        assertEquals(0, prompts.size());
        assertEquals(1, answer.getLabels().size());
        assertEquals(2, answer.getNotChecked().size());
        assertEquals(List.of("tests were not executed"), read(LONE_NOT_CHECKED, true).getNotChecked(), "a lone note says what a one-item list says");
        assertEquals(List.of(), read(GOOD, true).getNotChecked(), "a reviewer that checked everything may leave the field out");
    }
    @Test
    void anAnswerCutOffMidObjectIsCorrectedOnce() throws Exception {
        corrections.add(GOOD);

        LocalReviewer.ReviewerAnswer answer = read(CUT, true);

        assertEquals(1, prompts.size());
        assertEquals(1, answer.getLabels().size());
    }
    /** the correction left the bad answer in the context; a fork from before it is asked for the final answer only */
    @Test
    void anAnswerStillUnreadableAfterTheCorrectionIsAskedOfAForkFromBeforeIt() throws Exception {
        corrections.add(BARE_LABEL);
        forkAnswer = GOOD;

        LocalReviewer.ReviewerAnswer answer = read(BARE_LABEL, true);

        assertEquals(1, prompts.size(), "one correction in the reviewer's session");
        assertEquals(List.of(UNREADABLE_MESSAGE), forkedBefore, "the fork leaves out the first unreadable answer and all after it");
        assertEquals(List.of(FORK), forks, "the fork goes into the review's trace");
        assertTrue(forkPrompts.get(0).startsWith("Your review is done: do not call any tool"), forkPrompts.get(0));
        assertEquals("route()", answer.getLabels().get(0).getSignature());
        assertTrue(answer.isCorrected());
    }
    @Test
    void anAnswerTheForkCannotGiveEitherFailsTheReview() {
        corrections.add(BARE_LABEL);
        forkAnswer = BARE_LABEL;

        assertThrows(JacksonException.class, () -> read(BARE_LABEL, true));
        assertEquals(1, prompts.size());
        assertEquals(1, forkPrompts.size(), "one fork, then the review fails instead of judging with less");
    }
    @Test
    void anAnswerWithoutLabelsIsCorrectedNotReadAsLabellingNothing() throws Exception {
        corrections.add(GOOD);

        LocalReviewer.ReviewerAnswer answer = read(ONE_FINDING, true);

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("no \"unitLabels\" field"), prompts.get(0));
        assertEquals(1, answer.getLabels().size());
    }
    @Test
    void anUnreadableAnswerBeyondTheReviewsCorrectionsFailsWithoutAsking() {
        assertThrows(JacksonException.class, () -> read(BARE_LABEL, false));
        assertEquals(0, prompts.size());
    }
    @Test
    void aReadableAnswerIsTakenAsItIs() throws Exception {
        LocalReviewer.ReviewerAnswer answer = read(GOOD, true);

        assertEquals(0, prompts.size());
        assertEquals(1, answer.getLabels().size());
        assertEquals(List.of("Adds a router"), answer.getObservations());
    }
    private LocalReviewer.ReviewerAnswer read(String first, boolean mayCorrect) throws IOException {
        try (OpenCodeClient client = new OpenCodeClient(url, "pw", Duration.ofSeconds(10))) {
            return LocalReviewer.readReviewerAnswer(client, SESSION, first, mayCorrect, forks::add, new NoopLog());
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = StringUtils.EMPTY;
        if (route.equals("POST /api/session/" + SESSION + "/prompt")) {
            prompts.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("text").asString());
        } else if (route.equals("GET /api/session/" + SESSION + "/message")) {
            body = newestAssistant(prompts.isEmpty() ? UNREADABLE_MESSAGE : "msg_corrected", corrections.get(Math.max(0, prompts.size() - 1)));
        } else if (route.equals("POST /api/session/" + SESSION + "/fork")) {
            forkedBefore.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("before").asString());
            ObjectNode created = JsonNodeFactory.instance.objectNode();
            created.putObject("data").put("id", FORK);
            body = created.toString();
        } else if (route.equals("POST /api/session/" + FORK + "/prompt")) {
            forkPrompts.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("text").asString());
        } else if (route.equals("GET /api/session/" + FORK + "/message")) {
            body = newestAssistant("msg_fork", forkAnswer);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(HttpResponseStatus.OK.code(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    private static String newestAssistant(String id, String text) {
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("id", id).put("type", "assistant").put("finish", "stop").put("text", text);
        ObjectNode toReturn = JsonNodeFactory.instance.objectNode();
        toReturn.putArray("data").add(message);
        toReturn.putObject("cursor").putNull("next");
        return toReturn.toString();
    }
}
