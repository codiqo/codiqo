package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * How OpenCode records the end of a turn, as its adapter ({@link OpenCodeSessions}) reads it over OpenCode's HTTP API,
 * and the verdict each end makes. A turn is sent once. OpenCode records a turn it gave up as idle, its newest assistant
 * message finished with {@code error}; such a turn fails the review with that error rather than being prompted again. A
 * turn that outlives the review timeout is told apart here from a wait that failed sooner. How a session is settled with
 * the answers it gave is tested on whole reviews ({@code LocalReviewTest}).
 */
class TurnTest {
    private static final String SESSION = "ses_coordinator";
    private static final String PROMPT = "Review commit 1a2b3c of this repository.";
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TURN_TIMEOUT = Duration.ofMillis(300);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> prompts = Lists.newCopyOnWriteArrayList();
    /** held by a turn that does not end, until the test is over */
    private final CountDownLatch turnEnds = new CountDownLatch(1);
    private HttpServer server;
    private UnavailableProvider provider;
    private ReviewAnswers answers;
    private LlmRelay relay;
    private String url;
    /** the error type the turn ends with, or none for a turn that ended on its own */
    private Optional<String> error = Optional.empty();
    /** a turn cut off at its step cap: its last message neither stopped on its own nor failed */
    private boolean cutOff;
    private boolean endless;
    /** the provider fails while the turn runs, not before it */
    private boolean providerFailsDuringTurn;
    /** a prompt was sent whose turn has not run yet */
    private volatile boolean turnPending;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        url = RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
        provider = UnavailableProvider.start();
        relay = LlmRelay.start(new RunArgs(), provider.endpoint(), new NoopLog());
        answers = ReviewAnswers.start(new RunArgs(), new NoopLog());
        answers.expect(SESSION, AnswerTool.LABELS, arguments -> arguments);
    }
    @AfterEach
    void stop() {
        turnEnds.countDown();
        server.stop(0);
        provider.close();
        relay.close();
        answers.close();
    }
    @Test
    void aTurnIsSentOnce() throws Exception {
        answer("{\"labels\":[{\"id\":\"U1\"}]}");

        assertEquals(Optional.empty(), ask(CLIENT_TIMEOUT));
        assertEquals(List.of(PROMPT), prompts);
    }
    @Test
    void aTurnThatEndedWithAnErrorFailsWithIt() throws Exception {
        error = Optional.of("provider.invalid-output");

        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());

        assertEquals("session ses_coordinator ended its turn with provider.invalid-output: stream cut", failed.getError().getMessage());
        assertEquals(ReviewVerdict.RELAY_COUNTED_NONE, failed.getProviderSummary());
        assertEquals(List.of(PROMPT), prompts, "the turn is not prompted again");
    }
    /**
     * Every type OpenCode 2.2.2 ends a turn with other than the two the commit causes (its to-session-error.ts, and the
     * provider.unknown, compaction.failed and compaction.interrupted its runner records), with provider.no-route, which
     * 2.2.7 dropped, and provider.chatgpt-workspace-limit, which 2.4.0 added, plus one no version has yet: each halts the
     * project, so the commit is reviewed again on resume rather than silently excluded. An exhausted quota
     * (provider.quota) excluded 28 commits of one project when only the listed types halted.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "provider.auth", "provider.budget-limit", "provider.chatgpt-limit", "provider.chatgpt-workspace-limit",
            "provider.free-limit", "provider.go-limit",
            "provider.internal", "provider.invalid-output", "provider.model-budget-limit", "provider.no-route",
            "provider.quota", "provider.rate-limit", "provider.timeout", "provider.transport", "provider.unknown",
            "provider.unsupported-operation", "permission.rejected", "tool.execution", "compaction.failed",
            "compaction.interrupted", "unknown", "provider.added-in-a-later-version" })
    void aTurnThatFailedOnAnythingButTheCommitIsTheProvidersFailure(String type) throws Exception {
        error = Optional.of(type);

        assertInstanceOf(ReviewVerdict.ProviderFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());
    }
    /** a request too large for the model, or content its filter refuses, fails again on every resume: the commit is excluded */
    @ParameterizedTest
    @ValueSource(strings = { "provider.invalid-request", "provider.content-filter" })
    void aTurnThatFailedOnWhatTheCommitAsksIsTheCommitsFailure(String type) throws Exception {
        error = Optional.of(type);
        providerFails();

        assertInstanceOf(ReviewVerdict.CommitFailure.class, ask(CLIENT_TIMEOUT).orElseThrow(), "whatever else the provider failed during the review");
    }
    /** the prose-only answers that failed reviews before: a turn the model ended on its own without the call */
    @Test
    void aTurnThatStoppedWithoutTheCallFailsOnItsAnswer() throws Exception {
        ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());

        assertEquals("session ses_coordinator ended its turn without calling codiqo_submit_labels, so it gave no answer", failed.getError().getMessage());
    }
    /** a session cut off at its step cap says nothing of why: the relay's count during its turn decides */
    @Test
    void aSessionCutOffIsTheProvidersFailureOnlyWhenTheProviderFailedDuringItsTurn() throws Exception {
        cutOff = true;

        ReviewVerdict.CommitFailure unanswered = assertInstanceOf(ReviewVerdict.CommitFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());
        assertEquals("session ses_coordinator was cut off before it called codiqo_submit_labels, so it gave no answer", unanswered.getError().getMessage());

        providerFailsDuringTurn = true;
        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());
        assertEquals("the LLM provider answered 503 once during the review", failed.getProviderSummary());
    }
    /** a turn that outlives the review timeout is settled like a session cut off, never as Codiqo's failure */
    @Test
    void aTurnThatDidNotEndInTimeIsUnanswered() throws Exception {
        endless = true;
        providerFailsDuringTurn = true;
        RunArgs args = new RunArgs();
        args.setReviewTimeout(TURN_TIMEOUT);

        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, ask(args, TURN_TIMEOUT).orElseThrow());

        assertEquals("session ses_coordinator did not end its turn within PT0.3S", failed.getError().getMessage());
    }
    /**
     * OkHttp ends a connect to OpenCode that timed out with the same kind of exception as a call that outlived the review
     * timeout: one that ends sooner is no turn that ran out of time, and goes on as Codiqo's failure.
     */
    @Test
    void aWaitThatFailedBeforeTheReviewTimeoutIsNoTurnThatRanOutOfTime() {
        endless = true;

        assertThrows(InterruptedIOException.class, () -> ask(new RunArgs(), TURN_TIMEOUT));
    }
    private void answer(String arguments) {
        answers.answerCall(SESSION, AnswerTool.LABELS.getToolName(), mapper.readTree(arguments));
    }
    private Optional<ReviewVerdict> ask(Duration timeout) throws IOException {
        return ask(new RunArgs(), timeout);
    }
    /** a turn asked under the review timeout of the arguments, OpenCode answering within the client's own timeout */
    private Optional<ReviewVerdict> ask(RunArgs args, Duration clientTimeout) throws IOException {
        try (OpenCodeSessions sessions = new OpenCodeSessions(new OpenCodeClient(url, "pw", clientTimeout), Path.of("."), args.getReviewTimeout(), NoopLog.INSTANCE)) {
            return reviewer(args).ask(sessions, answers, relay, SESSION, PROMPT, AnswerTool.LABELS);
        }
    }
    private static LocalReviewer reviewer(RunArgs args) {
        return new LocalReviewer(args, new ReviewEndpoint("http://127.0.0.1:1", null, null), StringUtils.EMPTY, ReviewTags.NONE, List.of(), List.of(), new NoopLog());
    }
    /** the provider answers one of the review's calls with a 503, which the relay counts */
    private void providerFails() throws IOException {
        UnavailableProvider.callThrough(relay.getUrl(), relay.getSecret());
    }
    private void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = "{}";
        if (route.equals("POST /api/session/" + SESSION + "/prompt")) {
            prompts.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("text").asString());
            turnPending = true;
        } else if (route.equals("POST /api/experimental/session/" + SESSION + "/wait")) {
            awaitTurn();
        } else if (route.equals("GET /api/session/" + SESSION + "/message")) {
            body = messages(error);
        } else if (route.equals("GET /api/session/active")) {
            body = "{\"data\":{}}";
        } else if (route.equals("GET /api/experimental/job")) {
            body = "{\"data\":[]}";
        }
        respond(exchange, HttpResponseStatus.OK.code(), body);
    }
    /**
     * The turn as the test sets it up: the provider failing during it, and a turn that does not end. Only the first wait
     * after a prompt runs the turn: a wait on a session already idle returns at once, as OpenCode's does.
     */
    private void awaitTurn() throws IOException {
        if (BooleanUtils.and(new boolean[] { turnPending, providerFailsDuringTurn })) {
            providerFails();
        }
        turnPending = false;
        if (endless) {
            awaitTurnEnd();
        }
    }
    private void awaitTurnEnd() throws IOException {
        try {
            turnEnds.await(CLIENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException(err);
        }
    }
    /** the session's one turn, oldest first: the prompt, then an assistant message that stopped, failed or was cut off */
    private String messages(Optional<String> turnError) {
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("type", "assistant");
        if (turnError.isPresent()) {
            message.put("finish", "error");
            message.putObject("error").put("type", turnError.get()).put("message", "stream cut");
        } else if (cutOff) {
            message.put("finish", "tool-calls");
        } else {
            message.put("finish", "stop").put("text", "All units are labelled.");
        }
        ObjectNode toReturn = JsonNodeFactory.instance.objectNode();
        toReturn.putArray("data").add(JsonNodeFactory.instance.objectNode().put("type", "user").put("text", PROMPT)).add(message);
        toReturn.putObject("cursor").putNull("next");
        return toReturn.toString();
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
