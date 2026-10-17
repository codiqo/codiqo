package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A turn is sent once. OpenCode 2.0.25 records a turn it gave up as idle, its newest assistant message finished with
 * {@code error}; such a turn fails the review with that error rather than being prompted again.
 */
class TurnTest {
    private static final String SESSION = "ses_coordinator";
    private static final String PROMPT = "Review commit 1a2b3c of this repository.";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> prompts = Lists.newCopyOnWriteArrayList();
    private HttpServer server;
    private String url;
    /** the error type the turn ends with, or none for a turn that answered */
    private Optional<String> error = Optional.empty();

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
    void aTurnThatAnsweredIsSentOnce() throws Exception {
        turn();

        assertEquals(List.of(PROMPT), prompts);
    }
    @Test
    void aTurnThatEndedWithAnErrorFailsWithIt() {
        error = Optional.of("provider.invalid-output");

        IOException err = assertThrows(IOException.class, this::turn);

        assertEquals("session ses_coordinator ended its turn with provider.invalid-output: stream cut", err.getMessage());
        assertEquals(List.of(PROMPT), prompts, "the turn is not prompted again");
    }
    private void turn() throws IOException {
        try (OpenCodeClient client = new OpenCodeClient(url, "pw", Duration.ofSeconds(10))) {
            LocalReviewer.turn(client, SESSION, PROMPT);
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = StringUtils.EMPTY;
        if (route.equals("POST /api/session/" + SESSION + "/prompt")) {
            prompts.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("text").asString());
        } else if (route.equals("GET /api/session/" + SESSION + "/message")) {
            body = newestAssistant();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(HttpResponseStatus.OK.code(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    private String newestAssistant() {
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("type", "assistant");
        if (error.isPresent()) {
            message.put("finish", "error");
            message.putObject("error").put("type", error.get()).put("message", "stream cut");
        } else {
            message.put("finish", "stop").put("text", "{}");
        }
        ObjectNode toReturn = JsonNodeFactory.instance.objectNode();
        toReturn.putArray("data").add(message);
        toReturn.putObject("cursor").putNull("next");
        return toReturn.toString();
    }
}
