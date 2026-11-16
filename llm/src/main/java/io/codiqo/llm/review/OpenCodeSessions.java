package io.codiqo.llm.review;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A review's sessions in the headless OpenCode server the review starts ({@link OpenCodeServer}), driven through its HTTP
 * API ({@link OpenCodeClient}). Everything OpenCode's records mean is read here (how a turn ended, whether it ran out of
 * time, which part of a message is a tool's output), so the review itself never reads OpenCode's message format.
 */
public class OpenCodeSessions implements ReviewSessions {
    /** the {@code finish} of an assistant message the model ended on its own, not on a tool call, an error or a cap */
    private static final String STOP = "stop";
    /** a tool's output is mostly file contents and command output the repository already holds; the start shows what it was */
    private static final int MAX_TOOL_OUTPUT_CHARS = 4_000;
    /**
     * The turn errors that follow from what the commit asks, so a review that fails on one is the commit's failure and
     * excludes it: a request the provider refused as invalid (a prompt larger than the model takes) and content its filter
     * refused. Every other turn error is taken for the provider's or Codiqo's and halts the project, a type a later
     * OpenCode version adds included. The two outcomes are not equally costly: a halt is reported and an administrator
     * resumes it, with the commit excluded if it fails again, while an exclusion is silent and leaves the commit unscored
     * until someone deletes its analysis. When the provider failures were the listed ones instead, provider.quota was
     * missing from the list, and an exhausted provider quota excluded 28 commits of one project within 14 minutes.
     */
    private static final Set<String> COMMIT_TURN_ERRORS = Set.of(
            "provider.invalid-request",
            "provider.content-filter");

    private final OpenCodeClient client;
    private final Path workTree;
    private final Duration turnTimeout;
    private final Runnable stopServer;

