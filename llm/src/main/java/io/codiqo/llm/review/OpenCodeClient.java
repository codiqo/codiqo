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
    public String finalAnswer(String sessionId) throws IOException {
        Optional<String> toReturn = answer(sessionId);
        if (toReturn.isPresent()) {
            return toReturn.get();
        }
        throw new IOException("session " + sessionId + " finished without an answer");
    }
    /**
     * The text of the newest assistant message that has any, read from the assistant messages newest first and
     * followed page by page: a single unordered page of 100 messages, with tool results counted in, could hold only the
     * oldest messages of a long coordinator session and miss its actual answer. Empty when no assistant message of the
     * session has text; a failed request to OpenCode is an {@link IOException}, never an empty answer.
     */
    public Optional<String> answer(String sessionId) throws IOException {
        Optional<String> cursor = Optional.empty();
        do {
            JsonNode page = messagePage(sessionId, Optional.of(ASSISTANT), ORDER_NEWEST_FIRST, cursor);
            for (JsonNode message : page.path("data")) {
                String text = text(message);
                if (StringUtils.isNotBlank(text)) {
                    return Optional.of(text);
                }
            }
            cursor = nextPage(page);
        } while (cursor.isPresent());
        return Optional.empty();
    }
    /**
     * The error the session's last turn ended with, when it failed: OpenCode records it on the newest assistant
     * message ({@code finish} {@code error}, {@code error.type} such as {@code provider.invalid-output}) and goes idle.
     */
    public Optional<JsonNode> turnError(String sessionId) throws IOException {
        JsonNode newest = messagePage(sessionId, Optional.of(ASSISTANT), ORDER_NEWEST_FIRST, Optional.empty()).path("data").path(0);
        if (newest.path("error").isObject()) {
            return Optional.of(newest.path("error"));
        }
        return Optional.empty();
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
    private static String text(JsonNode message) {
        String text = message.path("text").asString();
        if (StringUtils.isNotBlank(text)) {
            return text;
        }
        StringBuilder toReturn = new StringBuilder();
        for (JsonNode part : message.path("content")) {
            if ("text".equals(part.path("type").asString())) {
                toReturn.append(part.path("text").asString());
            }
        }
        return toReturn.toString();
    }
}
