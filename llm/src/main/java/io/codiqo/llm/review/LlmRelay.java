package io.codiqo.llm.review;

import static java.util.function.Predicate.not;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.time.DateUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.common.base.Joiner;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.openai.core.JsonMissing;
import com.openai.core.ObjectMappers;
import com.openai.models.ErrorObject;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.util.RequestAuthorizer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import okhttp3.HttpUrl;

/**
 * A loop back end point the review's agents call instead of the model's. For the backend's LLM proxy it attaches the
 * member's credential per request, refreshed as it ages, so the token never reaches OpenCode's configuration on disk
 * and a review that outlasts it keeps going. It also sets the review's sampling on every chat completion, since
 * OpenCode 2.0 sends none.
 *
 * <p>
 * Any process on the machine can reach a loop back port, so a caller must present the secret made for this review;
 * anything else is refused before it can spend the organization's budget.
 */
public final class LlmRelay implements AutoCloseable {
    public static final String API_PREFIX = "/v1";

    private static final String EVENT_END = StringUtils.repeat(StringUtils.LF, 2);
    private static final List<String> FORWARDED_REQUEST_HEADERS = List.of(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderNames.ACCEPT.toString());
    private static final List<String> FORWARDED_RESPONSE_HEADERS = List.of(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderNames.RETRY_AFTER.toString());
    private static final String CHAT_COMPLETIONS = "/chat/completions";
    private static final String RETRY_AFTER_MS = "retry-after-ms";
    private static final String SHOULD_RETRY = "x-should-retry";
    /**
     * The most a rate-limit wait is doubled. The rounds are a setting with no bound of their own, and {@code 1L << n}
     * wraps once n reaches 63; the maximum back off caps the wait long before this does.
     */
    private static final int MAX_DOUBLINGS = 16;
    private static final double JITTER = 0.2;
    /**
     * How much of a 429's body is read ahead to quote it: an error object is a few hundred bytes. The whole body still
     * goes on to OpenCode, which reads it to tell a rate limit from an exhausted quota.
     */
    private static final int RATE_LIMIT_BODY_BYTES = 4096;
    /**
     * How much of the provider's 429 text is quoted. The quote ends up in the error of a failed review, which becomes
     * the project's halt cause, and a provider's text has no bound of its own (an HTML error page, a policy notice).
     */
    private static final int RATE_LIMIT_TEXT_CHARS = 256;
    private static final String RATE_LIMIT_HEADER_PREFIX = "x-ratelimit";
    /**
     * The answers in which the provider refused a request itself: a prompt longer than the model's context (400 with
     * {@code context_length_exceeded}, 413) or a body it could not process (422). They follow from what the commit asks
     * the model, so a review that failed after them is the commit's failure, never the provider's outage.
     */
    private static final Set<Integer> REFUSED_REQUESTS = Set.of(
            HttpResponseStatus.BAD_REQUEST.code(),
            HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code(),
            HttpResponseStatus.UNPROCESSABLE_ENTITY.code());

    /**
     * Matched exactly: the member's credential reaches far more of the backend than the LLM proxy, and the server
     * context matches by prefix, which let {@code /v1x} or {@code /v1/../api/...} through with any method.
     */
    private static final Set<String> FORWARDED_CALLS = Set.of("POST " + CHAT_COMPLETIONS, "GET /models");
    private static final JsonMapper OPENAI = ObjectMappers.jsonMapper();
    private static final Joiner COMMA = Joiner.on(", ");

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient http;
    private final HttpUrl upstream;
    private final RequestAuthorizer authorizer;
    private final int retries;
    private final Duration requestTimeout;
    private final Duration retryBackoff;
    private final int rateLimitRetries;
    private final Duration rateLimitBackoff;
    private final Duration rateLimitMaxBackoff;
    /**
     * The rate limit's rounds since the last success. A round is one wait every call shares: the reviewers run in
     * parallel on one key, so each call refused while a round lasts is told to come back when that round ends. Counting
     * a round per refused call would end the review after one burst.
     */
    private final AtomicInteger rateLimitRound = new AtomicInteger();
    /** guards {@link #rateLimitRoundStarted} and {@link #rateLimitRoundEnds} */
    private final Object rateLimitLock = new Object();
    private Instant rateLimitRoundStarted = Instant.EPOCH;
    private Instant rateLimitRoundEnds = Instant.EPOCH;
    /**
     * Set once the rounds ran out. A limit that outlasts them outlasts the review, so every later call is refused at once
     * and the review fails within minutes as the provider's failure, which halts the project and aborts the run.
     */
    private final AtomicBoolean rateLimitExhausted = new AtomicBoolean();
    private final double temperature;
    private final double topP;
    private final String secret;
    private final Multiset<Integer> providerErrors = ConcurrentHashMultiset.create();
    /**
     * What the provider's last 429 said, quoted by {@link #providerErrors()}: the status counts alone cannot tell a short
     * rate limit from an exhausted usage quota, and a provider once refused every call for eight hours with nothing
     * recorded to say which.
     */
    private final AtomicReference<String> lastRateLimitText = new AtomicReference<>();
    private final Log log;

