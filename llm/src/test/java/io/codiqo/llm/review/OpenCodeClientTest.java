package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Maps;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpHeaderNames;

class OpenCodeClientTest {
    private static final String PASSWORD = "test-password";
    private static final String SESSION = "ses_parent";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String EMPTY_FINDINGS = emptyFindings();

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, String> requestBodies = Maps.newConcurrentMap();
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
    void createsTheSessionForTheRepositoryAndAgent() throws Exception {
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            String id = client.createSession(Path.of("/repo/root"), OpenCodeReviewConfig.COORDINATOR, "codiqo review abc");

            assertEquals(SESSION, id);
            JsonNode body = mapper.readTree(requestBodies.get("POST /api/session"));
            assertEquals("/repo/root", body.path("location").path("directory").asString());
            assertEquals(OpenCodeReviewConfig.COORDINATOR, body.path("agent").asString());
        }
    }
    @Test
    void aForkIsANewSessionCopiedFromTheGivenOne() throws Exception {
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            assertEquals("ses_fork", client.fork(SESSION));
            assertEquals("{}", requestBodies.get("POST /api/session/" + SESSION + "/fork"), "no 'before': the fork copies every message");
        }
    }
    @Test
    void finalAnswerIsTheNewestAssistantTextInEitherShape() throws Exception {
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            client.prompt(SESSION, "Review commit abc of this repository.");
            client.awaitIdle(SESSION);

            assertEquals(EMPTY_FINDINGS, client.finalAnswer(SESSION));
            assertEquals("Review commit abc of this repository.", mapper.readTree(requestBodies.get("POST /api/session/" + SESSION + "/prompt")).path("text").asString());
        }
    }
    @Test
    void usageCoversTheCoordinatorAndEverySubAgent() throws Exception {
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            SessionUsage coordinator = client.usage(SESSION);
            List<SessionUsage> children = client.childUsage(SESSION);

            assertEquals(new SessionUsage(SESSION, "codiqo-review-coordinator", "coordinator-model", 10, 100, 2, 1), coordinator);
            assertEquals(2, children.size());
            assertEquals(3_000, children.stream().mapToLong(SessionUsage::getCachedInputTokens).sum());
        }
    }
    @Test
    void aRejectedCallReportsTheRouteAndStatus() {
        try (OpenCodeClient client = new OpenCodeClient(url, "wrong-password", TIMEOUT)) {
            IOException err = assertThrows(IOException.class, () -> client.usage(SESSION));
            assertTrue(err.getMessage().contains("/api/session/" + SESSION) && err.getMessage().contains("401"), err.getMessage());
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        String expected = "Basic " + Base64.getEncoder().encodeToString(("opencode:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        if (expected.equals(exchange.getRequestHeaders().getFirst(HttpHeaderNames.AUTHORIZATION.toString()))) {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requestBodies.put(route, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, HttpResponseStatus.OK.code(), body(route, exchange.getRequestURI().getQuery()));
            return;
        }
        respond(exchange, HttpResponseStatus.UNAUTHORIZED.code(), JSON.objectNode().put("_tag", "UnauthorizedError").put("message", "Authentication required").toString());
    }
    private static String body(String route, String query) {
        return switch (route) {
            case "POST /api/session" -> data(JSON.objectNode().put("id", SESSION));
            case "POST /api/session/" + SESSION + "/prompt" -> data(JSON.objectNode().put("id", "msg_1"));
            case "POST /api/session/" + SESSION + "/fork" -> data(JSON.objectNode().put("id", "ses_fork"));
            case "POST /api/experimental/session/" + SESSION + "/wait" -> StringUtils.EMPTY;
            case "GET /api/session/" + SESSION + "/message" -> messagePage(query);
            case "GET /api/session/" + SESSION -> data(session(SESSION, OpenCodeReviewConfig.COORDINATOR, "coordinator-model", 10, 2, 1, 100));
            case "GET /api/session" -> query.contains("parentID=" + SESSION)
                    ? page(null, List.of(
                            session("ses_a", OpenCodeReviewConfig.REVIEWER, "reviewer-model", 5, 1, 0, 1_000),
                            session("ses_b", OpenCodeReviewConfig.REVIEWER, "reviewer-model", 7, 3, 0, 2_000)))
                    : page(null, List.of());
            default -> throw new IllegalArgumentException("unexpected route " + route);
        };
    }
    /**
     * newest first and paged, as OpenCode 2.0.20 serves it: the answer is on the second page, after a text-less step.
     * Every page must ask for the order, the cursor is not trusted to keep it.
     */
    private static String messagePage(String query) {
        if (BooleanUtils.and(new boolean[] { query.contains("type=assistant"), query.contains("order=desc") })) {
            if (query.contains("cursor=page-2")) {
                ObjectNode answer = assistant(200);
                answer.putArray("content").add(part("reasoning", "thinking")).add(part("text", EMPTY_FINDINGS));
                return page(null, List.of(answer, assistant(100).put("text", "I will start by listing the changed files.")));
            }
            return page("page-2", List.of(assistant(300).put("text", StringUtils.EMPTY)));
        }
        throw new IllegalArgumentException("unexpected message query " + query);
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    private static String data(JsonNode data) {
        ObjectNode toReturn = JSON.objectNode();
        toReturn.set("data", data);
        return toReturn.toString();
    }
    private static String page(String next, List<ObjectNode> items) {
        ObjectNode toReturn = JSON.objectNode();
        toReturn.putArray("data").addAll(items);
        toReturn.putObject("cursor").putNull("previous").put("next", next);
        return toReturn.toString();
    }
    private static ObjectNode session(String id, String agent, String model, long input, long output, long reasoning, long cacheRead) {
        ObjectNode toReturn = JSON.objectNode().put("id", id).put("agent", agent);
        toReturn.putObject("model").put("id", model);
        toReturn.putObject("tokens").put("input", input).put("output", output).put("reasoning", reasoning).putObject("cache").put("read", cacheRead);
        return toReturn;
    }
    private static ObjectNode assistant(long created) {
        ObjectNode toReturn = JSON.objectNode().put("type", "assistant");
        toReturn.putObject("time").put("created", created);
        return toReturn;
    }
    private static ObjectNode part(String type, String text) {
        return JSON.objectNode().put("type", type).put("text", text);
    }
    private static String emptyFindings() {
        ObjectNode toReturn = JSON.objectNode();
        toReturn.putArray("blocking");
        toReturn.putArray("major");
        toReturn.putArray("minor");
        return toReturn.toString();
    }
}