    /**
     * Sessions of an OpenCode server someone else runs and stops, as a test's fake of its HTTP API is. The turn timeout is
     * the review timeout, and the client's own timeouts may end a wait sooner, as a connect that timed out does.
     */
    @VisibleForTesting
    public OpenCodeSessions(OpenCodeClient client, Path workTree, Duration turnTimeout) {
        this(client, workTree, turnTimeout, () -> {
        });
    }
    private OpenCodeSessions(OpenCodeClient client, Path workTree, Duration turnTimeout, Runnable stopServer) {
        this.client = Objects.requireNonNull(client);
        this.workTree = Objects.requireNonNull(workTree);
        this.turnTimeout = Objects.requireNonNull(turnTimeout);
        this.stopServer = Objects.requireNonNull(stopServer);
    }
    @Override
    public String createSession(String agent, String title) throws IOException {
        return client.createSession(workTree, agent, title);
    }
    @Override
    public String fork(String sessionId) throws IOException {
        return client.fork(sessionId);
    }
    @Override
    public void prompt(String sessionId, String text) throws IOException {
        client.prompt(sessionId, text);
    }
    @Override
    public boolean awaitTurn(String sessionId) throws IOException {
        StopWatch turn = StopWatch.createStarted();
        try {
            client.awaitIdle(sessionId);
            return true;
        } catch (InterruptedIOException err) {
            /**
             * OkHttp ends a call that outlived its call timeout, which is the review timeout, with this exception, and
             * also a connect to OpenCode that timed out after seconds (a SocketTimeoutException) and an interrupted wait.
             * Only the first is a turn that did not end in time, and only it comes after the whole review timeout: the
             * others are Codiqo's failure or a stopped run, settled where the review is.
             */
            if (BooleanUtils.or(new boolean[] { Thread.currentThread().isInterrupted(), turn.getDuration().compareTo(turnTimeout) < 0 })) {
                throw err;
            }
            return false;
        }
    }
    /** A session with no message has run no turn, which reads as cut off. */
    @Override
    public TurnEnd lastTurn(String sessionId) throws IOException {
        List<List<JsonNode>> turns = client.turns(sessionId);
        if (turns.isEmpty()) {
            return TurnEnd.Ending.CUT_OFF;
        }
        return endOf(turns.getLast());
    }
    @Override
    public SessionUsage usage(String sessionId) throws IOException {
        return client.usage(sessionId);
    }
    @Override
    public List<SessionUsage> children(String sessionId) throws IOException {
        return client.childUsage(sessionId);
    }
    @Override
    public ObjectNode trace(String sessionId) throws IOException {
        ObjectNode toReturn = JsonNodeFactory.instance.objectNode();
        toReturn.set("session", client.session(sessionId));
        ArrayNode messages = toReturn.putArray("messages");
        for (JsonNode message : client.messages(sessionId)) {
            abbreviateToolOutput(message);
            messages.add(message);
        }
        return toReturn;
    }
    /**
     * The server is stopped even when closing the client fails, and a failure to stop it is kept with the client's, as
     * nested try-with-resources would keep it.
     */
    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException err) {
            closeAfter(err, stopServer);
            throw err;
        }
        stopServer.run();
    }
    /**
     * How a turn ended, read from its last assistant message: an error on it is the turn's error, and a {@code finish} of
     * "stop" is a turn the model ended on its own; anything else, a tool call or a step cap, is a turn cut off.
     */
    @VisibleForTesting
    public static TurnEnd endOf(List<JsonNode> turn) {
        Optional<JsonNode> last = Lists.reverse(turn).stream()
                .filter(message -> OpenCodeClient.ASSISTANT.equals(message.path("type").asString()))
                .findFirst();
        Optional<JsonNode> error = last.map(message -> message.path("error")).filter(JsonNode::isObject);
        if (error.isPresent()) {
            String type = error.get().path("type").asString();
            return new TurnEnd.Failed(type, error.get().path("message").asString(), COMMIT_TURN_ERRORS.contains(type));
        }
        if (last.filter(message -> STOP.equals(message.path("finish").asString())).isPresent()) {
            return TurnEnd.Ending.STOPPED_ON_ITS_OWN;
        }
        return TurnEnd.Ending.CUT_OFF;
    }
    /**
     * Starts OpenCode for one review of the work tree and waits until it has connected to the answer server: without it
     * no answer can be given, and every agent would run to its step cap for nothing. Whatever fails before the sessions
     * are handed over stops the server, whose home holds the configuration with the relay's secret; a failure to stop it
     * is kept with the failure that stopped it.
     */
    public static OpenCodeSessions start(RunArgs args, ObjectNode configuration, Path workTree, Log log) throws IOException {
        OpenCodeServer server = OpenCodeServer.start(args, configuration, workTree, log);
        try {
            OpenCodeClient client = new OpenCodeClient(server.getUrl(), server.getPassword(), args.getReviewTimeout());
            try {
                client.awaitMcpConnected(ReviewAnswers.SERVER, args.getReviewStartupTimeout());
                return new OpenCodeSessions(client, workTree, args.getReviewTimeout(), server::close);
            } catch (Throwable err) {
                closeAfter(err, client::close);
                throw err;
            }
        } catch (Throwable err) {
            closeAfter(err, server::close);
            throw err;
        }
    }
    private static void closeAfter(Throwable failure, Runnable close) {
        try {
            close.run();
        } catch (RuntimeException err) {
            failure.addSuppressed(err);
        }
    }
    private static void abbreviateToolOutput(JsonNode message) {
        for (JsonNode part : message.path("content")) {
            if ("tool".equals(part.path("type").asString())) {
                abbreviate(part.path("state").path("content"));
            }
        }
    }
    private static void abbreviate(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().isString()) {
                    object.put(field.getKey(), StringUtils.abbreviate(field.getValue().asString(), MAX_TOOL_OUTPUT_CHARS));
                } else {
                    abbreviate(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(OpenCodeSessions::abbreviate);
        }
    }
}