    private LlmRelay(HttpServer server, ExecutorService executor, RunArgs args, ReviewEndpoint upstream, Log log) {
        this.server = Objects.requireNonNull(server);
        this.executor = Objects.requireNonNull(executor);
        this.http = HttpClient.newBuilder().connectTimeout(args.getReviewRelayConnectTimeout()).executor(executor).build();
        this.upstream = HttpUrl.get(upstream.getBaseUrl());
        this.authorizer = Optional.ofNullable(upstream.getAuthorizer()).orElseGet(() -> direct(upstream.getApiKey()));
        this.retries = Math.max(0, args.getReviewRelayRetries());
        /**
         * A direct end point gets the whole review timeout to start answering: a local model evaluating a long prompt can
         * take minutes before its first byte. The relay's own first-byte limit is for the proxy, where such a wait is a hang.
         */
        this.requestTimeout = Objects.isNull(upstream.getAuthorizer()) ? args.getReviewTimeout() : Objects.requireNonNull(args.getReviewRelayRequestTimeout());
        this.retryBackoff = Objects.requireNonNull(args.getReviewRelayRetryBackoff());
        this.rateLimitRetries = Math.max(0, args.getReviewRelayRateLimitRetries());
        this.rateLimitBackoff = Objects.requireNonNull(args.getReviewRelayRateLimitBackoff());
        this.rateLimitMaxBackoff = Objects.requireNonNull(args.getReviewRelayRateLimitMaxBackoff());
        this.temperature = args.getReviewTemperature();
        this.topP = args.getReviewTopP();
        this.log = Objects.requireNonNull(log);

        this.secret = RandomStringUtils.secure().nextAlphanumeric(RunArgs.LOOPBACK_SECRET_LENGTH);
    }
    public String getUrl() {
        return RunArgs.loopbackUrl(server.getAddress().getPort()).encodedPath(API_PREFIX).build().toString();
    }
    public String getSecret() {
        return secret;
    }
    /**
     * The end point's failed answers so far, retried ones included, for the error of a review that failed: a turn that
     * timed out while the provider answered 429 for an hour says only "timeout" on its own.
     */
    public Optional<String> providerErrors() {
        List<String> counts = providerErrors.entrySet().stream()
                .sorted(Comparator.comparing(Multiset.Entry::getElement))
                .map(entry -> entry.getElement() + StringUtils.SPACE + (entry.getCount() == 1 ? "once" : entry.getCount() + " times"))
                .toList();

        Optional<String> toReturn = Optional.empty();
        if (CollectionUtils.isNotEmpty(counts)) {
            String listed = counts.getLast();
            if (counts.size() > 1) {
                listed = COMMA.join(counts.subList(0, counts.size() - 1)) + " and " + listed;
            }
            String summary = "the LLM provider answered " + listed + " during the review";
            Optional<String> providerSays = Optional.ofNullable(lastRateLimitText.get());
            if (providerSays.isPresent()) {
                summary += "; its last 429 said: " + providerSays.get();
            }
            toReturn = Optional.of(summary);
        }
        return toReturn;
    }
    /**
     * Whether the provider failed during the review: any failed answer but a {@link #REFUSED_REQUESTS refused request},
     * the relay's own 502 and 504 for an end point that could not be reached, did not answer in time or cut its answer
     * short included. Parallel reviewers share the relay, so this cannot say which call failed: LocalReviewer settles an
     * answer in the wrong shape as the commit's failure before it asks here.
     */
    public boolean providerFailed() {
        return providerErrors.elementSet().stream().anyMatch(not(REFUSED_REQUESTS::contains));
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
        if (CHAT_COMPLETIONS.equals(path)) {
            try {
                body = sampled(body);
            } catch (IOException err) {
                log.error("llm relay: refused a chat completion that is not a JSON request, which would leave without the review's sampling: %s", err.getMessage());
                respond(exchange, HttpResponseStatus.BAD_REQUEST.code(), error("not a chat completion request", "invalid_request_error"));
                return;
            }
        }

        HttpResponse<InputStream> response;
        Instant sentAt;
        try {
            if (rateLimitExhausted.get()) {
                respondRateLimitExhausted(exchange);
                return;
            }
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
             * The relay retries no call to the model: OpenCode retries a failed call itself, and retries stacked on its
             * own are how one review answered "429 132 times". Only the credential's refresh is retried, since it is
             * Codiqo's own login, authorised per call because a call may come after the token expired. One that still
             * cannot be refreshed is the member's login ending, not the proxy being down, so it answers 401, which an
             * OpenAI client does not retry, and the agents stop instead of spending their step budget.
             */
            for (int attempt = 0;; attempt++) {
                try {
                    authorizer.accept(request::setHeader);
                    break;
                } catch (IOException err) {
                    if (attempt < retries) {
                        log.warn("llm relay: the Codiqo credential could not be refreshed (%s), retry %d of %d", err.getMessage(), attempt + 1, retries);
                        Thread.sleep(backoff(attempt));
                    } else {
                        log.warn("llm relay: the Codiqo credential could not be refreshed: %s", err.getMessage());
                        respond(exchange, HttpResponseStatus.UNAUTHORIZED.code(), error("the Codiqo login could not be refreshed; log in again", "authentication_error"));
                        return;
                    }
                }
            }

            sentAt = Instant.now();
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException err) {
                /**
                 * Answered as a 5xx, which OpenCode retries, and counted as the provider's failure: an end point that
                 * cannot be reached or does not answer in time is the outage ReviewProviderException exists for, and
                 * left uncounted it would exclude the commit for good.
                 */
                log.warn("llm relay: %s %s failed: %s", exchange.getRequestMethod(), path, err.getMessage());
                boolean timedOut = err instanceof HttpTimeoutException;
                int status = (timedOut ? HttpResponseStatus.GATEWAY_TIMEOUT : HttpResponseStatus.BAD_GATEWAY).code();
                providerErrors.add(status);
                respond(exchange, status, error(timedOut ? "the model's end point did not answer in time" : "the model's end point could not be reached", "api_error"));
                return;
            }
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return;
        }

