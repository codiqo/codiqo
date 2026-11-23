package io.codiqo.llm.review;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The few routes of the OpenCode v2 HTTP API a review needs, checked against the OpenAPI documents of OpenCode 2.0.20,
 * 2.2.7, 2.3.0 and 2.3.2.
 *
 * <p>The wait route is still under {@code /api/experimental/} and blocks until the session's own run is idle. Up to
 * OpenCode 2.2 that included every sub-agent a coordinator started in the foreground; from 2.3 every sub-agent runs in
 * the background, so the route listing the running sessions tells when they are done ({@code OpenCodeSessions}). It has
 * the same answer in 2.2.7, 2.3.0 and 2.3.2. From 2.3.2 a shell command still running at its timeout moves to the
 * background too, where no session runs while it does, so the job listing tells when it is done.
 */
public class OpenCodeClient implements Closeable {
    public static final String ORDER_OLDEST_FIRST = "asc";
    public static final String USER = "opencode";
    public static final MediaType JSON = MediaType.get(HttpHeaderValues.APPLICATION_JSON.toString());
    public static final int PAGE = 100;
    public static final String ASSISTANT = "assistant";
    public static final String USER_MESSAGE = "user";
    public static final String MCP_CONNECTED = "connected";

    /** what OpenCode lists an MCP server as while it connects; a server not listed at all has not been started yet */
    public static final String MCP_PENDING = "pending";
    public static final Duration MCP_POLL = Duration.ofMillis(250);

    private final OkHttpClient http;
    private final HttpUrl base;
    private final String authorization;
    private final JsonMapper mapper;
    /** a client made by {@link #within} shares this one's connections and threads, which only their owner closes */
    private final boolean owner;

