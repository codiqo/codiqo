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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Maps;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class OpenCodeClientTest {
    private static final String PASSWORD = "test-password";
    private static final String SESSION = "ses_parent";
    private static final String CONTINUED = "ses_continued";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String EMPTY_FINDINGS = emptyFindings();
    private static final Pattern TRACED_MESSAGES = Pattern.compile("GET /api/session/(ses_\\w+)/message");
    private static final Pattern TRACED_SESSION = Pattern.compile("GET /api/session/(ses_\\w+)");

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
    /**
     * every session the review ran, its messages in order across pages, and a tool's long output cut: a page that is not
     * the last still carries a cursor, so the empty page after the last ends the list
     */
    @Test
    void theTraceHoldsEverySessionWithItsToolCalls(@TempDir Path directory) throws Exception {
        RunArgs args = new RunArgs();
        args.setReviewTraceDirectory(directory.toFile());
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            ReviewTrace.write(args, client, "abcdef0123456789", SESSION, List.of("ses_fork"), new NoopLog());
        }

        JsonNode trace = mapper.readTree(directory.resolve("codiqo-review-trace-abcdef01.json").toFile());
        assertEquals("abcdef0123456789", trace.path("commit").asString());
        assertEquals(List.of(SESSION, "ses_a", "ses_b", "ses_fork"), trace.path("sessions").valueStream().map(session -> session.path("session").path("id").asString()).toList());

        JsonNode messages = trace.path("sessions").get(1).path("messages");
        assertEquals(2, messages.size());
        JsonNode tool = messages.get(0).path("content").get(0);
        assertEquals("git show", tool.path("state").path("input").path("command").asString());
        assertEquals(4_000, tool.path("state").path("content").get(0).path("text").asString().length());
        assertEquals("done", messages.get(1).path("text").asString());
    }
    /**
     * a reviewer whose answer the provider cut short, which OpenCode continued within the same turn, and which the
     * coordinator then asked for a one-word confirmation: two turns, the review being the first one's answer
     */
    @Test
    void aReviewerTheCoordinatorContinuedKeepsItsReviewInItsFirstTurn() throws Exception {
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            List<List<JsonNode>> turns = client.turns(CONTINUED);

            assertEquals(2, turns.size());
            List<JsonNode> review = turns.iterator().next();
            assertEquals(Optional.of(EMPTY_FINDINGS), OpenCodeClient.answerOf(review));
            assertEquals(Optional.empty(), OpenCodeClient.errorOf(review), "OpenCode continued the cut answer, so the turn ended well");
            assertEquals(Optional.of("no"), OpenCodeClient.answerOf(turns.getLast()));
        }
    }
    /** a turn that ended with an error reports it and has no answer, though an earlier step of it wrote text */
    @Test
    void aTurnThatEndedWithAnErrorReportsItAndNoAnswer() {
        ObjectNode failed = assistant(200).put("finish", "error");
        failed.putObject("error").put("type", "provider.timeout").put("message", "no answer in time");
        List<JsonNode> turn = List.of(JSON.objectNode().put("type", "user").put("text", "Review part of commit abc."), assistant(100).put("finish", "tool-calls").put("text", "Step 1."), failed);

        assertEquals("provider.timeout", OpenCodeClient.errorOf(turn).orElseThrow().path("type").asString());
        assertEquals(Optional.empty(), OpenCodeClient.answerOf(turn));
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
        if (route.equals("GET /api/session/" + CONTINUED + "/message")) {
            return continuedReviewer(query);
        }
        Matcher traced = TRACED_MESSAGES.matcher(route);
        if (BooleanUtils.and(new boolean[] { traced.matches(), BooleanUtils.negate(Strings.CS.contains(query, "type=")) })) {
            return tracePage(query);
        }
        Matcher session = TRACED_SESSION.matcher(route);
        if (session.matches() && BooleanUtils.negate(SESSION.equals(session.group(1)))) {
            return data(session(session.group(1), OpenCodeReviewConfig.REVIEWER, "reviewer-model", 1, 1, 0, 0));
        }
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
     * The cursor carries the order and OpenCode refuses a page that names it again, but the type filter goes with every
     * page.
     */
    private static String messagePage(String query) {
        if (BooleanUtils.and(new boolean[] { query.contains("type=assistant"), query.contains("cursor=page-2"), BooleanUtils.negate(query.contains("order=")) })) {
            ObjectNode answer = assistant(200);
            answer.putArray("content").add(part("reasoning", "thinking")).add(part("text", EMPTY_FINDINGS));
            return page(null, List.of(answer, assistant(100).put("text", "I will start by listing the changed files.")));
        }
        if (BooleanUtils.and(new boolean[] { query.contains("type=assistant"), query.contains("order=desc"), BooleanUtils.negate(query.contains("cursor=")) })) {
            return page("page-2", List.of(assistant(300).put("text", StringUtils.EMPTY)));
        }
        throw new IllegalArgumentException("unexpected message query " + query);
    }
    /** a tool call whose output is longer than the trace keeps, then a text answer, then the empty page after the last */
    private static String tracePage(String query) {
        if (BooleanUtils.and(new boolean[] { Strings.CS.contains(query, "cursor="), Strings.CS.contains(query, "order=") })) {
            throw new IllegalArgumentException("Cursor cannot be combined with order");
        }
        if (Strings.CS.contains(query, "cursor=trace-3")) {
            return page("trace-4", List.of());
        }
        if (Strings.CS.contains(query, "cursor=trace-2")) {
            return page("trace-3", List.of(assistant(200).put("text", "done")));
        }
        ObjectNode tool = JSON.objectNode().put("type", "tool").put("name", "bash");
        ObjectNode state = tool.putObject("state").put("status", "completed");
        state.putObject("input").put("command", "git show");
        state.putArray("content").add(part("text", StringUtils.repeat('x', 10_000)));
        ObjectNode message = assistant(100);
        message.putArray("content").add(tool);
        return page("trace-2", List.of(message));
    }
    /**
     * what OpenCode recorded for the reviewer the coordinator continued in production, served oldest first over two
     * pages: the first page names the order and the cursor carries it, so any other query is refused
     */
    private static String continuedReviewer(String query) {
        if (BooleanUtils.and(new boolean[] { query.contains("order=asc"), BooleanUtils.negate(query.contains("cursor=")) })) {
            ObjectNode cut = assistant(200).put("finish", "error");
            cut.putObject("error").put("type", "provider.invalid-output").put("message", "OpenAI Chat stream ended without finish_reason");
            return page("continued-2", List.of(
                    JSON.objectNode().put("type", "user").put("text", "Review part of commit abc."),
                    assistant(100).put("finish", "tool-calls").put("text", "Step 1 of 256."),
                    cut,
                    JSON.objectNode().put("type", "synthetic").put("text", "The previous response was interrupted. Continue from where you left off.")));
        }
        if (BooleanUtils.and(new boolean[] { query.contains("cursor=continued-2"), BooleanUtils.negate(query.contains("order=")) })) {
            ObjectNode review = assistant(400).put("finish", "stop");
            review.putArray("content").add(part("text", EMPTY_FINDINGS));
            return page("continued-3", List.of(
                    review,
                    JSON.objectNode().put("type", "idle"),
                    JSON.objectNode().put("type", "user").put("text", "Do not review anything. Answer with the single word yes or no."),
                    assistant(600).put("finish", "stop").put("text", "no"),
                    JSON.objectNode().put("type", "idle")));
        }
        if (query.contains("cursor=continued-3")) {
            return page("continued-4", List.of());
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
