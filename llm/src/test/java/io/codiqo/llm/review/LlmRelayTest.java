package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.codiqo.util.RequestAuthorizer;

/** The relay between the review's agents and the backend's proxy, against a fake proxy. */
class LlmRelayTest {
    private static final String EVENT_END = StringUtils.repeat(StringUtils.LF, 2);
    private static final String CREDENTIAL = RequestAuthorizer.bearer("member-token");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String CHAT_REQUEST = JSON.objectNode().put("model", "m").put("stream", true).toString();
    private static final String FIRST_CHUNK = JSON.objectNode().put("n", 1).toString();
    private static final String MODELS = models();
    private static final int RETRIES = 2;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicReference<String> upstreamAuthorization = new AtomicReference<>();
    private final AtomicReference<String> upstreamPath = new AtomicReference<>();
    private final AtomicReference<String> upstreamBody = new AtomicReference<>();
    private final AtomicInteger authorizations = new AtomicInteger();
    private final CountDownLatch firstChunkRead = new CountDownLatch(1);

    private HttpServer upstream;
    private LlmRelay relay;

    @BeforeEach
    void start() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", this::stream);
        upstream.createContext("/v1/models", exchange -> respond(exchange, HttpResponseStatus.OK.code(), MODELS));
        upstream.start();

