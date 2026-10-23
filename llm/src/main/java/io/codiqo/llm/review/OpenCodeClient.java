package io.codiqo.llm.review;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The few routes of the OpenCode v2 HTTP API a review needs, checked against OpenCode 2.0.20's own OpenAPI document.
 *
 * <p>The wait route is still under {@code /api/experimental/} in that version and blocks until the session is idle,
 * which for a coordinator includes every foreground sub-agent it started; a pinned OpenCode version keeps it stable.
 */
public class OpenCodeClient implements Closeable {
    private static final String USER = "opencode";
    private static final MediaType JSON = MediaType.get(HttpHeaderValues.APPLICATION_JSON.toString());
    private static final int PAGE = 100;
    private static final String ASSISTANT = "assistant";
    private static final String USER_MESSAGE = "user";
    private static final String STOP = "stop";
    private static final String MCP_CONNECTED = "connected";
    /** what OpenCode lists an MCP server as while it connects; a server not listed at all has not been started yet */
    private static final String MCP_PENDING = "pending";
    private static final Duration MCP_POLL = Duration.ofMillis(250);
    private static final String ORDER_NEWEST_FIRST = "desc";
    private static final String ORDER_OLDEST_FIRST = "asc";

    private final OkHttpClient http;
    private final HttpUrl base;
    private final String authorization;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenCodeClient(String url, String password, Duration timeout) {
        this.base = HttpUrl.parse(url);
        this.authorization = Credentials.basic(USER, password);
        this.http = new OkHttpClient.Builder().readTimeout(timeout).callTimeout(timeout).build();
    }
    public String createSession(Path directory, String agent, String title) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("location").put("directory", directory.toAbsolutePath().toString());
        body.put("agent", agent);
        body.put("title", title);
        return sessionId(post(path("api", "session"), body), "a new session");
    }
    public String fork(String sessionId) throws IOException {
        return sessionId(post(path("api", "session", sessionId, "fork"), mapper.createObjectNode()), "a fork of " + sessionId);
    }
    public void prompt(String sessionId, String text) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("text", text);
        post(path("api", "session", sessionId, "prompt"), body);
    }
    public void awaitIdle(String sessionId) throws IOException {
        post(path("api", "experimental", "session", sessionId, "wait"), mapper.createObjectNode());
    }
    /**
     * Waits until OpenCode has connected to the named MCP server. OpenCode 2.1.1 lists the server about a second after it
     * starts, as pending while it connects, then as connected, or as failed with the reason ("Unable to connect...").
     */
    public void awaitMcpConnected(String server, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<JsonNode> status = Optional.empty();
        while (System.nanoTime() < deadline) {
            status = get(path("api", "mcp")).path("data").valueStream()
                    .filter(listed -> server.equals(listed.path("name").asString()))
                    .map(listed -> listed.path("status"))
                    .findFirst();
            String state = status.map(listed -> listed.path("status").asString()).orElse(MCP_PENDING);
            if (MCP_CONNECTED.equals(state)) {
                return;
            }
            if (MCP_PENDING.equals(state)) {
                sleep(MCP_POLL);
            } else {
                throw new IOException("OpenCode could not connect to the MCP server " + server + ": " + status.get());
            }
        }
        throw new IOException("OpenCode did not connect to the MCP server " + server + " within " + timeout + ": " + status.map(JsonNode::toString).orElse("not listed"));
    }
    public SessionUsage usage(String sessionId) throws IOException {
        return toUsage(session(sessionId));
    }
    public List<SessionUsage> childUsage(String sessionId) throws IOException {
        HttpUrl url = path("api", "session").newBuilder()
                .addQueryParameter("parentID", sessionId)
                .addQueryParameter("limit", Integer.toString(PAGE))
                .build();
        List<SessionUsage> toReturn = Lists.newArrayList();
        for (JsonNode session : get(url).path("data")) {
            toReturn.add(toUsage(session));
        }
        return toReturn;
    }
    public JsonNode session(String sessionId) throws IOException {
        return get(path("api", "session", sessionId)).path("data");
    }
    /** every message of the session, oldest first, as OpenCode records it: text, reasoning and tool calls with their input and output */
    public List<JsonNode> messages(String sessionId) throws IOException {
        List<JsonNode> toReturn = Lists.newArrayList();
        Optional<String> cursor = Optional.empty();
        do {
            JsonNode page = messagePage(sessionId, Optional.empty(), ORDER_OLDEST_FIRST, cursor);
            page.path("data").forEach(toReturn::add);
            cursor = nextPage(page);
        } while (cursor.isPresent());
        return toReturn;
    }
    /**
     * Every message of the session, oldest first, grouped into turns: a turn opens with a user message and holds every
     * message up to the next one, the messages OpenCode adds on its own inside a turn included (the "continue" it sends
     * after an answer the provider cut short is a synthetic message, not a user one).
     */
    public List<List<JsonNode>> turns(String sessionId) throws IOException {
        List<List<JsonNode>> toReturn = Lists.newArrayList();
        for (JsonNode message : messages(sessionId)) {
            if (BooleanUtils.or(new boolean[] { toReturn.isEmpty(), USER_MESSAGE.equals(message.path("type").asString()) })) {
                toReturn.add(Lists.newArrayList());
            }
            toReturn.getLast().add(message);
        }
        return toReturn;
    }
    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
    private JsonNode post(HttpUrl url, JsonNode body) throws IOException {
        return execute(new Request.Builder().url(url).post(RequestBody.create(mapper.writeValueAsString(body), JSON)));
    }
    private JsonNode get(HttpUrl url) throws IOException {
        return execute(new Request.Builder().url(url).get());
    }
    private JsonNode execute(Request.Builder request) throws IOException {
        Call call = http.newCall(request.header(HttpHeaderNames.AUTHORIZATION.toString(), authorization).build());
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call failed, IOException err) {
                answer.completeExceptionally(err);
            }
            @Override
            public void onResponse(Call succeeded, Response response) {
                try (response) {
                    answer.complete(read(response));
                } catch (IOException | RuntimeException err) {
                    answer.completeExceptionally(err);
                }
            }
        });

        try {
            return answer.get();
        } catch (InterruptedException err) {
            call.cancel();
            Thread.currentThread().interrupt();
            InterruptedIOException toThrow = new InterruptedIOException("interrupted while waiting for " + call.request().url().encodedPath());
            toThrow.initCause(err);
            throw toThrow;
        } catch (ExecutionException err) {
            return ExceptionUtils.rethrow(err.getCause());
        }
    }
    private JsonNode read(Response response) throws IOException {
        ResponseBody body = response.body();
        String text = Objects.isNull(body) ? StringUtils.EMPTY : body.string();
        if (response.isSuccessful()) {
            return StringUtils.isBlank(text) ? mapper.createObjectNode() : mapper.readTree(text);
        }
        throw new IOException(response.request().method() + " " + response.request().url().encodedPath() + " failed with " + response.code() + ": " + text);
    }
    /**
     * One page of a session's messages, as OpenCode 2.0.20 serves them: the first page names the order and a cursor
     * carries it, so a later page that names it again is refused ("Cursor cannot be combined with order"), while a
     * filter such as {@code type} is not carried and goes with every page.
     */
    private JsonNode messagePage(String sessionId, Optional<String> type, String order, Optional<String> cursor) throws IOException {
        HttpUrl.Builder url = path("api", "session", sessionId, "message").newBuilder().addQueryParameter("limit", Integer.toString(PAGE));
        type.ifPresent(value -> url.addQueryParameter("type", value));
        if (cursor.isPresent()) {
            url.addQueryParameter("cursor", cursor.get());
        } else {
            url.addQueryParameter("order", order);
        }
        return get(url.build());
    }
    private HttpUrl path(String... segments) {
        HttpUrl.Builder toReturn = base.newBuilder();
        for (String segment : segments) {
            toReturn.addPathSegment(segment);
        }
        return toReturn.build();
    }
    /** whether the turn's last assistant message ended on its own ({@code finish} "stop"), not on a tool call, an error or a cap */
    public static boolean stoppedOnItsOwn(List<JsonNode> turn) {
        return Lists.reverse(turn).stream()
                .filter(message -> ASSISTANT.equals(message.path("type").asString()))
                .findFirst()
                .filter(message -> STOP.equals(message.path("finish").asString()))
                .isPresent();
    }
    public static Optional<JsonNode> errorOf(List<JsonNode> turn) {
        return Lists.reverse(turn).stream()
                .filter(message -> ASSISTANT.equals(message.path("type").asString()))
                .findFirst()
                .map(message -> message.path("error"))
                .filter(JsonNode::isObject);
    }
    /** without an id every later call goes to {@code /api/session//...} and fails with a 404 that names nothing */
    private static String sessionId(JsonNode answer, String what) throws IOException {
        String toReturn = answer.path("data").path("id").asString();
        if (StringUtils.isNotBlank(toReturn)) {
            return toReturn;
        }
        throw new IOException("OpenCode created " + what + " without an id: " + StringUtils.abbreviate(answer.toString(), RunArgs.REVIEW_ANSWER_PREVIEW));
    }
    /** the last page still carries a cursor, so an empty page ends the list as well as a missing cursor does */
    private static Optional<String> nextPage(JsonNode page) {
        JsonNode next = page.path("cursor").path("next");
        if (BooleanUtils.and(new boolean[] { next.isString(), page.path("data").size() > 0 })) {
            return Optional.of(next.asString());
        }
        return Optional.empty();
    }
    private static SessionUsage toUsage(JsonNode session) {
        JsonNode tokens = session.path("tokens");
        return new SessionUsage(
                session.path("id").asString(),
                session.path("agent").asString(),
                session.path("model").path("id").asString(),
                tokens.path("input").asLong(),
                tokens.path("cache").path("read").asLong(),
                tokens.path("output").asLong(),
                tokens.path("reasoning").asLong());
    }
    private static void sleep(Duration duration) throws InterruptedIOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            InterruptedIOException toThrow = new InterruptedIOException("interrupted while waiting for OpenCode");
            toThrow.initCause(err);
            throw toThrow;
        }
    }
}
