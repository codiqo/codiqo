package io.codiqo.llm.review;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.google.common.collect.Lists;

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
        return post(path("api", "session"), body).path("data").path("id").asString();
    }
    public String fork(String sessionId) throws IOException {
        return post(path("api", "session", sessionId, "fork"), mapper.createObjectNode()).path("data").path("id").asString();
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
     * The text of the newest assistant message that has any. The list is asked for assistant messages newest first,
     * as OpenCode 2.0.20's OpenAPI document offers ({@code type=assistant}, {@code order=desc}), and followed page by
     * page, each page asking for that order again since nothing says the cursor keeps it: a single unordered page of
     * 100 messages, with tool results and the repair turn counted in, could hold only the oldest messages of a long
     * coordinator session and miss its actual answer.
     */
    public String finalAnswer(String sessionId) throws IOException {
        HttpUrl messages = path("api", "session", sessionId, "message").newBuilder()
                .addQueryParameter("type", "assistant")
                .addQueryParameter("limit", Integer.toString(PAGE))
                .addQueryParameter("order", "desc")
                .build();
        JsonNode page = get(messages);
        for (;;) {
            for (JsonNode message : page.path("data")) {
                String text = text(message);
                if (StringUtils.isNotBlank(text)) {
                    return text;
                }
            }
            JsonNode next = page.path("cursor").path("next");
            if (next.isString()) {
                page = get(messages.newBuilder().addQueryParameter("cursor", next.asString()).build());
            } else {
                throw new IOException("session " + sessionId + " finished without an answer");
            }
        }
    }
    public SessionUsage usage(String sessionId) throws IOException {
        return toUsage(get(path("api", "session", sessionId)).path("data"));
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
    private HttpUrl path(String... segments) {
        HttpUrl.Builder toReturn = base.newBuilder();
        for (String segment : segments) {
            toReturn.addPathSegment(segment);
        }
        return toReturn.build();
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