        relay = LlmRelay.start(relayArgs(), endpoint(RunArgs.loopbackUrl(upstream.getAddress().getPort()).encodedPath("/v1/").build().toString(), header -> {
            authorizations.incrementAndGet();
            header.accept(HttpHeaderNames.AUTHORIZATION.toString(), CREDENTIAL);
        }), new NoopLog());
    }
    @AfterEach
    void stop() {
        relay.close();
        upstream.stop(0);
    }
    @Test
    void forwardsWithTheMembersCredentialInsteadOfTheRelaySecret() throws Exception {
        HttpResponse<String> response = http.send(get("/models").header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(relay.getSecret())).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpResponseStatus.OK.code(), response.statusCode());
        assertEquals(CREDENTIAL, upstreamAuthorization.get(), "the secret never leaves the machine; the credential replaces it");
        assertEquals("/v1/models", upstreamPath.get());
    }
    /** OpenCode sends no sampling, so every chat completion leaves with the scoring prompt's, whatever the agent sent */
    @Test
    void aChatCompletionLeavesWithTheScoringPromptsSampling() throws Exception {
        firstChunkRead.countDown();
        HttpRequest request = HttpRequest.newBuilder(URI.create(relay.getUrl() + "/chat/completions"))
                .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(relay.getSecret()))
                .header(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.objectNode().put("model", "m").put("stream", true).put("temperature", 1.0).toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        RunArgs args = relayArgs();
        JsonNode forwarded = JsonMapper.builder().build().readTree(upstreamBody.get());
        assertEquals(HttpResponseStatus.OK.code(), response.statusCode());
        assertEquals(args.getLlmTemperature(), forwarded.path("temperature").asDouble());
        assertEquals(args.getLlmTopP(), forwarded.path("top_p").asDouble());
        assertEquals(args.getLlmSeed(), forwarded.path("seed").asInt());
        assertEquals("m", forwarded.path("model").asString(), "the rest of the request is untouched");
    }
    /**
     * The request is read and written as the OpenAI SDK's request body: everything OpenCode sends that the SDK does not
     * model (stream, the reasoning a model returned, a tool's JSON schema) leaves exactly as it came, and only the
     * sampling changes.
     */
    @Test
    void onlyTheSamplingOfAnOpenCodeRequestChanges() throws Exception {
        firstChunkRead.countDown();
        String sent = IOUtils.resourceToString("/openai/opencode-chat-request.json", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(relay.getUrl() + "/chat/completions"))
                .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(relay.getSecret()))
                .header(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString())
                .POST(HttpRequest.BodyPublishers.ofString(sent))
                .build();
        assertEquals(HttpResponseStatus.OK.code(), http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());

        RunArgs args = relayArgs();
        JsonMapper mapper = JsonMapper.builder().build();
        ObjectNode expected = (ObjectNode) mapper.readTree(sent);
        expected.put("temperature", args.getLlmTemperature()).put("top_p", args.getLlmTopP()).put("seed", args.getLlmSeed());
        assertEquals(expected, mapper.readTree(upstreamBody.get()));
    }
    /** it could not leave with the scoring prompt's sampling, so the turn fails rather than going on unseeded */
    @Test
    void aBodyThatIsNotAChatCompletionRequestIsRefused() throws Exception {
        firstChunkRead.countDown();
        HttpRequest request = HttpRequest.newBuilder(URI.create(relay.getUrl() + "/chat/completions"))
                .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(relay.getSecret()))
                .POST(HttpRequest.BodyPublishers.ofString("[1, 2]"))
                .build();

        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertNull(upstreamBody.get(), "nothing reaches the provider");
    }
    /** a direct end point gets its own key as the bearer token, and nothing when it has none, as a local daemon */
    @Test
    void aDirectEndPointGetsItsOwnKeyOrNone() throws Exception {
        String base = RunArgs.loopbackUrl(upstream.getAddress().getPort()).encodedPath("/v1").build().toString();
        try (LlmRelay keyed = LlmRelay.start(relayArgs(), new ReviewEndpoint(base, "provider-key", null), new NoopLog())) {
            getModels(keyed);
            assertEquals(RequestAuthorizer.bearer("provider-key"), upstreamAuthorization.get());
        }
        try (LlmRelay local = LlmRelay.start(relayArgs(), new ReviewEndpoint(base, null, null), new NoopLog())) {
            getModels(local);
            assertNull(upstreamAuthorization.get());
        }
    }
    /** another process on the machine can reach the port; without the secret it spends nothing */
    @Test
    void refusesACallerWithoutTheSecret() throws Exception {
        HttpResponse<String> response = http.send(get("/models").header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer("guessed")).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), response.statusCode());
        assertNull(upstreamPath.get(), "nothing reached the proxy");
        assertEquals(0, authorizations.get(), "the credential was not even fetched");
    }
    /** the member's credential reaches more of the backend than the proxy, so only the agents' two calls are relayed */
    @Test
    void forwardsOnlyTheCallsTheAgentsMake() throws Exception {
        String secret = RequestAuthorizer.bearer(relay.getSecret());

        assertEquals(HttpResponseStatus.NOT_FOUND.code(), http.send(get("/../api/projects").header(HttpHeaderNames.AUTHORIZATION.toString(), secret).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(HttpResponseStatus.NOT_FOUND.code(), http.send(HttpRequest.newBuilder(URI.create(relay.getUrl() + "/models")).DELETE().header(HttpHeaderNames.AUTHORIZATION.toString(), secret).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(HttpResponseStatus.NOT_FOUND.code(), http.send(HttpRequest.newBuilder(URI.create(relay.getUrl() + "x/models")).GET().header(HttpHeaderNames.AUTHORIZATION.toString(), secret).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertNull(upstreamPath.get(), "nothing reached the proxy");
        assertEquals(0, authorizations.get(), "the credential was not even fetched");
    }
    /** an ended login is not an unreachable proxy: 401 stops the agents instead of having them retry a 502 */
    @Test
    void aCredentialThatCannotBeRefreshedIsAnAuthenticationError() throws Exception {
        try (LlmRelay ended = LlmRelay.start(relayArgs(), endpoint(RunArgs.loopbackUrl(upstream.getAddress().getPort()).encodedPath("/v1").build().toString(), header -> {
            throw new IOException("invalid_grant");
        }), new NoopLog())) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(ended.getUrl() + "/models")).GET()
                    .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(ended.getSecret())).build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), response.statusCode());
            assertTrue(response.body().contains("log in again"), response.body());
            assertNull(upstreamPath.get(), "nothing reached the proxy");
        }
    }
    /** a refresh that failed once on the network is retried, not taken for an ended login */
    @Test
    void aRefreshThatFailsOnceIsRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (LlmRelay flaky = LlmRelay.start(relayArgs(), endpoint(RunArgs.loopbackUrl(upstream.getAddress().getPort()).encodedPath("/v1").build().toString(), header -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("connection reset on the way to the auth server");
            }
            header.accept(HttpHeaderNames.AUTHORIZATION.toString(), CREDENTIAL);
        }), new NoopLog())) {
            assertEquals(HttpResponseStatus.OK.code(), getModels(flaky).statusCode());
            assertEquals(2, attempts.get());
        }
    }
    /** a stream cut after the answer started cannot become a 502; it ends with an error event the client raises */
    @Test
    void aStreamCutShortEndsWithAnErrorEvent() throws Exception {
        HttpServer cutting = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cutting.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.TEXT_EVENT_STREAM.toString());
            exchange.sendResponseHeaders(HttpResponseStatus.OK.code(), 0);
            exchange.getResponseBody().write(("data: " + FIRST_CHUNK + EVENT_END).getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            throw new IOException("the upstream went away mid-answer");
        });
        cutting.start();
        try (LlmRelay through = relayTo(cutting)) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(through.getUrl() + "/chat/completions"))
                    .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(through.getSecret()))
                    .header(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(CHAT_REQUEST))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertTrue(response.body().contains("data: " + FIRST_CHUNK), response.body());
            assertTrue(response.body().contains("cut short"), "the cut is visible to the client: " + response.body());
        } finally {
            cutting.stop(0);
        }
    }
    /** the relay retries no call to the model: OpenCode retries a busy proxy itself, with its own backoff */
    @Test
    void aBusyProxysAnswerIsPassedOnForOpenCodeToRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer busy = fakeProxy(exchange -> {
            calls.incrementAndGet();
            respond(exchange, HttpResponseStatus.SERVICE_UNAVAILABLE.code(), MODELS);
        });
        try (LlmRelay passing = relayTo(busy)) {
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE.code(), getModels(passing).statusCode());
            assertEquals(1, calls.get(), "no retry on top of OpenCode's");
        } finally {
            busy.stop(0);
        }
    }
    /** a review that fails while the provider keeps failing says so, by status */
    @Test
    void countsTheProvidersFailedAnswersForTheReviewsFailure() throws Exception {
        List<Integer> statuses = List.of(HttpResponseStatus.GATEWAY_TIMEOUT.code(), HttpResponseStatus.OK.code(), HttpResponseStatus.TOO_MANY_REQUESTS.code());
        AtomicInteger calls = new AtomicInteger();
        HttpServer failing = fakeProxy(exchange -> respond(exchange, statuses.get(calls.getAndIncrement()), MODELS));
        try (LlmRelay counting = relayTo(failing)) {
            assertEquals(Optional.empty(), counting.providerErrors(), "nothing failed yet");

            assertEquals(HttpResponseStatus.GATEWAY_TIMEOUT.code(), getModels(counting).statusCode());
            assertEquals(HttpResponseStatus.OK.code(), getModels(counting).statusCode());
            assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS.code(), getModels(counting).statusCode());

            assertEquals(Optional.of("the LLM provider answered 429 once and 504 once during the review"), counting.providerErrors());
            assertTrue(counting.providerFailed());
        } finally {
            failing.stop(0);
        }
    }
    /** a request the provider only refused, such as a prompt over the model's context, is named but is the commit's failure */
    @Test
    void aRefusedRequestIsNotTheProvidersFailure() throws Exception {
        HttpServer refusing = fakeProxy(exchange -> respond(exchange, HttpResponseStatus.BAD_REQUEST.code(), MODELS));
        try (LlmRelay counting = relayTo(refusing)) {
            assertEquals(HttpResponseStatus.BAD_REQUEST.code(), getModels(counting).statusCode());

            assertEquals(Optional.of("the LLM provider answered 400 once during the review"), counting.providerErrors());
            assertFalse(counting.providerFailed());
        } finally {
            refusing.stop(0);
        }
    }
    /** OpenCode tells an exhausted quota, which it does not retry, from a rate limit by the provider's own error body */
    @Test
    void aRateLimitKeepsTheProvidersErrorBody() throws Exception {
        String quota = "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}";
        HttpServer exhausted = fakeProxy(exchange -> respond(exchange, HttpResponseStatus.TOO_MANY_REQUESTS.code(), quota));
        try (LlmRelay passing = relayTo(exhausted, rateLimited(5, Duration.ofSeconds(10)))) {
            HttpResponse<String> answer = getModels(passing);

            assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS.code(), answer.statusCode());
            assertEquals(quota, answer.body());
            assertTrue(answer.headers().firstValue("retry-after-ms").isPresent(), "still with the round's wait");
        } finally {
            exhausted.stop(0);
        }
    }
    /** a 429 goes back with the round's wait, which OpenCode keeps before it calls again */
    @Test
    void aRateLimitIsPassedOnWithTheRoundsWait() throws Exception {
        HttpServer limited = fakeProxy(exchange -> respond(exchange, HttpResponseStatus.TOO_MANY_REQUESTS.code(), MODELS));
        try (LlmRelay waiting = relayTo(limited, rateLimited(5, Duration.ofSeconds(10)))) {
            HttpResponse<String> answer = getModels(waiting);

            assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS.code(), answer.statusCode());
            long millis = Long.parseLong(answer.headers().firstValue("retry-after-ms").orElseThrow());
            assertTrue(millis >= 7_900 && millis <= 10_000, "the first round, spread by up to a fifth: " + millis);
            assertEquals(Optional.of(Long.toString(Math.ceilDiv(millis, 1000L))), answer.headers().firstValue(HttpHeaderNames.RETRY_AFTER.toString()), "the same wait in whole seconds");
            assertTrue(answer.headers().firstValue("x-should-retry").isEmpty(), "OpenCode retries it");
        } finally {
            limited.stop(0);
        }
    }
    /** calls refused while a round lasts share its deadline, so parallel reviewers come back once, together */
    @Test
    void callsInTheSameRoundShareItsDeadline() throws Exception {
        HttpServer limited = fakeProxy(exchange -> respond(exchange, HttpResponseStatus.TOO_MANY_REQUESTS.code(), MODELS));
        try (LlmRelay sharing = relayTo(limited, rateLimited(1, Duration.ofSeconds(10)))) {
            long first = Long.parseLong(getModels(sharing).headers().firstValue("retry-after-ms").orElseThrow());
            HttpResponse<String> second = getModels(sharing);

            assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS.code(), second.statusCode(), "still the first round, not the exhausted answer");
            assertTrue(Long.parseLong(second.headers().firstValue("retry-after-ms").orElseThrow()) <= first, "the same deadline, a little nearer");
        } finally {
            limited.stop(0);
        }
    }
    /**
     * A limit that outlasts the rounds is answered with a 429 OpenCode does not retry, and every later call of the review
     * is refused without reaching the provider: retried, each would wait through the whole schedule again.
     */
    @Test
    void aRateLimitThatOutlastsTheRoundsEndsTheReviewsCalls() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer limited = fakeProxy(exchange -> {
            calls.incrementAndGet();
            respond(exchange, HttpResponseStatus.TOO_MANY_REQUESTS.code(), MODELS);
        });
        try (LlmRelay exhausted = relayTo(limited, rateLimited(2, Duration.ofMillis(1)))) {
            for (int round = 1; round <= 2; round++) {
                assertTrue(getModels(exhausted).headers().firstValue("x-should-retry").isEmpty(), "round " + round + " is retried");
                Thread.sleep(5);
            }
            HttpResponse<String> last = getModels(exhausted);
            assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS.code(), last.statusCode());
            assertEquals(Optional.of("false"), last.headers().firstValue("x-should-retry"));
            assertTrue(last.body().contains("rate_limit_exhausted"), last.body());

            int reached = calls.get();
            assertEquals(Optional.of("false"), getModels(exhausted).headers().firstValue("x-should-retry"));
            assertEquals(reached, calls.get(), "the later call never reached the provider");
        } finally {
            limited.stop(0);
        }
    }
    /** a success ends the rate limit, so the next 429 starts again from the first round */
    @Test
    void aSuccessResetsTheRounds() throws Exception {
        List<Integer> statuses = List.of(HttpResponseStatus.TOO_MANY_REQUESTS.code(), HttpResponseStatus.OK.code(), HttpResponseStatus.TOO_MANY_REQUESTS.code(), HttpResponseStatus.TOO_MANY_REQUESTS.code());
        AtomicInteger calls = new AtomicInteger();
        HttpServer flapping = fakeProxy(exchange -> respond(exchange, statuses.get(calls.getAndIncrement()), MODELS));
        try (LlmRelay resetting = relayTo(flapping, rateLimited(1, Duration.ofMillis(1)))) {
            assertTrue(getModels(resetting).headers().firstValue("x-should-retry").isEmpty());
            Thread.sleep(5);
            assertEquals(HttpResponseStatus.OK.code(), getModels(resetting).statusCode());
            Thread.sleep(5);
            assertTrue(getModels(resetting).headers().firstValue("x-should-retry").isEmpty(), "the first round again, not the second");
        } finally {
            flapping.stop(0);
        }
    }
    @Test
    void aProviderThatNeverFailedAddsNothingToTheReviewsFailure() throws Exception {
        assertEquals(HttpResponseStatus.OK.code(), getModels(relay).statusCode());
        assertEquals(Optional.empty(), relay.providerErrors());
    }
    @Test
    void anUnreachableProxyIsReportedAs502() throws Exception {
        try (LlmRelay unreachable = LlmRelay.start(relayArgs(), endpoint("http://127.0.0.1:9/v1", header -> header.accept(HttpHeaderNames.AUTHORIZATION.toString(), CREDENTIAL)), new NoopLog())) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(unreachable.getUrl() + "/models")).GET()
                    .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(unreachable.getSecret())).build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpResponseStatus.BAD_GATEWAY.code(), response.statusCode());
            assertTrue(response.body().contains("could not be reached"), response.body());
            assertTrue(unreachable.providerFailed(), "an end point that cannot be reached is the provider's outage");
        }
    }
    /**
     * The upstream holds its second chunk until the client has read the first: a relay that buffered the answer
     * would deadlock here, and an agent would see nothing until a long answer ended.
     */
    @Test
    void streamsEachChunkAsItArrives() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(relay.getUrl() + "/chat/completions"))
                .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(relay.getSecret()))
                .header(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString())
                .POST(HttpRequest.BodyPublishers.ofString(CHAT_REQUEST))
                .build();

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals("data: " + FIRST_CHUNK, reader.readLine());
            firstChunkRead.countDown();
            reader.readLine();
            assertEquals("data: [DONE]", reader.readLine());
        }

        assertEquals(HttpResponseStatus.OK.code(), response.statusCode());
        assertTrue(response.headers().firstValue(HttpHeaderNames.CONTENT_TYPE.toString()).orElseThrow().startsWith(HttpHeaderValues.TEXT_EVENT_STREAM.toString()));
        assertEquals("m", JsonMapper.builder().build().readTree(upstreamBody.get()).path("model").asString(), "the request reaches the proxy");
    }
    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(relay.getUrl() + path)).GET();
    }
    private LlmRelay relayTo(HttpServer upstreamProxy) throws IOException {
        return relayTo(upstreamProxy, relayArgs());
    }
    private LlmRelay relayTo(HttpServer upstreamProxy, RunArgs args) throws IOException {
        return LlmRelay.start(args, endpoint(RunArgs.loopbackUrl(upstreamProxy.getAddress().getPort()).encodedPath("/v1").build().toString(), header -> {
            authorizations.incrementAndGet();
            header.accept(HttpHeaderNames.AUTHORIZATION.toString(), CREDENTIAL);
        }), new NoopLog());
    }
    private HttpResponse<String> getModels(LlmRelay through) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(through.getUrl() + "/models")).GET().header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(through.getSecret())).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static HttpServer fakeProxy(HttpHandler handler) throws IOException {
        HttpServer toReturn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        toReturn.createContext("/v1/models", handler);
        toReturn.start();
        return toReturn;
    }
    private static RunArgs rateLimited(int rounds, Duration firstRound) {
        RunArgs toReturn = relayArgs();
        toReturn.setReviewRelayRateLimitRetries(rounds);
        toReturn.setReviewRelayRateLimitBackoff(firstRound);
        toReturn.setReviewRelayRateLimitMaxBackoff(firstRound);
        return toReturn;
    }
    private static RunArgs relayArgs() {
        RunArgs toReturn = new RunArgs();
        toReturn.setReviewRelayRetries(RETRIES);
        toReturn.setReviewRelayRetryBackoff(Duration.ofMillis(1));
        return toReturn;
    }
    private static ReviewEndpoint endpoint(String baseUrl, RequestAuthorizer authorizer) {
        return new ReviewEndpoint(baseUrl, null, authorizer);
    }
    private void stream(HttpExchange exchange) throws IOException {
        upstreamAuthorization.set(exchange.getRequestHeaders().getFirst(HttpHeaderNames.AUTHORIZATION.toString()));
        upstreamPath.set(exchange.getRequestURI().getPath());
        upstreamBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.TEXT_EVENT_STREAM.toString());
        exchange.sendResponseHeaders(HttpResponseStatus.OK.code(), 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(("data: " + FIRST_CHUNK + EVENT_END).getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                assertTrue(firstChunkRead.await(10, TimeUnit.SECONDS), "the first chunk never reached the client");
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
            }
            out.write(("data: [DONE]" + EVENT_END).getBytes(StandardCharsets.UTF_8));
        }
    }
    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        upstreamAuthorization.set(exchange.getRequestHeaders().getFirst(HttpHeaderNames.AUTHORIZATION.toString()));
        upstreamPath.set(exchange.getRequestURI().getPath());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    private static String models() {
        ObjectNode toReturn = JSON.objectNode().put("object", "list");
        toReturn.putArray("data");
        return toReturn.toString();
    }
}