    public OpenCodeClient(String url, String password, Duration timeout) {
        this(HttpUrl.parse(url), Credentials.basic(USER, password), new OkHttpClient.Builder().readTimeout(timeout).callTimeout(timeout).build(),
                JsonMapper.builder().build(), true);
    }
    private OpenCodeClient(HttpUrl base, String authorization, OkHttpClient http, JsonMapper mapper, boolean owner) {
        this.base = Objects.requireNonNull(base);
        this.authorization = Objects.requireNonNull(authorization);
        this.http = Objects.requireNonNull(http);
        this.mapper = Objects.requireNonNull(mapper);
        this.owner = owner;
    }
    /**
     * The same client with its timeouts shortened to {@code limit}, never lengthened: the client's own still end a call to
     * an OpenCode that stopped answering sooner. It shares this one's connections, and closing it closes nothing.
     */
    public OpenCodeClient within(Duration limit) {
        return new OpenCodeClient(base, authorization, http.newBuilder()
                .callTimeout(shorter(limit, http.callTimeoutMillis()))
                .readTimeout(shorter(limit, http.readTimeoutMillis()))
                .build(), mapper, false);
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
    /** blocks until the session's own run is idle; a session already idle answers at once */
    public void awaitIdle(String sessionId) throws IOException {
        post(path("api", "experimental", "session", sessionId, "wait"), mapper.createObjectNode());
    }
    /** every session of the server running right now, its children included */
    public Set<String> activeSessions() throws IOException {
        JsonNode data = get(path("api", "session", "active")).path("data");
        if (data.isObject()) {
            return Sets.newHashSet(data.propertyNames());
        }
        /** read as nothing running, any other answer would end every coordinator turn after its first response */
        throw new IOException("GET /api/session/active answered without its data object: " + StringUtils.abbreviate(data.toString(), RunArgs.REVIEW_ANSWER_PREVIEW));
    }
    /**
     * The shell commands OpenCode runs in the background for the session's tree, itself included. OpenCode 2.3.2 moves a
     * command still running at its timeout (two minutes unless the agent set one) there instead of stopping it, and tells
     * the agent to end its response if it has nothing else to do: the agent's session is then idle, and absent from
     * {@link #activeSessions()}, until the command's result wakes it. A job is listed until that result is in the
     * session, finished or not, and OpenCode wakes the session before it drops the job, so a look at these jobs taken
     * before a look at the running sessions misses no session that will run again.
     */
    public List<ShellJob> backgroundShells(String sessionId) throws IOException {
        return shellJobs(shellJobsUrl().addQueryParameter("sessionID", sessionId).addQueryParameter("descendants", Boolean.TRUE.toString()).build());
    }
    /**
     * Stops a shell command and drops its output. A command OpenCode runs in the background then ends with an error,
     * which wakes its session as a finished command's result does (checked on OpenCode 2.3.2, where stopping it again
     * also answers 204). The command belongs to the directory OpenCode serves, the review's work tree.
     */
    public void stopShell(String shellId) throws IOException {
        execute(new Request.Builder().url(path("api", "shell", shellId)).delete());
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
    /**
     * Fails unless OpenCode lists the sessions running right now and the shell commands it runs in the background, which
     * tell when a sub-agent running in the background is done (OpenCode 2.2.7 and later do; the job listing came in
     * 2.2.6, the running sessions earlier): checked before any agent is paid for, where a review on an OpenCode without
     * either route would fail only after its coordinator's first response.
     */
    public void requireBackgroundRoutes() throws IOException {
        try {
            activeSessions();
            shellJobs(shellJobsUrl().build());
        } catch (IOException err) {
            throw new IOException("OpenCode could not list its running sessions or background shell commands (" + err.getMessage() + "); the review needs "
                    + "GET /api/session/active and GET /api/experimental/job to wait for its subagents, which OpenCode 2.2.7 and later serve", err);
        }
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
            JsonNode page = messagePage(sessionId, cursor);
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
        if (owner) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
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
            try {
                return StringUtils.isBlank(text) ? mapper.createObjectNode() : mapper.readTree(text);
            } catch (JacksonException err) {
                throw new IOException(response.request().method() + " " + response.request().url().encodedPath() + " answered with something other than JSON: "
                        + err.getOriginalMessage(), err);
            }
        }
        throw new IOException(response.request().method() + " " + response.request().url().encodedPath() + " failed with " + response.code() + ": " + text);
    }
    /**
     * One page of a session's messages, oldest first, as OpenCode 2.0.20 serves them: the first page names the order and
     * a cursor carries it, so a later page that names it again is refused ("Cursor cannot be combined with order").
     */
    private JsonNode messagePage(String sessionId, Optional<String> cursor) throws IOException {
        HttpUrl.Builder url = path("api", "session", sessionId, "message").newBuilder().addQueryParameter("limit", Integer.toString(PAGE));
        if (cursor.isPresent()) {
            url.addQueryParameter("cursor", cursor.get());
        } else {
            url.addQueryParameter("order", ORDER_OLDEST_FIRST);
        }
        return get(url.build());
    }
    /** the listed shell jobs, refusing an answer without its list as {@link #activeSessions()} does */
    private List<ShellJob> shellJobs(HttpUrl url) throws IOException {
        JsonNode data = get(url).path("data");
        if (data.isArray()) {
            return data.valueStream()
                    .map(job -> new ShellJob(job.path("sessionID").asString(), job.path("shellID").asString(), Instant.ofEpochMilli(job.path("time").path("started").asLong())))
                    .toList();
        }
        throw new IOException("GET /api/experimental/job answered without its data array: " + StringUtils.abbreviate(data.toString(), RunArgs.REVIEW_ANSWER_PREVIEW));
    }
    private HttpUrl.Builder shellJobsUrl() {
        return path("api", "experimental", "job").newBuilder().addQueryParameter("kind", "shell");
    }
    private HttpUrl path(String... segments) {
        HttpUrl.Builder toReturn = base.newBuilder();
        for (String segment : segments) {
            toReturn.addPathSegment(segment);
        }
        return toReturn.build();
    }
    /** an interruptible pause between two reads of OpenCode's state */
    public static void sleep(Duration duration) throws InterruptedIOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            InterruptedIOException toThrow = new InterruptedIOException("interrupted while waiting for OpenCode");
            toThrow.initCause(err);
            throw toThrow;
        }
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
    /** the shorter of the limit and the client's own timeout, which OkHttp keeps as 0 when there is none */
    private static Duration shorter(Duration limit, int ownMillis) {
        return ownMillis > 0 ? ObjectUtils.min(limit, Duration.ofMillis(ownMillis)) : limit;
    }

    /** a shell command OpenCode runs in the background: the session it wakes, the command's id and when it started */
    public static final class ShellJob extends ImmutableTriple<String, String, Instant> {
        public ShellJob(String sessionId, String shellId, Instant started) {
            super(sessionId, shellId, started);
        }
        public String getSessionId() {
            return getLeft();
        }
        public String getShellId() {
            return getMiddle();
        }
        public Instant getStarted() {
            return getRight();
        }
    }
}
