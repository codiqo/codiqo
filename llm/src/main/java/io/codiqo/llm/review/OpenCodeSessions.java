package io.codiqo.llm.review;

import static java.util.function.Predicate.not;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

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

    /** added to every bound, so a call never times out before the review timeout does */
    private static final long DEADLINE_MARGIN_MILLIS = 2;

    private final OpenCodeClient client;
    private final Path workTree;
    private final Duration turnTimeout;
    private final Runnable stopServer;
    private final Log log;
    /** the client a look at the running sub-agents goes through when an answer asks, bounded as one look is */
    private final OpenCodeClient reviewersCheck;

    /**
     * Sessions of an OpenCode server someone else runs and stops, as a test's fake of its HTTP API is. The turn timeout is
     * the review timeout, and the client's own timeouts may end a wait sooner, as a connect that timed out does.
     */
    @VisibleForTesting
    public OpenCodeSessions(OpenCodeClient client, Path workTree, Duration turnTimeout, Log log) {
        this(client, workTree, turnTimeout, () -> {
        }, log);
    }
    private OpenCodeSessions(OpenCodeClient client, Path workTree, Duration turnTimeout, Runnable stopServer, Log log) {
        this.client = Objects.requireNonNull(client);
        this.workTree = Objects.requireNonNull(workTree);
        this.turnTimeout = Objects.requireNonNull(turnTimeout);
        this.stopServer = Objects.requireNonNull(stopServer);
        this.log = Objects.requireNonNull(log);
        this.reviewersCheck = client.within(RunArgs.REVIEW_LOOK_TIMEOUT);
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
    /**
     * Waits until the session and the sub-agents it started are quiet. OpenCode 2.3 runs every sub-agent in the
     * background: a coordinator's run ends once it started its reviewers, and each reviewer's report, put in its inbox,
     * wakes it for a new run, while OpenCode's wait covers one session's own run and no route waits for a session's tree.
     * A reviewer stays among the running sessions until its report is in the coordinator's inbox and the coordinator woken,
     * which makes it running in turn, so the turn ended once no session of the tree is running: looked at twice a moment
     * apart with the session unchanged in between, which also covers OpenCode 2.2, where a report reached the inbox just
     * after its sub-agent stopped running. Once the session answered, the first look that finds nothing running ends it.
     * A session of the tree that waits for a shell command running in the background keeps an unanswered turn open as a
     * running one does: OpenCode 2.3.2 moves a command still running at its timeout there, the agent ends its response,
     * and the command's result wakes it again, a reviewer that has not answered yet included. An answered turn does not
     * wait for such a command: the answer was taken only once every sub-agent still at work, one waiting for a command
     * included, had submitted its review (the answer's hold), so a command left then belongs to an agent that is done,
     * and a server or a hung command would hold the answered review to its timeout and fail it. Every call is bounded by
     * what is left of the review timeout, and a look that failed is tried again a few times before the wait fails. Up to
     * OpenCode 2.2 sub-agents ran in the foreground, inside the coordinator's run, and the first wait already covers them.
     */
    @Override
    public boolean awaitTurn(String sessionId, BooleanSupplier answered) throws IOException {
        StopWatch turn = StopWatch.createStarted();
        try {
            Optional<String> quiet = Optional.empty();
            int failedLooks = 0;
            while (turn.getDuration().compareTo(turnTimeout) < 0) {
                try {
                    bounded(turn).awaitIdle(sessionId);
                    /** a quick listing, bounded as one look is, so one OpenCode never answers fails within seconds and is tried again */
                    OpenCodeClient look = client.within(ObjectUtils.min(remaining(turn), RunArgs.REVIEW_LOOK_TIMEOUT));
                    /**
                     * The shell jobs are read before the running sessions: OpenCode wakes a session with its command's
                     * result before it drops the command's job, so a session found in neither look has no command left
                     * that would make it run again. Read the other way round, a command that ended between the two
                     * looks would leave its session in neither, and the turn would end before that session's next run.
                     */
                    boolean waitingForShells = CollectionUtils.isNotEmpty(waitedForShells(look, sessionId));
                    Set<String> running = running(look, sessionId, true);
                    if (CollectionUtils.isNotEmpty(running)) {
                        quiet = Optional.empty();
                        for (String session : running) {
                            bounded(turn).awaitIdle(session);
                        }
                    } else if (answered.getAsBoolean()) {
                        return true;
                    } else if (waitingForShells) {
                        /** no session runs to wait on: the one a command's result wakes stays idle until then */
                        quiet = Optional.empty();
                        OpenCodeClient.sleep(RunArgs.REVIEW_SHELL_POLL);
                    } else {
                        String state = look.session(sessionId).toString();
                        if (quiet.filter(state::equals).isPresent()) {
                            return true;
                        }
                        quiet = Optional.of(state);
                        OpenCodeClient.sleep(RunArgs.REVIEW_POLL);
                    }
                    failedLooks = 0;
                } catch (InterruptedIOException err) {
                    /** a connect that timed out is a failed look; an interrupt or the review timeout ends the wait */
                    if (BooleanUtils.or(new boolean[] { Thread.currentThread().isInterrupted(), turn.getDuration().compareTo(turnTimeout) >= 0, ++failedLooks >= RunArgs.REVIEW_LOOK_ATTEMPTS })) {
                        throw err;
                    }
                    OpenCodeClient.sleep(RunArgs.REVIEW_POLL);
                } catch (IOException err) {
                    if (++failedLooks >= RunArgs.REVIEW_LOOK_ATTEMPTS) {
                        throw err;
                    }
                    log.warn("opencode: looking at the review's sessions failed (%d of %d), looking again: %s", failedLooks, RunArgs.REVIEW_LOOK_ATTEMPTS, err.getMessage());
                    OpenCodeClient.sleep(RunArgs.REVIEW_POLL);
                }
            }
            return false;
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
    /**
     * The sub-agents still running, as OpenCode lists its running sessions: from OpenCode 2.3 a reviewer stays running
     * until its report is in the coordinator's inbox. One that waits for a shell command running in the background counts
     * too, since the command's result wakes it to go on with its review. A look that failed is tried again, as the wait's
     * are.
     */
    @Override
    public Set<String> runningSubagents(String sessionId) throws IOException {
        for (int attempt = 1;; attempt++) {
            try {
                /**
                 * The shell jobs are read before the running sessions, as in the turn's wait: OpenCode wakes a sub-agent
                 * with its command's result before it drops the command's job, so one found in neither look has no
                 * command left that would make it run again, and an answer is not held for it.
                 */
                Set<String> toReturn = waitedForShells(reviewersCheck, sessionId);
                toReturn.remove(sessionId);
                toReturn.addAll(running(reviewersCheck, sessionId, false));
                return toReturn;
            } catch (IOException err) {
                if (BooleanUtils.or(new boolean[] { Thread.currentThread().isInterrupted(), attempt >= RunArgs.REVIEW_LOOK_ATTEMPTS })) {
                    throw err;
                }
                OpenCodeClient.sleep(RunArgs.REVIEW_POLL);
            }
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
     * The sessions of the tree waiting for a shell command OpenCode runs in the background, after stopping each command
     * that has run longer than {@link RunArgs#REVIEW_SHELL_LIMIT}. A stopped command's session is still returned:
     * OpenCode lists its job until the error the stop ended it with has woken that session, and a look taken after that
     * finds the session running.
     */
    private Set<String> waitedForShells(OpenCodeClient client, String sessionId) throws IOException {
        Instant overdue = Instant.now().minus(RunArgs.REVIEW_SHELL_LIMIT);
        Set<String> toReturn = Sets.newHashSet();
        for (OpenCodeClient.ShellJob job : client.backgroundShells(sessionId)) {
            if (job.getStarted().isBefore(overdue)) {
                log.warn("opencode: stopping shell command %s of session %s, still running %s after it started", job.getShellId(), job.getSessionId(), RunArgs.REVIEW_SHELL_LIMIT);
                client.stopShell(job.getShellId());
            }
            toReturn.add(job.getSessionId());
        }
        return toReturn;
    }
    /**
     * The client bounded by what is left of the review timeout, rounded up: OkHttp keeps a timeout in whole milliseconds
     * and starts it with the call, so one rounded down fired just before the deadline, and the wait, finding time left,
     * threw it instead of reporting a turn out of time (1 run in 145 of the test that waits for a reviewer that never
     * ends). It is never nothing, which OkHttp reads as no timeout at all.
     */
    private OpenCodeClient bounded(StopWatch turn) {
        return client.within(remaining(turn));
    }
    private Duration remaining(StopWatch turn) {
        return ObjectUtils.max(turnTimeout.minus(turn.getDuration()), Duration.ZERO).truncatedTo(ChronoUnit.MILLIS).plusMillis(DEADLINE_MARGIN_MILLIS);
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
                client.within(RunArgs.REVIEW_LOOK_TIMEOUT).requireBackgroundRoutes();
                return new OpenCodeSessions(client, workTree, args.getReviewTimeout(), server::close, log);
            } catch (Throwable err) {
                closeAfter(err, client::close);
                throw err;
            }
        } catch (Throwable err) {
            closeAfter(err, server::close);
            throw err;
        }
    }
    /**
     * The session's sub-agents running right now, with the session itself when {@code itself}. The sub-agents are listed
     * only when some other session runs: a hold looks once a second for as long as a reviewer works.
     */
    private static Set<String> running(OpenCodeClient client, String sessionId, boolean itself) throws IOException {
        Set<String> active = client.activeSessions();
        Set<String> toReturn = Sets.newHashSet();
        if (BooleanUtils.and(new boolean[] { itself, active.contains(sessionId) })) {
            toReturn.add(sessionId);
        }
        if (active.stream().anyMatch(not(sessionId::equals))) {
            client.childUsage(sessionId).stream().map(SessionUsage::getSessionId).filter(active::contains).forEach(toReturn::add);
        }
        return toReturn;
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
