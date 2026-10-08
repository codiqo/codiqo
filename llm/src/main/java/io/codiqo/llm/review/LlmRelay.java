package io.codiqo.llm.review;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.math.NumberUtils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.util.RequestAuthorizer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import okhttp3.HttpUrl;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A loop back end point the review's agents call instead of the backend's LLM proxy, which forwards each request with
 * the member's current credential. The token is refreshed as it ages, so a review out lasting it keeps going, and it
 * never reaches OpenCode's configuration on disk.
 *
 * <p>
 * Other processes on the machine can reach a loop back port, so the agents must present a secret made for this
 * review; anything else is refused before it can spend the organization's budget.
 */
public final class LlmRelay implements AutoCloseable {
    /** the path prefix OpenAI clients put before {@code /chat/completions}, kept on both sides of the relay */
    public static final String API_PREFIX = "/v1";

    private static final String EVENT_END = StringUtils.repeat(StringUtils.LF, 2);
    private static final int SECRET_LENGTH = 43;
    private static final int BUFFER_BYTES = 8192;
    private static final List<String> FORWARDED_REQUEST_HEADERS = List.of(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderNames.ACCEPT.toString());
    private static final List<String> FORWARDED_RESPONSE_HEADERS = List.of(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderNames.RETRY_AFTER.toString());
    private static final Set<Integer> RETRIED_STATUSES = Set.of(
            HttpResponseStatus.TOO_MANY_REQUESTS.code(),
            HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
            HttpResponseStatus.BAD_GATEWAY.code(),
            HttpResponseStatus.SERVICE_UNAVAILABLE.code(),
            HttpResponseStatus.GATEWAY_TIMEOUT.code());
    /**
     * The only calls an OpenAI-compatible provider makes, as method and path after {@value #API_PREFIX}, matched exactly.
     * The relay attaches the member's credential, whose scopes reach far more of the backend than the LLM proxy, so it
     * must not forward whatever a caller asks for: the server context matches by string prefix, which let
     * {@code /v1x} or {@code /v1/../api/...} through with any method.
     */
    private static final Set<String> FORWARDED_CALLS = Set.of("POST /chat/completions", "GET /models");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient http;
    private final HttpUrl upstream;
    private final RequestAuthorizer authorizer;
    private final int retries;
    private final Duration requestTimeout;
    private final Duration retryBackoff;
    private final String secret;
    private final Log log;

