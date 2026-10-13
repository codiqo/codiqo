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
 * label written as a bare string, a lone observation written as a string, and an answer cut off mid-object.
 */
class ReviewerAnswerRepairTest {
    private static final String SESSION = "ses_reviewer";
    private static final String GOOD = """
            {"blockCategories":[{"file":"server/Router.java","signature":"route()","category":"MECHANICAL","reason":"annotation-only"}],
             "observations":["Adds a router"]}""";
    private static final String BARE_LABEL = """
            {"blockCategories":["CreateCouponResponse.getFreeSpin(): MECHANICAL — annotation-only change, adds @JsonIgnore with no logic change"],
             "observations":["Adds a router"]}""";
    private static final String LONE_OBSERVATION = """
            {"blockCategories":[{"file":"server/Router.java","signature":"route()","category":"MECHANICAL","reason":"annotation-only"}],
             "observations":"Adds a router"}""";
    private static final String CUT = """
            {"blockCategories":[{"file":"server/Router.java","signature":"route()\"""";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> prompts = Lists.newCopyOnWriteArrayList();
    /** the reviewer's answer after each correction: index 0 is what a correction prompt leaves behind first */
    private final List<String> corrections = Lists.newCopyOnWriteArrayList();
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
    void anAnswerCutOffMidObjectIsCorrectedOnce() throws Exception {
        corrections.add(GOOD);

        LocalReviewer.ReviewerAnswer answer = read(CUT, true);

        assertEquals(1, prompts.size());
        assertEquals(1, answer.getLabels().size());
    }
    @Test
    void anAnswerStillUnreadableAfterTheCorrectionFailsTheReview() {
        corrections.add(BARE_LABEL);

        assertThrows(JacksonException.class, () -> read(BARE_LABEL, true));
        assertEquals(1, prompts.size(), "one correction, then the review fails instead of judging with less");
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
            return LocalReviewer.readReviewerAnswer(client, SESSION, first, mayCorrect, new NoopLog());
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = StringUtils.EMPTY;
        if (route.equals("POST /api/session/" + SESSION + "/prompt")) {
            prompts.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("text").asString());
        } else if (route.equals("GET /api/session/" + SESSION + "/message")) {
            body = newestAssistant(corrections.get(Math.max(0, prompts.size() - 1)));
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(HttpResponseStatus.OK.code(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    private static String newestAssistant(String text) {
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("type", "assistant").put("finish", "stop").put("text", text);
        ObjectNode toReturn = JsonNodeFactory.instance.objectNode();
        toReturn.putArray("data").add(message);
        toReturn.putObject("cursor").putNull("next");
        return toReturn.toString();
    }
}
