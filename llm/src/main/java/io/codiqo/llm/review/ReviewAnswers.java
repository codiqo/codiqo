package io.codiqo.llm.review;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.sun.net.httpserver.HttpExchange;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The loop back MCP server the review's agents give their answers to, one {@link AnswerTool} per answer. Each answer is
 * read and checked as it is submitted, by the reader the review registered for it, and a refused answer is answered
 * with what is wrong, so the agent corrects it within the same turn instead of the review failing after it. An agent
 * gets {@link RunArgs#getReviewAnswerAttempts()} refused calls; after that its answer stays refused and the review
 * fails for it.
 *
 * <p>
 * OpenCode 2.1 names the calling session in every call's {@code _meta} ({@link #SESSION_META}, checked on 2.1.1 and
 * 2.1.2), which is how an answer is matched to the session that was asked for it: a reviewer's session exists only once
 * the coordinator started it, so any session may answer with a tool registered through {@link #expectFromAnySession}.
 * Only what OpenCode sends is served: JSON-RPC requests over POST, each answered with JSON, never an event stream.
 */
public final class ReviewAnswers implements AutoCloseable {
    /** the server's name in OpenCode's configuration, which OpenCode puts in front of every tool name it offers */
    public static final String SERVER = "codiqo";
    public static final String PATH = "/mcp";
    /** the tool the coordinator reads its reviewers' reviews with: a subagent's reply text is only the end of its turn */
    public static final String REVIEWS_TOOL = "reviewer_reports";
    /** the argument of {@link #REVIEWS_TOOL} that asks for the reviewers' unit labels instead of their findings */
    public static final String LABELS_ARGUMENT = "unitLabels";

    private static final String SESSION_META = "ai.opencode/sessionID";
    private static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final String JSONRPC_VERSION = "2.0";
    private static final int PARSE_ERROR = -32700;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final String READ_FAILED = "Your answer could not be read because of an error on Codiqo's side, so the review fails. End your turn.";

    private final LoopbackServer loopback;
    private final int attempts;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Slot<?>> slots = Maps.newConcurrentMap();
    private final Map<AnswerTool, AnswerReader<?>> anySession = Maps.newConcurrentMap();
    /** the reviews accepted so far, each with the session that gave it, in the order they arrived */
    private final Queue<Pair<String, JsonNode>> reviews = Queues.newConcurrentLinkedQueue();
    /** how many reviews each session had read when it last called {@link #REVIEWS_TOOL} for the findings */
    private final Map<String, Integer> reviewsRead = Maps.newConcurrentMap();
    /**
     * Set when a call named no session: the OpenCode in use does not say who calls, so no answer can be matched to its
     * session and every review would be paid for in full and then fail on a missing answer.
     */
    private final AtomicReference<String> unmatchable = new AtomicReference<>();
    private final Log log;

    private ReviewAnswers(LoopbackServer loopback, RunArgs args, Log log) {
        this.loopback = Objects.requireNonNull(loopback);
        this.attempts = Math.max(1, args.getReviewAnswerAttempts());
        this.log = Objects.requireNonNull(log);
    }
    public Endpoint endpoint() {
        return new Endpoint(RunArgs.loopbackUrl(loopback.getPort()).encodedPath(PATH).build().toString(), loopback.getSecret());
    }
    /** asks the session for its answer through the tool, read by the reader as each call arrives */
    public <T> void expect(String sessionId, AnswerTool tool, AnswerReader<T> reader) {
        slots.put(sessionId, new Slot<>(tool, reader));
    }
    /** takes the tool's answer from any session asked for nothing else, each read by the reader as its calls arrive */
    public <T> void expectFromAnySession(AnswerTool tool, AnswerReader<T> reader) {
        anySession.put(tool, reader);
    }
    /**
     * The answer the session gave, as its reader read it, with the arguments it was read from; empty when no call of
     * the session was accepted. Fails, as Codiqo's failure rather than the agent's, when the calls could not be matched
     * to their sessions or a reader failed on something other than the answer.
     */
    public <T> Optional<Accepted<T>> accepted(String sessionId, Class<T> type) {
        Optional.ofNullable(unmatchable.get()).ifPresent(failure -> {
            throw new IllegalStateException(failure);
        });
        Slot<?> slot = slots.get(sessionId);
        if (Objects.isNull(slot)) {
            return Optional.empty();
        }
        return slot.accepted().map(answer -> new Accepted<>(type.cast(answer.getParsed()), answer.getArguments()));
    }
    /** what was wrong with the last call of a session whose answer was refused and never accepted */
    public Optional<String> refusal(String sessionId) {
        return Optional.ofNullable(slots.get(sessionId)).flatMap(Slot::refusal);
    }
    /** what one call of a tool gets back, as {@code tools/call} answers it once the calling session is known */
    @VisibleForTesting
    public ObjectNode answerCall(String sessionId, String toolName, JsonNode arguments) {
        if (REVIEWS_TOOL.equals(toolName)) {
            return reviewsFor(sessionId, arguments.path(LABELS_ARGUMENT).asBoolean(false));
        }

        AnswerTool tool = AnswerTool.byToolName(toolName).orElseThrow(() -> new IllegalArgumentException("unknown tool " + toolName));
        Slot<?> slot = slots.get(sessionId);
        if (BooleanUtils.and(new boolean[] { Objects.isNull(slot), anySession.containsKey(tool) })) {
            slot = slots.computeIfAbsent(sessionId, created -> new Slot<>(tool, anySession.get(tool)));
        }
        if (Objects.isNull(slot)) {
            return toolResult("Refused: nobody asked this session for " + tool.getOpenCodeName() + "; carry on with what you were asked.", true);
        }
        return slot.submit(sessionId, tool, arguments);
    }
    @Override
    public void close() {
        loopback.close();
    }
    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (loopback.isAuthorized(exchange)) {
                if (HttpMethod.POST.name().equals(exchange.getRequestMethod())) {
                    if (PATH.equals(exchange.getRequestURI().getRawPath())) {
                        answer(exchange);
                    } else {
                        respond(exchange, HttpResponseStatus.NOT_FOUND.code(), null);
                    }
                } else {
                    /** the event stream a GET would open carries nothing here, and the MCP transport answers it with 405 */
                    exchange.getResponseHeaders().set(HttpHeaderNames.ALLOW.toString(), HttpMethod.POST.name());
                    respond(exchange, HttpResponseStatus.METHOD_NOT_ALLOWED.code(), null);
                }
            } else {
                respond(exchange, HttpResponseStatus.UNAUTHORIZED.code(), null);
            }
        }
    }
    private void answer(HttpExchange exchange) throws IOException {
        JsonNode request;
        try {
            request = mapper.readTree(exchange.getRequestBody());
        } catch (JacksonException err) {
            respond(exchange, HttpResponseStatus.BAD_REQUEST.code(), error(mapper.nullNode(), PARSE_ERROR, "not a JSON-RPC message: " + err.getOriginalMessage()));
            return;
        }
        if (request.has("id")) {
            respond(exchange, HttpResponseStatus.OK.code(), response(request));
        } else {
            /** a notification ("notifications/initialized") or a response, neither of which is answered */
            respond(exchange, HttpResponseStatus.ACCEPTED.code(), null);
        }
    }
    private ObjectNode response(JsonNode request) {
        JsonNode id = request.get("id");
        JsonNode params = request.path("params");
        String method = request.path("method").asString();
        return switch (method) {
            case "initialize" -> result(id, initialized(params));
            case "ping" -> result(id, mapper.createObjectNode());
            case "tools/list" -> result(id, tools());
            case "tools/call" -> call(id, params);
            default -> error(id, METHOD_NOT_FOUND, "unknown method " + method);
        };
    }
    private ObjectNode initialized(JsonNode params) {
        ObjectNode toReturn = mapper.createObjectNode();
        toReturn.put("protocolVersion", StringUtils.defaultIfBlank(params.path("protocolVersion").asString(), DEFAULT_PROTOCOL));
        toReturn.putObject("capabilities").putObject("tools").put("listChanged", false);
        toReturn.putObject("serverInfo").put("name", SERVER).put("version", "1");
        return toReturn;
    }
    private ObjectNode tools() {
        ObjectNode toReturn = mapper.createObjectNode();
        ArrayNode tools = toReturn.putArray("tools");
        for (AnswerTool tool : AnswerTool.values()) {
            ObjectNode listed = tools.addObject().put("name", tool.getToolName()).put("description", tool.getDescription());
            listed.set("inputSchema", tool.inputSchema());
        }
        ObjectNode reports = tools.addObject()
                .put("name", REVIEWS_TOOL)
                .put("description", "Returns every review your subagents submitted, each with its subagent's sessionID, in the order they arrived: their findings, "
                        + "observations and notChecked, which are here only. With unitLabels true it returns only their unitLabels instead.");
        ObjectNode schema = reports.putObject("inputSchema").put("type", "object");
        schema.putObject("properties").putObject(LABELS_ARGUMENT).put("type", "boolean");
        return toReturn;
    }
    private ObjectNode call(JsonNode id, JsonNode params) {
        String name = params.path("name").asString();
        String sessionId = params.path("_meta").path(SESSION_META).asString();
        if (StringUtils.isBlank(sessionId)) {
            String failure = "OpenCode named no calling session in _meta[" + SESSION_META + "] of a call to " + name + ", so no answer can be matched to its session";
            unmatchable.compareAndSet(null, failure);
            log.error("review answers: %s", failure);
            return error(id, INVALID_PARAMS, failure);
        }
        if (BooleanUtils.or(new boolean[] { REVIEWS_TOOL.equals(name), AnswerTool.byToolName(name).isPresent() })) {
            return result(id, answerCall(sessionId, name, params.path("arguments")));
        }
        return error(id, INVALID_PARAMS, "unknown tool " + name);
    }
    /**
     * The reviews for the coordinator, each with the session that gave it, so it can tell which area a review covers.
     * A unit label is the labelling turn's starting point and never the coordinator's, so labels come only when asked
     * for: OpenCode cuts a tool's output at 51,200 bytes by default, and the five reviews of one production commit were
     * 36,000 characters, 21,000 of them labels.
     */
    private ObjectNode reviewsFor(String sessionId, boolean labels) {
        ArrayNode submitted = mapper.createArrayNode();
        for (Pair<String, JsonNode> review : reviews) {
            ObjectNode entry = submitted.addObject().put("sessionID", review.getLeft());
            if (labels) {
                entry.set(LABELS_ARGUMENT, review.getRight().path(LABELS_ARGUMENT));
            } else {
                entry.setAll((ObjectNode) review.getRight());
                entry.remove(LABELS_ARGUMENT);
            }
        }
        if (labels) {
            return toolResult(submitted.toString(), false);
        }
        reviewsRead.put(sessionId, submitted.size());
        return toolResult(submitted.toString(), false);
    }
    private ObjectNode toolResult(String text, boolean isError) {
        ObjectNode toReturn = mapper.createObjectNode();
        toReturn.putArray("content").addObject().put("type", "text").put("text", text);
        toReturn.put("isError", isError);
        return toReturn;
    }
    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode toReturn = mapper.createObjectNode().put("jsonrpc", JSONRPC_VERSION);
        toReturn.set("id", id);
        toReturn.set("result", result);
        return toReturn;
    }
    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode toReturn = mapper.createObjectNode().put("jsonrpc", JSONRPC_VERSION);
        toReturn.set("id", id);
        toReturn.putObject("error").put("code", code).put("message", message);
        return toReturn;
    }
    private void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
        if (Objects.isNull(body)) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    /** the name an agent calls a tool of this server by: OpenCode puts the server's name in front of every tool name it offers */
    public static String openCodeName(String toolName) {
        return SERVER + "_" + toolName;
    }
    public static ReviewAnswers start(RunArgs args, Log log) throws IOException {
        LoopbackServer loopback = LoopbackServer.create();
        ReviewAnswers toReturn = new ReviewAnswers(loopback, args, log);
        loopback.serve(PATH, toReturn::serve);
        return toReturn;
    }
    /**
     * What a model reads to correct its answer: Jackson's message with the path to the value it is about, written the
     * way the answer's own fields are ({@code unitLabels[2].category}), never the Java classes the path runs through.
     */
    private static String describe(JacksonException err) {
        String path = err.getPath().stream()
                .map(step -> Objects.nonNull(step.getPropertyName()) ? "." + step.getPropertyName() : "[" + step.getIndex() + "]")
                .collect(Collectors.joining());
        if (StringUtils.isEmpty(path)) {
            return err.getOriginalMessage();
        }
        return StringUtils.removeStart(path, ".") + ": " + err.getOriginalMessage();
    }

    /** reads a call's arguments into an answer, or names what keeps them from being one */
    @FunctionalInterface
    public interface AnswerReader<T> {
        /** @throws JacksonException or {@link RefusedAnswerException} when the arguments are not an answer the review can take */
        T read(JsonNode arguments);
    }

    /** an answer a reader cannot take, with what is wrong with it in words the agent can act on */
    public static final class RefusedAnswerException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RefusedAnswerException(String problems) {
            super(problems);
        }
    }

    /** where the agents reach the answers: the URL OpenCode is configured with and the secret it presents */
    public static final class Endpoint extends ImmutablePair<String, String> {
        public Endpoint(String url, String secret) {
            super(url, secret);
        }
        public String getUrl() {
            return getLeft();
        }
        public String getSecret() {
            return getRight();
        }
    }

    /** an accepted answer as its reader read it, and the arguments, as JSON text, it was read from */
    public static final class Accepted<T> extends ImmutablePair<T, String> {
        public Accepted(T parsed, String arguments) {
            super(parsed, arguments);
        }
        public T getParsed() {
            return getLeft();
        }
        public String getArguments() {
            return getRight();
        }
    }

    /** the answer one session was asked for, and what became of its calls */
    private final class Slot<T> {
        private final AnswerTool tool;
        private final AnswerReader<T> reader;
        private Accepted<T> accepted;
        private int refused;
        private String lastRefusal;
        private RuntimeException failure;

        private Slot(AnswerTool tool, AnswerReader<T> reader) {
            this.tool = Objects.requireNonNull(tool);
            this.reader = Objects.requireNonNull(reader);
        }
        /**
         * The first accepted call is the answer. The subagent tool can continue a reviewer that already answered, and the
         * coordinator did so with no-op prompts ("reply with an empty report") in 6 of 194 production reviews; a later
         * call replacing the first would let such a reply stand for the review.
         *
         * <p>
         * A call to another answer tool than the one asked for, and a coordinator answer given before it read every
         * review, are turned away without counting against the answer's attempts: neither is an answer in the wrong
         * shape. A fork carries the coordinator's prompt, which names the coordinator's tool, and the coordinator is
         * allowed the forks' tools, so either can be called by mistake.
         */
        private synchronized ObjectNode submit(String sessionId, AnswerTool called, JsonNode arguments) {
            if (Objects.nonNull(failure)) {
                return toolResult(READ_FAILED, true);
            }
            if (Objects.nonNull(accepted)) {
                log.warn("review answers: session %s called %s again after its answer was accepted; the first answer stands", sessionId, called.getOpenCodeName());
                return toolResult("Your answer was already accepted and is final; this call changed nothing. End your turn.", false);
            }
            if (refused >= attempts) {
                return toolResult("Your answer was refused " + refused + " times, so no further call is read and the review fails. End your turn.", true);
            }
            if (called != tool) {
                return toolResult("Refused: this turn is answered with " + tool.getOpenCodeName() + ", not " + called.getOpenCodeName() + "; call "
                        + tool.getOpenCodeName() + " with your answer.", true);
            }
            if (BooleanUtils.and(new boolean[] { tool == AnswerTool.ANSWER, reviews.size() > reviewsRead.getOrDefault(sessionId, 0) })) {
                return toolResult("Refused: your subagents submitted reviews you have not read. Call " + openCodeName(REVIEWS_TOOL)
                        + ", merge the findings it returns, then call " + tool.getOpenCodeName() + " again.", true);
            }
            if (arguments.isObject()) {
                return read(sessionId, arguments);
            }
            return refuse(sessionId, "the arguments are not a JSON object");
        }
        private ObjectNode read(String sessionId, JsonNode arguments) {
            try {
                accepted = new Accepted<>(reader.read(arguments), arguments.toString());
            } catch (JacksonException err) {
                return refuse(sessionId, describe(err));
            } catch (RefusedAnswerException err) {
                return refuse(sessionId, err.getMessage());
            } catch (RuntimeException err) {
                /** a reader that failed on its own is no fault of the answer: the review fails with this error, never the agent */
                failure = err;
                log.error("review answers: reading the %s answer of session %s failed: %s", tool.getOpenCodeName(), sessionId, err);
                return toolResult(READ_FAILED, true);
            }
            if (tool == AnswerTool.REVIEW) {
                reviews.add(Pair.of(sessionId, arguments));
            }
            return toolResult("Accepted; your answer is final. End your turn with one short sentence.", false);
        }
        private ObjectNode refuse(String sessionId, String problems) {
            refused++;
            lastRefusal = problems;
            log.warn("review answers: refused the %s answer of session %s (%d of %d): %s", tool.getOpenCodeName(), sessionId, refused, attempts, problems);
            String next = refused < attempts
                    ? " Correct exactly that and call " + tool.getOpenCodeName() + " again with your whole answer."
                    : " That was the last call read: the review fails. End your turn.";
            return toolResult("Refused: " + problems + "." + next, true);
        }
        private synchronized Optional<Accepted<T>> accepted() {
            if (Objects.nonNull(failure)) {
                throw new IllegalStateException("reading the " + tool.getOpenCodeName() + " answer failed", failure);
            }
            return Optional.ofNullable(accepted);
        }
        private synchronized Optional<String> refusal() {
            if (Objects.isNull(accepted)) {
                return Optional.ofNullable(lastRefusal);
            }
            return Optional.empty();
        }
    }
}