        if (HttpStatusClass.valueOf(response.statusCode()) == HttpStatusClass.SUCCESS) {
            rateLimitRecovered(sentAt);
        } else {
            providerErrors.add(response.statusCode());
        }

        Optional<Duration> retryIn = Optional.empty();
        byte[] readAhead = ArrayUtils.EMPTY_BYTE_ARRAY;
        if (response.statusCode() == HttpResponseStatus.TOO_MANY_REQUESTS.code()) {
            readAhead = response.body().readNBytes(RATE_LIMIT_BODY_BYTES);
            String providerSays = rateLimitText(readAhead, response);
            lastRateLimitText.set(providerSays);
            retryIn = rateLimitRound(exchange, path, response, providerSays);
            if (retryIn.isEmpty()) {
                response.body().close();
                respondRateLimitExhausted(exchange);
                return;
            }
        }

        for (String header : FORWARDED_RESPONSE_HEADERS) {
            response.headers().firstValue(header).ifPresent(value -> exchange.getResponseHeaders().set(header, value));
        }
        retryIn.ifPresent(wait -> {
            exchange.getResponseHeaders().set(RETRY_AFTER_MS, Long.toString(wait.toMillis()));
            exchange.getResponseHeaders().set(HttpHeaderNames.RETRY_AFTER.toString(), Long.toString(Math.ceilDiv(wait.toMillis(), DateUtils.MILLIS_PER_SECOND)));
        });
        exchange.sendResponseHeaders(response.statusCode(), 0);
        boolean streamed = response.headers().firstValue(HttpHeaderNames.CONTENT_TYPE.toString()).map(type -> Strings.CI.startsWith(type, HttpHeaderValues.TEXT_EVENT_STREAM)).orElse(false);
        try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
            out.write(readAhead);
            byte[] buffer = IOUtils.byteArray();
            for (int read = upstreamRead(in, buffer, out, exchange, path, streamed); read >= 0; read = upstreamRead(in, buffer, out, exchange, path, streamed)) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }
    }
    /**
     * One read of the provider's answer. A read that fails is the provider cutting its answer short after the 200 went
     * out, so it is counted as the relay's 502: otherwise the review that failed on the cut would exclude the commit as
     * its own failure. A streamed answer ends with an error event, which an OpenAI client raises; a plain body is left
     * short, which no client parses. A read interrupted by the relay closing, which the JDK client reports as an
     * IOException caused by an InterruptedException, is not the provider's and is not counted.
     */
    private int upstreamRead(InputStream in, byte[] buffer, OutputStream out, HttpExchange exchange, String path, boolean streamed) throws IOException {
        try {
            return in.read(buffer);
        } catch (IOException err) {
            if (isInterruption(err)) {
                throw err;
            }
            log.warn("llm relay: %s %s was cut short after the answer started: %s", exchange.getRequestMethod(), path, err.getMessage());
            providerErrors.add(HttpResponseStatus.BAD_GATEWAY.code());
            if (streamed) {
                out.write((StringUtils.LF + "data: " + error("the model's answer was cut short", "api_error") + EVENT_END).getBytes(StandardCharsets.UTF_8));
            }
            throw err;
        }
    }
    /**
     * The request with the review's sampling set and no seed, whatever the agent sent, read and written as the OpenAI
     * SDK's own request body, which keeps every field it does not model ({@code stream} among them) as it came.
     */
    @SuppressWarnings({ "deprecation", "unchecked" })
    private byte[] sampled(byte[] body) throws IOException {
        ChatCompletionCreateParams.Body chat = OPENAI.readValue(body, ChatCompletionCreateParams.Body.class).toBuilder()
                .temperature(temperature)
                .topP(topP)
                .seed(JsonMissing.of())
                .build();
        return OPENAI.writeValueAsBytes(chat);
    }
    private Duration backoff(int attempt) {
        return retryBackoff.multipliedBy(1L << attempt);
    }
    /**
     * The wait after the n-th 429 (from 0): the rate-limit back off doubled n times, a longer {@code Retry-After}
     * instead, never beyond the maximum, then spread by up to a fifth either way so the review's parallel calls do not
     * all come back in the same second.
     */
    private Duration rateLimitWait(HttpResponse<?> answer, int rateLimited) {
        Duration doubled = rateLimitBackoff.multipliedBy(1L << Math.min(rateLimited, MAX_DOUBLINGS));
        Duration wait = ObjectUtils.min(ObjectUtils.max(doubled, rawRetryAfter(answer).orElse(Duration.ZERO)), rateLimitMaxBackoff);
        double spread = 1.0 + JITTER * (2 * ThreadLocalRandom.current().nextDouble() - 1);
        return ObjectUtils.min(Duration.ofMillis(Math.round(wait.toMillis() * spread)), rateLimitMaxBackoff);
    }
    /**
     * The provider's own words for a 429: the OpenAI error's {@code message}, the plain {@code error} string some
     * providers send instead, or the raw body, followed by {@code Retry-After} and the {@code x-ratelimit-*} headers,
     * where providers say which limit was hit and when it resets.
     */
    private static String rateLimitText(byte[] body, HttpResponse<?> answer) {
        String raw = new String(body, StandardCharsets.UTF_8);
        String message = raw;
        if (StringUtils.isNotBlank(raw)) {
            try {
                /** readTree answers null for a body that holds no JSON at all */
                JsonNode error = Optional.ofNullable(OPENAI.readTree(body)).map(tree -> tree.path("error")).orElseGet(MissingNode::getInstance);
                if (error.isTextual()) {
                    message = error.asText();
                } else if (error.path("message").isTextual()) {
                    message = error.path("message").asText();
                }
            } catch (IOException err) {
                message = raw;
            }
        }

        List<String> limits = Lists.newArrayList();
        answer.headers().map().forEach((name, values) -> {
            if (BooleanUtils.or(new boolean[] { Strings.CI.startsWith(name, RATE_LIMIT_HEADER_PREFIX), Strings.CI.equals(name, HttpHeaderNames.RETRY_AFTER) })) {
                limits.add(name + "=" + COMMA.join(values));
            }
        });
        String toReturn = StringUtils.abbreviate(StringUtils.normalizeSpace(StringUtils.defaultIfBlank(message, "(no body)")), RATE_LIMIT_TEXT_CHARS);
        if (CollectionUtils.isNotEmpty(limits)) {
            toReturn += " (" + COMMA.join(limits) + ")";
        }
        return toReturn;
    }
    private static boolean isInterruption(IOException err) {
        return BooleanUtils.or(new boolean[] {
                Thread.currentThread().isInterrupted(),
                err instanceof InterruptedIOException,
                ExceptionUtils.indexOfThrowable(err, InterruptedException.class) >= 0 });
    }
    private static Optional<Duration> rawRetryAfter(HttpResponse<?> answer) {
        return answer.headers().firstValue(HttpHeaderNames.RETRY_AFTER.toString())
                .map(value -> NumberUtils.toLong(StringUtils.trim(value), -1))
                .filter(seconds -> seconds >= 0)
                .map(Duration::ofSeconds);
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
    private static RequestAuthorizer direct(String apiKey) {
        return header -> {
            if (StringUtils.isNotBlank(apiKey)) {
                header.accept(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(apiKey));
            }
        };
    }
    /**
     * The wait a 429 is passed on with, which OpenCode keeps before it calls again; empty once the rounds ran out.
     * OpenCode 2 retries a rate-limited call itself (ten times, 2 seconds doubling to at most 10, or the answer's
     * {@code retry-after-ms} up to 15 minutes, read from its binary) and has no setting for it, so the
     * relay steers that retry instead of retrying on top of it. The provider's own error body goes on with the wait: OpenCode 2.0.25 reads it to tell a rate limit, which
     * it retries, from an exhausted quota or budget ({@code insufficient_quota}, "quota exceeded"), which it does not. A
     * body of the relay's own would make every exhausted quota look like a rate limit and keep the review waiting
     * through every round, about 35 minutes with the defaults, for a quota that is not coming back.
     */
    private Optional<Duration> rateLimitRound(HttpExchange exchange, String path, HttpResponse<?> answer, String providerSays) {
        Instant now = Instant.now();
        synchronized (rateLimitLock) {
            if (now.isBefore(rateLimitRoundEnds)) {
                return Optional.of(Duration.between(now, rateLimitRoundEnds));
            }
            int round = rateLimitRound.incrementAndGet();
            if (round > rateLimitRetries) {
                rateLimitExhausted.set(true);
                log.error("llm relay: the provider still answered 429 after %d rounds of waiting; every further call of this review is refused. It said: %s",
                        rateLimitRetries, providerSays);
                return Optional.empty();
            }
            rateLimitRoundStarted = now;
            rateLimitRoundEnds = now.plus(rateLimitWait(answer, round - 1));
            log.warn("llm relay: %s %s answered 429; rate-limit round %d of %d, every call comes back in %s. The provider said: %s",
                    exchange.getRequestMethod(),
                    path,
                    round,
                    rateLimitRetries,
                    Duration.between(now, rateLimitRoundEnds),
                    providerSays);
            return Optional.of(Duration.between(now, rateLimitRoundEnds));
        }
    }
    /**
     * Ends the rate limit's rounds, but only for a call sent after the current round started. A parallel call sent
     * before the provider began refusing can succeed after a round started; it says nothing about the limit having
     * lifted, and resetting on it would restart the schedule at its shortest round, so a mixed burst could keep the
     * review waiting past every configured round.
     */
    private void rateLimitRecovered(Instant sentAt) {
        synchronized (rateLimitLock) {
            if (sentAt.isAfter(rateLimitRoundStarted)) {
                rateLimitRound.set(0);
            }
        }
    }
    /**
     * The answer once the rounds ran out: a 429 OpenCode is told not to retry ({@code x-should-retry: false}, which its
     * session retry reads before anything else). Retried, each call would wait through the whole schedule again.
     */
    private static void respondRateLimitExhausted(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set(SHOULD_RETRY, Boolean.FALSE.toString());
        respond(exchange, HttpResponseStatus.TOO_MANY_REQUESTS.code(), error("the LLM provider is still rate limiting after every round of waiting", "rate_limit_exhausted"));
    }
    private static String error(String message, String type) throws JsonProcessingException {
        return OPENAI.writeValueAsString(Map.of("error", ErrorObject.builder().message(message).type(type).code(Optional.empty()).param(Optional.empty()).build()));
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
