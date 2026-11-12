package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import io.codiqo.util.RequestAuthorizer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A turn is sent once, and how it ended is settled with the answer the session gave through its tool, into the review's
 * verdict when it gave none. OpenCode records a turn it gave up as idle, its newest assistant message finished with
 * {@code error}; such a turn fails the review with that error rather than being prompted again, unless the answer was
 * given before it.
 */
class TurnTest {
    private static final String SESSION = "ses_coordinator";
    private static final String REFUSED_REVIEWER = "ses_refused";
    private static final String RATE_LIMITED_REVIEWER = "ses_rate_limited";
    private static final String PROMPT = "Review commit 1a2b3c of this repository.";
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TURN_TIMEOUT = Duration.ofMillis(300);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> prompts = Lists.newCopyOnWriteArrayList();
    private final LocalReviewer reviewer = reviewer(new RunArgs());
    /** held by a turn that does not end, until the test is over */
    private final CountDownLatch turnEnds = new CountDownLatch(1);
    private HttpServer server;
    /** the model's end point behind the relay, which answers every call with a 503 */
    private HttpServer provider;
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

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        url = RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/models", exchange -> respond(exchange, HttpResponseStatus.SERVICE_UNAVAILABLE.code(), "{}"));
        provider.start();
        relay = LlmRelay.start(new RunArgs(), new ReviewEndpoint(RunArgs.loopbackUrl(provider.getAddress().getPort()).encodedPath("/v1").build().toString(), null, null), new NoopLog());
        answers = ReviewAnswers.start(new RunArgs(), new NoopLog());
        answers.expect(SESSION, AnswerTool.LABELS, arguments -> {
            if (arguments.path("labels").isEmpty()) {
                throw new ReviewAnswers.RefusedAnswerException("not labelled: [U1]");
            }
            return arguments;
        });
    }
    @AfterEach
    void stop() {
        turnEnds.countDown();
        server.stop(0);
        provider.stop(0);
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
    /** the closing sentence after an accepted answer is one more call to the model, and its failure loses nothing */
    @Test
    void anAnswerGivenBeforeTheTurnFailedStands() throws Exception {
        answer("{\"labels\":[{\"id\":\"U1\"}]}");
        error = Optional.of("provider.rate-limit");

        assertEquals(Optional.empty(), ask(CLIENT_TIMEOUT));
    }
    /** an agent that never got to correct a refused answer failed on the provider, not on its answer */
    @Test
    void aProviderErrorAfterARefusedCallIsTheTurnsError() throws Exception {
        answer("{\"labels\":[]}");
        error = Optional.of("provider.rate-limit");

        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());

        assertEquals("session ses_coordinator ended its turn with provider.rate-limit: stream cut", failed.getError().getMessage());
    }
    /**
     * Every type OpenCode 2.2.2 ends a turn with other than the two the commit causes (its to-session-error.ts, and the
     * provider.unknown, compaction.failed and compaction.interrupted its runner records), plus one no version has yet:
     * each halts the project, so the commit is reviewed again on resume rather than silently excluded. An exhausted quota
     * (provider.quota) excluded 28 commits of one project when only the listed types halted.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "provider.auth", "provider.budget-limit", "provider.chatgpt-limit", "provider.free-limit", "provider.go-limit",
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
    @Test
    void aRefusedAnswerFailsOnItsAnswer() throws Exception {
        answer("{\"labels\":[]}");

        ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());

        assertEquals("session ses_coordinator's codiqo_submit_labels answer was refused: not labelled: [U1]", failed.getError().getMessage());
    }
    /** the prose-only answers that failed reviews before: a turn the model ended on its own without the call */
    @Test
    void aTurnThatStoppedWithoutTheCallFailsOnItsAnswer() throws Exception {
        ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());

        assertEquals("session ses_coordinator ended its turn without calling codiqo_submit_labels, so it gave no answer", failed.getError().getMessage());
    }
    /**
     * An answer that could not be read on Codiqo's side is the commit's failure, never the provider's, even when the
     * relay counted a provider error during the review: decided by that count, one retried 429 turned it into a halt.
     */
    @Test
    void anAnswerCodiqoCouldNotReadIsTheCommitsFailureWhateverTheProviderDid() throws Exception {
        answers.expect(SESSION, AnswerTool.LABELS, arguments -> {
            throw new IllegalArgumentException("index out of date");
        });
        answer("{\"labels\":[{\"id\":\"U1\"}]}");
        providerFails();

        ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());

        assertEquals("index out of date", failed.getError().getCause().getCause().getMessage());
        assertTrue(failed.getReason().endsWith("; the LLM provider answered 503 once during the review"), failed.getReason());
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
    /**
     * A labelling or triage turn runs after the coordinator's: a provider failure during an earlier turn, which OpenCode
     * retried, does not make this turn's lost answer the provider's, though the error still names it.
     */
    @Test
    void aSessionCutOffAfterAnEarlierTurnsProviderFailureIsTheCommits() throws Exception {
        cutOff = true;
        providerFails();

        ReviewVerdict.CommitFailure unanswered = assertInstanceOf(ReviewVerdict.CommitFailure.class, ask(CLIENT_TIMEOUT).orElseThrow());

        assertEquals("session ses_coordinator was cut off before it called codiqo_submit_labels, so it gave no answer; the LLM provider answered 503 once during the review",
                unanswered.getReason());
    }
    /** a turn that outlives the review timeout is settled like a session cut off, never as Codiqo's failure */
    @Test
    void aTurnThatDidNotEndInTimeIsUnanswered() throws Exception {
        endless = true;
        providerFailsDuringTurn = true;
        RunArgs args = new RunArgs();
        args.setReviewTimeout(TURN_TIMEOUT);

        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, ask(reviewer(args), TURN_TIMEOUT).orElseThrow());

        assertEquals("session ses_coordinator did not end its turn within PT0.3S", failed.getError().getMessage());
    }
    /**
     * OkHttp ends a connect to OpenCode that timed out with the same kind of exception as a call that outlived the review
     * timeout: one that ends sooner is no turn that ran out of time, and goes on as Codiqo's failure.
     */
    @Test
    void aWaitThatFailedBeforeTheReviewTimeoutIsNoTurnThatRanOutOfTime() {
        endless = true;

        assertThrows(InterruptedIOException.class, () -> ask(reviewer, TURN_TIMEOUT));
    }
    /** a reviewer the provider failed halts the review, so the commit is reviewed again rather than silently excluded */
    @Test
    void aReviewerTheProviderFailedSettlesTheReviewersWhicheverOrderTheyAreListedIn() throws Exception {
        answers.expect(REFUSED_REVIEWER, AnswerTool.REVIEW, arguments -> {
            throw new ReviewAnswers.RefusedAnswerException("no unitLabels");
        });
        answers.answerCall(REFUSED_REVIEWER, AnswerTool.REVIEW.getToolName(), mapper.readTree("{}"));

        try (OpenCodeClient client = new OpenCodeClient(url, "pw", CLIENT_TIMEOUT)) {
            for (List<String> listed : List.of(List.of(REFUSED_REVIEWER, RATE_LIMITED_REVIEWER), List.of(RATE_LIMITED_REVIEWER, REFUSED_REVIEWER))) {
                ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class,
                        LocalReviewer.settleReviewers(client, answers, relay, listed).orElseThrow(), listed.toString());

                assertEquals("session ses_rate_limited ended its turn with provider.rate-limit: stream cut", failed.getError().getMessage());
            }
        }
    }
    private void answer(String arguments) {
        answers.answerCall(SESSION, AnswerTool.LABELS.getToolName(), mapper.readTree(arguments));
    }
    private Optional<ReviewVerdict> ask(Duration timeout) throws IOException {
        return ask(reviewer, timeout);
    }
    private Optional<ReviewVerdict> ask(LocalReviewer asking, Duration timeout) throws IOException {
        try (OpenCodeClient client = new OpenCodeClient(url, "pw", timeout)) {
            return asking.ask(client, answers, relay, SESSION, PROMPT, AnswerTool.LABELS);
        }
    }
    private static LocalReviewer reviewer(RunArgs args) {
        return new LocalReviewer(args, new ReviewEndpoint("http://127.0.0.1:1", null, null), StringUtils.EMPTY, ReviewTags.NONE, List.of(), List.of(), new NoopLog());
    }
    /** the provider answers one of the review's calls with a 503, which the relay counts */
    private void providerFails() throws IOException {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(relay.getUrl() + "/models")).GET().header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(relay.getSecret())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE.code(), response.statusCode());
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException(err);
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = "{}";
        if (route.equals("POST /api/session/" + SESSION + "/prompt")) {
            prompts.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("text").asString());
        } else if (route.equals("POST /api/experimental/session/" + SESSION + "/wait")) {
            awaitTurn();
        } else if (route.equals("GET /api/session/" + SESSION + "/message")) {
            body = messages(error);
        } else if (route.equals("GET /api/session/" + REFUSED_REVIEWER + "/message")) {
            body = messages(Optional.empty());
        } else if (route.equals("GET /api/session/" + RATE_LIMITED_REVIEWER + "/message")) {
            body = messages(Optional.of("provider.rate-limit"));
        }
        respond(exchange, HttpResponseStatus.OK.code(), body);
    }
    /** the turn as the test sets it up: the provider failing during it, and a turn that does not end */
    private void awaitTurn() throws IOException {
        if (providerFailsDuringTurn) {
            providerFails();
        }
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
