package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
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
    /** a busy proxy is waited out: the agent sees the answer, never the 503, and the credential is fetched per attempt */
    @Test
    void retriesABusyProxyUntilItAnswers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer busy = fakeProxy(exchange -> respond(exchange, calls.incrementAndGet() < 3 ? HttpResponseStatus.SERVICE_UNAVAILABLE.code() : HttpResponseStatus.OK.code(), MODELS));
        try (LlmRelay retrying = relayTo(busy)) {
            assertEquals(HttpResponseStatus.OK.code(), getModels(retrying).statusCode());
            assertEquals(3, calls.get());
            assertEquals(3, authorizations.get());
        } finally {
            busy.stop(0);
        }
    }
    /** once the retries are spent the proxy's own answer reaches the agent, which may still retry it */
    @Test
    void passesTheAnswerOnOnceTheRetriesAreSpent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer down = fakeProxy(exchange -> {
            calls.incrementAndGet();
            respond(exchange, HttpResponseStatus.SERVICE_UNAVAILABLE.code(), MODELS);
        });
        try (LlmRelay retrying = relayTo(down)) {
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE.code(), getModels(retrying).statusCode());
            assertEquals(RETRIES + 1, calls.get(), "the first call and every retry");
        } finally {
            down.stop(0);
        }
    }
    @Test
    void anUnreachableProxyIsRetriedThenReportedAs502() throws Exception {
        try (LlmRelay unreachable = LlmRelay.start(relayArgs(), endpoint("http://127.0.0.1:9/v1", header -> header.accept(HttpHeaderNames.AUTHORIZATION.toString(), CREDENTIAL)), new NoopLog())) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(unreachable.getUrl() + "/models")).GET()
                    .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(unreachable.getSecret())).build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpResponseStatus.BAD_GATEWAY.code(), response.statusCode());
            assertTrue(response.body().contains("could not be reached"), response.body());
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
        assertEquals(CHAT_REQUEST, upstreamBody.get(), "the body reaches the proxy byte for byte");
    }
    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(relay.getUrl() + path)).GET();
    }
    private LlmRelay relayTo(HttpServer upstreamProxy) throws IOException {
        return LlmRelay.start(relayArgs(), endpoint(RunArgs.loopbackUrl(upstreamProxy.getAddress().getPort()).encodedPath("/v1").build().toString(), header -> {
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