    private LlmRelay(HttpServer server, ExecutorService executor, RunArgs args, ReviewEndpoint upstream, Log log) {
        this.server = Objects.requireNonNull(server);
        this.executor = Objects.requireNonNull(executor);
        this.http = HttpClient.newBuilder().connectTimeout(args.getReviewRelayConnectTimeout()).executor(executor).build();
        this.upstream = HttpUrl.get(upstream.getBaseUrl());
        this.authorizer = Objects.requireNonNull(upstream.getAuthorizer());
        this.retries = Math.max(0, args.getReviewRelayRetries());
        this.requestTimeout = Objects.requireNonNull(args.getReviewRelayRequestTimeout());
        this.retryBackoff = Objects.requireNonNull(args.getReviewRelayRetryBackoff());
        this.log = Objects.requireNonNull(log);

        this.secret = RandomStringUtils.secure().nextAlphanumeric(SECRET_LENGTH);
    }
    public String getUrl() {
        return RunArgs.loopbackUrl(server.getAddress().getPort()).encodedPath(API_PREFIX).build().toString();
    }
    public String getSecret() {
        return secret;
    }
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
    private void relay(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (isAuthorized(exchange)) {
                String path = Strings.CS.removeStart(exchange.getRequestURI().getRawPath(), API_PREFIX);
                if (FORWARDED_CALLS.contains(exchange.getRequestMethod() + StringUtils.SPACE + path)) {
                    forward(exchange, path);
                } else {
                    log.warn("llm relay: refused %s %s, which is not a call the agents make", exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
                    respond(exchange, HttpResponseStatus.NOT_FOUND.code(), error("not relayed", "invalid_request_error"));
                }
            } else {
                respond(exchange, HttpResponseStatus.UNAUTHORIZED.code(), error("unknown relay key", "invalid_request_error"));
            }
        }
    }
    private void forward(HttpExchange exchange, String path) throws IOException {
        URI target = upstream.newBuilder().addPathSegments(Strings.CS.removeStart(path, "/")).encodedQuery(exchange.getRequestURI().getRawQuery()).build().uri();
        byte[] body = exchange.getRequestBody().readAllBytes();

        HttpResponse<InputStream> response = null;
        try {
            for (int attempt = 0; Objects.isNull(response); attempt++) {
                HttpRequest.Builder request = HttpRequest.newBuilder(target)
                        .timeout(requestTimeout)
                        .method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                for (String header : FORWARDED_REQUEST_HEADERS) {
                    String value = exchange.getRequestHeaders().getFirst(header);
                    if (StringUtils.isNotBlank(value)) {
                        request.header(header, value);
                    }
                }

                /**
                 * A credential that still cannot be refreshed after the retries is the member's login ending, not the
                 * proxy being down. It is answered as 401, which an OpenAI client does not retry, so the agents stop
                 * instead of spending their step budget on retries of a misleading 502. A refresh that failed once is
                 * retried like any call: a network blip on the way to the authenticated server says nothing about the login.
                 * Authorised per attempt: a retry may come after the token expired.
                 */
                try {
                    authorizer.accept(request::setHeader);
                } catch (IOException err) {
                    if (attempt < retries) {
                        log.warn("llm relay: the Codiqo credential could not be refreshed (%s), retry %d of %d", err.getMessage(), attempt + 1, retries);
                        Thread.sleep(backoff(attempt));
                        continue;
                    }
                    log.warn("llm relay: the Codiqo credential could not be refreshed: %s", err.getMessage());
                    respond(exchange, HttpResponseStatus.UNAUTHORIZED.code(), error("the Codiqo login could not be refreshed; log in again", "authentication_error"));
                    return;
                }

                try {
                    HttpResponse<InputStream> answer = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                    if (BooleanUtils.and(new boolean[] { RETRIED_STATUSES.contains(answer.statusCode()), attempt < retries })) {
                        answer.body().close();
                        Duration wait = retryAfter(answer).orElse(backoff(attempt));
                        log.warn("llm relay: %s %s answered %d, retry %d of %d in %s", exchange.getRequestMethod(), path, answer.statusCode(), attempt + 1, retries, wait);
                        Thread.sleep(wait);
                    } else {
                        response = answer;
                    }
                } catch (IOException err) {
                    if (attempt < retries) {
                        log.warn("llm relay: %s %s failed (%s), retry %d of %d", exchange.getRequestMethod(), path, err.getMessage(), attempt + 1, retries);
                        Thread.sleep(backoff(attempt));
                    } else {
                        log.warn("llm relay: %s %s failed after %d retries: %s", exchange.getRequestMethod(), path, retries, err.getMessage());
                        boolean timedOut = err instanceof HttpTimeoutException;
                        respond(exchange, (timedOut ? HttpResponseStatus.GATEWAY_TIMEOUT : HttpResponseStatus.BAD_GATEWAY).code(), error(timedOut ? "the Codiqo LLM proxy did not answer in time" : "the Codiqo LLM proxy could not be reached", "api_error"));
                        return;
                    }
                }
            }
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return;
        }

        for (String header : FORWARDED_RESPONSE_HEADERS) {
            response.headers().firstValue(header).ifPresent(value -> exchange.getResponseHeaders().set(header, value));
        }
        exchange.sendResponseHeaders(response.statusCode(), 0);
        boolean streamed = response.headers().firstValue(HttpHeaderNames.CONTENT_TYPE.toString()).map(type -> Strings.CI.startsWith(type, HttpHeaderValues.TEXT_EVENT_STREAM)).orElse(false);
        try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
            byte[] buffer = new byte[BUFFER_BYTES];
            try {
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException err) {
                /**
                 * The status was sent with the first byte, so a cut can no longer be a 502. A streamed answer ends with
                 * an error event, which an OpenAI client raises; a plain body is left short, which no client parses.
                 */
                log.warn("llm relay: %s %s was cut short after the answer started: %s", exchange.getRequestMethod(), path, err.getMessage());
                if (streamed) {
                    out.write((StringUtils.LF + "data: " + error("the Codiqo LLM proxy's answer was cut short", "api_error") + EVENT_END).getBytes(StandardCharsets.UTF_8));
                }
                throw err;
            }
        }
    }
    private Duration backoff(int attempt) {
        return retryBackoff.multipliedBy(1L << attempt);
    }
    private Optional<Duration> retryAfter(HttpResponse<?> answer) {
        return answer.headers().firstValue(HttpHeaderNames.RETRY_AFTER.toString())
                .map(value -> NumberUtils.toLong(StringUtils.trim(value), -1))
                .filter(seconds -> seconds >= 0)
                .map(seconds -> ObjectUtils.min(Duration.ofSeconds(seconds), requestTimeout));
    }
    private boolean isAuthorized(HttpExchange exchange) {
        String presented = StringUtils.defaultString(exchange.getRequestHeaders().getFirst(HttpHeaderNames.AUTHORIZATION.toString()));
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), RequestAuthorizer.bearer(secret).getBytes(StandardCharsets.UTF_8));
    }
    public static LlmRelay start(RunArgs args, ReviewEndpoint upstream, Log log) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(RunArgs.LOOPBACK_HOST, 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        LlmRelay toReturn = new LlmRelay(server, executor, args, upstream, log);
        server.createContext(API_PREFIX, toReturn::relay);
        server.start();
        return toReturn;
    }
    private static String error(String message, String type) {
        ObjectNode toReturn = MAPPER.createObjectNode();
        toReturn.putObject("error").put("message", message).put("type", type);
        return MAPPER.writeValueAsString(toReturn);
    }
    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
