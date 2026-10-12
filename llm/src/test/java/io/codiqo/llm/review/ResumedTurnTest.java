package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A turn whose model answer failed on the way is resumed in the same session, as OpenCode 2.0.25 records such a turn:
 * idle, its newest assistant message finished with {@code error}. OpenCode retries a provider error itself only before
 * the answer starts, so a stream cut after its first tokens reaches the review as exactly this.
 */
class ResumedTurnTest {
    private static final String SESSION = "ses_coordinator";
    private static final String CUT = "provider.invalid-output";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> prompts = Lists.newCopyOnWriteArrayList();
    /** what each finished turn left on its newest assistant message: an error type, or null for an answer */
    private final List<String> outcomes = Lists.newCopyOnWriteArrayList();
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
    void aTurnCutOnTheWayIsResumedInTheSameSession() throws Exception {
        outcomes.add(CUT);
        outcomes.add("ok");

        turn("Review commit 1a2b3c of this repository.");

        assertEquals(2, prompts.size(), prompts.toString());
        assertEquals("Review commit 1a2b3c of this repository.", prompts.get(0));
        assertTrue(prompts.get(1).startsWith("Your last step failed on the way from the model: provider.invalid-output: stream cut"), prompts.get(1));
    }
    @Test
    void aRequestTheProviderRefusedIsNotResumed() throws Exception {
        outcomes.add("provider.invalid-request");

        turn("Review commit 1a2b3c of this repository.");

        assertEquals(1, prompts.size(), "resuming would only send the refused request again");
    }
    @Test
    void aTurnThatKeepsFailingIsResumedTwiceAndLeftAsItEnded() throws Exception {
        for (int i = 0; i < 5; i++) {
            outcomes.add(CUT);
        }

        turn("Review commit 1a2b3c of this repository.");

        assertEquals(3, prompts.size(), "the turn and two resumes: reading its answer then fails the review");
    }
    @Test
    void aTurnThatAnsweredIsLeftAlone() throws Exception {
        outcomes.add("ok");

        turn("Review commit 1a2b3c of this repository.");

        assertEquals(1, prompts.size());
    }
    private void turn(String text) throws IOException {
        try (OpenCodeClient client = new OpenCodeClient(url, "pw", Duration.ofSeconds(10))) {
            LocalReviewer.turn(client, SESSION, text, new NoopLog());
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = StringUtils.EMPTY;
        if (route.equals("POST /api/session/" + SESSION + "/prompt")) {
            prompts.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("text").asString());
        } else if (route.equals("GET /api/session/" + SESSION + "/message")) {
            body = newestAssistant(prompts.size());
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(HttpResponseStatus.OK.code(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    /** the newest assistant message after the given number of turns, as one page newest first */
    private String newestAssistant(int turns) {
        String outcome = outcomes.get(Math.min(turns, outcomes.size()) - 1);
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("type", "assistant");
        if (Objects.equals(outcome, "ok")) {
            message.put("finish", "stop").put("text", "{}");
        } else {
            message.put("finish", "error");
            message.putObject("error").put("type", outcome).put("message", "stream cut");
        }
        ObjectNode toReturn = JsonNodeFactory.instance.objectNode();
        toReturn.putArray("data").add(message);
        toReturn.putObject("cursor").putNull("next");
        return toReturn.toString();
    }
}
