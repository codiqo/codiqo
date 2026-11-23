package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A coordinator's turn as OpenCode 2.3 runs it, every sub-agent in the background: the coordinator's own run ends once it
 * started its reviewer, which keeps running until its report is in the coordinator's inbox and the coordinator woken for
 * a second run, and only once that run ended is the turn over. The fake plays that sequence through the route listing
 * the running sessions, one step for each wait the review makes.
 */
class BackgroundSubagentWaitTest {
    private static final String PASSWORD = "test-password";
    private static final String COORDINATOR = "ses_coordinator";
    private static final String REVIEWER = "ses_reviewer";
    private static final String FINISHED_REVIEWER = "ses_finished";
    private static final String OTHER_SESSION = "ses_other";
    private static final String SHELL = "sh_reviewer";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHORT = Duration.ofMillis(300);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 0: the reviewer runs; 1: its report woke the coordinator for a second run; 2: that run is over and the review quiet */
    private final AtomicInteger phase = new AtomicInteger();
    private final List<String> waits = Lists.newCopyOnWriteArrayList();
    /** released when the test ends: a reviewer that never ends holds its wait until then */
    private final CountDownLatch released = new CountDownLatch(1);
    /** how many of the next looks at the running sessions fail with a 500 */
    private final AtomicInteger failingLooks = new AtomicInteger();
    private boolean reviewerNeverEnds;
    /** an OpenCode older than the route that lists the running sessions */
    private volatile boolean withoutActiveRoute;
    /** the route answers, but without the data object that lists the running sessions */
    private volatile boolean activeWithoutData;
    /** how many more looks at the background jobs list a shell command of {@link #shellSessions} */
    private final AtomicInteger shellLooks = new AtomicInteger();
    private volatile List<String> shellSessions = List.of();
    /** when the listed shell commands started */
    private volatile Instant shellStarted = Instant.now();
    private volatile String jobQuery = StringUtils.EMPTY;
    /** the shell commands the review stopped, each by its own route */
    private final List<String> stoppedShells = Lists.newCopyOnWriteArrayList();
    /** an OpenCode older than the job listing, which came in 2.2.6 */
    private volatile boolean withoutJobRoute;
    /** the job listing answers, but without its data array */
    private volatile boolean jobsWithoutData;
    private ExecutorService executor;
    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        url = RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
    }
    @AfterEach
    void stop() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
    @Test
    void theTurnSpansEveryBackgroundReviewerAndTheRunItsReportWokeTheCoordinatorFor(@TempDir Path directory) throws Exception {
        try (OpenCodeSessions sessions = sessions(directory, TIMEOUT)) {
            assertTrue(sessions.awaitTurn(COORDINATOR, () -> false));
        }

        assertEquals(List.of(COORDINATOR, REVIEWER, COORDINATOR, COORDINATOR), waits,
                "the coordinator's first run, the reviewer's, the run its report woke, then the second quiet look");
    }
    /** once the coordinator answered, the first look that finds nothing running ends the turn */
    @Test
    void anAnsweredTurnEndsAtTheFirstLookWithNothingRunning(@TempDir Path directory) throws Exception {
        phase.set(2);
        try (OpenCodeSessions sessions = sessions(directory, TIMEOUT)) {
            assertTrue(sessions.awaitTurn(COORDINATOR, () -> true));
        }

        assertEquals(List.of(COORDINATOR), waits);
    }
    /**
     * A reviewer that never ends keeps the turn open until the review timeout, which the wait reports, never the call
     * timeout that bounds the reviewer's wait: rounded down to whole milliseconds, that one fired just before the deadline
     * and was thrown in 1 run in 145.
     */
    @RepeatedTest(10)
    void aReviewerThatNeverEndsRunsTheTurnOutOfTime(@TempDir Path directory) throws Exception {
        reviewerNeverEnds = true;
        try (OpenCodeSessions sessions = sessions(directory, SHORT)) {
            assertFalse(sessions.awaitTurn(COORDINATOR, () -> false));
        }
    }
    /** a look OpenCode failed once is taken again; failing every time, it fails the wait */
    @Test
    void aFailedLookIsTriedAgainBeforeTheWaitFails(@TempDir Path directory) throws Exception {
        phase.set(2);
        failingLooks.set(1);
        try (OpenCodeSessions sessions = sessions(directory, TIMEOUT)) {
            assertTrue(sessions.awaitTurn(COORDINATOR, () -> true));

            failingLooks.set(Integer.MAX_VALUE);
            IOException err = assertThrows(IOException.class, () -> sessions.awaitTurn(COORDINATOR, () -> true));
            assertTrue(err.getMessage().contains("500"), err.getMessage());
        }
    }
    /**
     * A reviewer whose shell command OpenCode 2.3.2 moved to the background ends its response and is listed among no
     * running sessions until the command's result wakes it. The turn stays open until then and spans the run that result
     * woke the reviewer for, where a wait that read only the running sessions ended the turn at its first quiet look and
     * lost the reviewer's review.
     */
    @Test
    void aShellRunningInTheBackgroundHoldsTheTurnUntilItsResultWokeTheReviewer(@TempDir Path directory) throws Exception {
        phase.set(2);
        shellSessions = List.of(REVIEWER);
        shellLooks.set(2);
        try (OpenCodeSessions sessions = sessions(directory, TIMEOUT)) {
            assertTrue(sessions.awaitTurn(COORDINATOR, () -> false));
        }

        assertEquals(List.of(COORDINATOR, COORDINATOR, REVIEWER, COORDINATOR, COORDINATOR), waits,
                "two looks at the shell, the reviewer's run its result woke, the coordinator's run its report woke, then the second quiet look");
        assertEquals(0, shellLooks.get());
        for (String parameter : List.of("sessionID=" + COORDINATOR, "kind=shell", "descendants=true")) {
            assertTrue(Strings.CS.contains(jobQuery, parameter), jobQuery);
        }
        assertEquals(List.of(), stoppedShells, "a command within the limit runs on");
    }
    /**
     * An answered turn ends at once though a command still runs in the background: the answer was taken only once every
     * sub-agent still at work had submitted, and a server left running would hold the review to its timeout.
     */
    @Test
    void anAnsweredTurnDoesNotWaitForACommandLeftInTheBackground(@TempDir Path directory) throws Exception {
        phase.set(2);
        shellSessions = List.of(REVIEWER);
        shellLooks.set(Integer.MAX_VALUE);
        try (OpenCodeSessions sessions = sessions(directory, TIMEOUT)) {
            assertTrue(sessions.awaitTurn(COORDINATOR, () -> true));
        }

        assertEquals(List.of(COORDINATOR), waits);
    }
    /**
     * A command running longer than the limit is stopped, which ends it with an error that wakes its reviewer, and the
     * turn then spans that reviewer's run: OpenCode 2.3.2 never stops a command it moved to the background itself.
     */
    @Test
    void aCommandRunningPastTheLimitIsStoppedAndItsReviewerWaitedFor(@TempDir Path directory) throws Exception {
        phase.set(2);
        shellSessions = List.of(REVIEWER);
        shellLooks.set(Integer.MAX_VALUE);
        shellStarted = Instant.now().minus(RunArgs.REVIEW_SHELL_LIMIT).minusSeconds(1);
        try (OpenCodeSessions sessions = sessions(directory, TIMEOUT)) {
            assertTrue(sessions.awaitTurn(COORDINATOR, () -> false));
        }

        assertEquals(List.of(SHELL), stoppedShells);
        assertEquals(List.of(COORDINATOR, COORDINATOR, REVIEWER, COORDINATOR, COORDINATOR), waits,
                "the look that stopped the command, the reviewer's run the stop woke, the coordinator's run its report woke, then the second quiet look");
    }
    /** the session's own sub-agents still running are listed, never one that finished, the session itself or another's */
    @Test
    void theRunningSubagentsAreTheSessionsOwnStillRunning(@TempDir Path directory) throws Exception {
        try (OpenCodeSessions sessions = sessions(directory, TIMEOUT)) {
            assertEquals(Set.of(REVIEWER), sessions.runningSubagents(COORDINATOR));
        }
    }
    /** a sub-agent waiting for a shell command in the background is still at work; the session's own command is no sub-agent */
    @Test
    void aSubagentWaitingForABackgroundShellIsStillRunning(@TempDir Path directory) throws Exception {
        phase.set(2);
        shellSessions = List.of(COORDINATOR, REVIEWER);
        shellLooks.set(Integer.MAX_VALUE);
        try (OpenCodeSessions sessions = sessions(directory, TIMEOUT)) {
            assertEquals(Set.of(REVIEWER), sessions.runningSubagents(COORDINATOR));
        }
    }
    /** an OpenCode without the route is refused before any agent is paid for, saying which version serves it */
    @Test
    void anOpenCodeWithoutTheRouteIsRefusedNamingTheVersion() {
        withoutActiveRoute = true;
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            IOException err = assertThrows(IOException.class, client::requireBackgroundRoutes);

            assertTrue(err.getMessage().contains("GET /api/session/active"), err.getMessage());
            assertTrue(err.getMessage().contains("2.2.7"), err.getMessage());
        }
    }
    /** a listing without its data object is refused as well: read as nothing running, it would end every turn at once */
    @Test
    void aListingWithoutItsDataIsRefused() {
        activeWithoutData = true;
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            IOException err = assertThrows(IOException.class, client::requireBackgroundRoutes);
            assertTrue(err.getMessage().contains("without its data object"), err.getMessage());
        }
    }
    /** an OpenCode that lists its running sessions but not its background jobs (2.1 to 2.2.5) is refused as well */
    @Test
    void anOpenCodeWithoutTheJobListingIsRefusedNamingTheVersion() {
        withoutJobRoute = true;
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            IOException err = assertThrows(IOException.class, client::requireBackgroundRoutes);

            assertTrue(err.getMessage().contains("GET /api/experimental/job"), err.getMessage());
            assertTrue(err.getMessage().contains("2.2.7"), err.getMessage());
        }
    }
    /** a job listing without its data array is refused: read as no shell running, it would end a turn a reviewer still owes */
    @Test
    void aJobListingWithoutItsDataIsRefused() {
        jobsWithoutData = true;
        try (OpenCodeClient client = new OpenCodeClient(url, PASSWORD, TIMEOUT)) {
            IOException err = assertThrows(IOException.class, client::requireBackgroundRoutes);
            assertTrue(err.getMessage().contains("without its data array"), err.getMessage());
        }
    }
    private OpenCodeSessions sessions(Path directory, Duration turnTimeout) {
        return new OpenCodeSessions(new OpenCodeClient(url, PASSWORD, TIMEOUT), directory, turnTimeout, NoopLog.INSTANCE);
    }
    private void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        if (route.equals("GET /api/session/active")) {
            if (withoutActiveRoute) {
                respond(exchange, HttpResponseStatus.NOT_FOUND.code(), "{}");
                return;
            }
            if (activeWithoutData) {
                respond(exchange, HttpResponseStatus.OK.code(), "{}");
                return;
            }
            if (failingLooks.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                respond(exchange, HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), "{}");
                return;
            }
        }
        if (route.equals("GET /api/experimental/job")) {
            if (withoutJobRoute) {
                respond(exchange, HttpResponseStatus.NOT_FOUND.code(), "{}");
                return;
            }
            if (jobsWithoutData) {
                respond(exchange, HttpResponseStatus.OK.code(), "{}");
                return;
            }
            jobQuery = StringUtils.defaultString(exchange.getRequestURI().getQuery());
        }
        if (route.equals("DELETE /api/shell/" + SHELL)) {
            stopShell();
            respond(exchange, HttpResponseStatus.NO_CONTENT.code(), StringUtils.EMPTY);
            return;
        }
        respond(exchange, HttpResponseStatus.OK.code(), switch (route) {
            case "POST /api/experimental/session/" + COORDINATOR + "/wait" -> waitFor(COORDINATOR);
            case "POST /api/experimental/session/" + REVIEWER + "/wait" -> waitFor(REVIEWER);
            case "GET /api/session" -> data(children(exchange.getRequestURI().getQuery()));
            case "GET /api/session/active" -> data(active());
            case "GET /api/experimental/job" -> data(shells());
            case "GET /api/session/" + COORDINATOR -> data(JSON.objectNode().put("id", COORDINATOR).put("runs", phase.get() == 2 ? 2 : 1));
            default -> throw new IllegalArgumentException("unexpected route " + route);
        });
    }
    /** a wait returns once that session's run is over: the reviewer's wakes the coordinator, whose second run ends the review */
    private String waitFor(String session) throws IOException {
        waits.add(session);
        if (REVIEWER.equals(session)) {
            if (reviewerNeverEnds) {
                awaitRelease();
            }
            phase.compareAndSet(0, 1);
        } else {
            phase.compareAndSet(1, 2);
        }
        return StringUtils.EMPTY;
    }
    private void awaitRelease() throws IOException {
        try {
            released.await();
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException(err);
        }
    }
    /** the coordinator's reviewers, one of them long finished; nobody else's */
    private static ArrayNode children(String query) {
        ArrayNode toReturn = JSON.arrayNode();
        if (Strings.CS.contains(query, "parentID=" + COORDINATOR)) {
            toReturn.addObject().put("id", REVIEWER).put("agent", OpenCodeReviewConfig.REVIEWER);
            toReturn.addObject().put("id", FINISHED_REVIEWER).put("agent", OpenCodeReviewConfig.REVIEWER);
        }
        return toReturn;
    }
    /** the reviewer runs until its report woke the coordinator, which runs until its second run ended; another session runs throughout */
    private ObjectNode active() {
        ObjectNode toReturn = JSON.objectNode();
        toReturn.putObject(OTHER_SESSION).put("type", "running");
        if (phase.get() == 0) {
            toReturn.putObject(REVIEWER).put("type", "running");
        } else if (phase.get() == 1) {
            toReturn.putObject(COORDINATOR).put("type", "running");
        }
        return toReturn;
    }
    /**
     * The shell jobs, listed while their commands run. OpenCode wakes a session with its command's result before it drops
     * the job, so the reviewer runs again from the last look that still lists the job.
     */
    private ArrayNode shells() {
        ArrayNode toReturn = JSON.arrayNode();
        int left = shellLooks.getAndUpdate(looks -> Math.max(0, looks - 1));
        if (left > 0) {
            for (String session : shellSessions) {
                ObjectNode job = toReturn.addObject().put("kind", "shell").put("sessionID", session).put("shellID", SHELL).put("status", "running");
                job.putObject("time").put("started", shellStarted.toEpochMilli());
            }
        }
        if (left == 1) {
            phase.set(0);
        }
        return toReturn;
    }
    /**
     * A stopped command ends with an error that wakes its reviewer: the job is still listed once more, as OpenCode lists
     * it until that error is in the session, and the reviewer runs.
     */
    private void stopShell() {
        stoppedShells.add(SHELL);
        shellLooks.set(1);
        shellStarted = Instant.now();
    }
    private static String data(Object data) {
        return "{\"data\":" + data + "}";
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
