package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.llm.review.ReviewProviderException;
import io.codiqo.submit.auth.ApiKeyCredential;
import io.codiqo.submit.review.LocalReviewException;
import io.codiqo.util.DownloadFailedException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ProjectHaltsTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    /** the chain the language-server download failed with while download.eclipse.org was down, as the halts recorded it */
    @Test
    void aFailedDownloadHaltsNothing() {
        DownloadFailedException download = new DownloadFailedException("failed to download from: https://download.eclipse.org/jdtls/milestones/1.63.0/x",
                new IOException("stream was reset: CANCEL"));

        assertTrue(ProjectHalts.haltsNothing(new IllegalStateException(new UndeclaredThrowableException(download))));
    }
    @Test
    void anAuthorsBrokenBuildHaltsNothing() {
        assertTrue(ProjectHalts.haltsNothing(new IllegalStateException(new CommitBuildFailedException("commit 0123456: the compile failed"))));
    }
    @Test
    void anyOtherFailureHalts() {
        assertFalse(ProjectHalts.haltsNothing(new IllegalStateException(new UndeclaredThrowableException(new IOException("stream was reset: CANCEL")))));
    }
    @Test
    void aHaltedProjectRefusesToRun() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects/p1/commits/missing-analyses", exchange -> {
            ObjectNode answer = JSON.objectNode().put("projectId", "p1");
            answer.putArray("commitShas");
            answer.putObject("halt").put("commitSha", SHA).put("reason", "no coverage").put("haltedAt", "2026-10-14T09:00:00Z");
            respond(exchange, HttpResponseStatus.OK.code(), answer.toString());
        });
        server.start();
        try {
            ProjectHaltedException err = assertThrows(ProjectHaltedException.class, () -> ProjectHalts.requireRunning(new NoopLog(), client(server), url(server), "p1"));

            assertEquals(SHA, err.getHalt().getCommitSha());
            assertTrue(err.getMessage().contains("no coverage"), err.getMessage());
        } finally {
            server.stop(0);
        }
    }
    /** a server older than the halt sends no halt field, which reads as a project that runs */
    @Test
    void aProjectWithoutAHaltRuns() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects/p1/commits/missing-analyses", exchange -> {
            ObjectNode answer = JSON.objectNode().put("projectId", "p1");
            answer.putArray("commitShas").add(SHA);
            respond(exchange, HttpResponseStatus.OK.code(), answer.toString());
        });
        server.start();
        try {
            assertDoesNotThrow(() -> ProjectHalts.requireRunning(new NoopLog(), client(server), url(server), "p1"));
        } finally {
            server.stop(0);
        }
    }
    @Test
    void aFailureIsPostedAsTheHaltWithItsRootCause() throws Exception {
        AtomicReference<JsonNode> posted = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects/p1/halt", exchange -> {
            posted.set(new ObjectMapper().readTree(IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(HttpResponseStatus.NO_CONTENT.code(), -1);
            exchange.close();
        });
        server.start();
        try {
            ProjectHalts.halt(new NoopLog(), client(server), url(server), "p1", SHA, new IllegalStateException("capture failed", new IOException("agent did not attach")), false);

            assertEquals(SHA, posted.get().get("commitSha").asString());
            assertTrue(posted.get().get("reason").asString().contains("agent did not attach"), posted.get().toString());
            assertTrue(posted.get().get("detail").asString().contains("IllegalStateException"), "the stack trace travels as the detail");
        } finally {
            server.stop(0);
        }
    }
    /** a review the provider failed is halted with the provider's answers in the reason, which the halt banner shows */
    @Test
    void aProviderFailureNamesTheProviderInTheReason() throws Exception {
        AtomicReference<JsonNode> posted = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects/p1/halt", exchange -> {
            posted.set(new ObjectMapper().readTree(IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(HttpResponseStatus.NO_CONTENT.code(), -1);
            exchange.close();
        });
        server.start();
        try {
            IOException unanswered = new IOException("session ses_1 finished without an answer");
            ReviewProviderException provider = new ReviewProviderException(unanswered.getMessage(), "the LLM provider answered 429 13 times and 504 5 times during the review", unanswered);
            ProjectHalts.halt(new NoopLog(), client(server), url(server), "p1", SHA, new LocalReviewException("local review failed: " + provider.getMessage(), provider), false);

            String reason = posted.get().get("reason").asString();
            assertTrue(reason.contains("the LLM provider answered 429 13 times"), reason);
            assertTrue(reason.contains("finished without an answer"), reason);

            IOException wordy = new IOException("opencode answered 500: " + "x".repeat(2100));
            ReviewProviderException behindWordy = new ReviewProviderException(wordy.getMessage(), "the LLM provider answered 429 once during the review", wordy);
            ProjectHalts.halt(new NoopLog(), client(server), url(server), "p1", SHA, new LocalReviewException("local review failed", behindWordy), false);

            String shortened = posted.get().get("reason").asString();
            assertTrue(shortened.endsWith("; the LLM provider answered 429 once during the review"), "a long failure is shortened, never the provider's summary");
        } finally {
            server.stop(0);
        }
    }
    /** a halt the server refuses is logged; the caller still fails with its own cause */
    @Test
    void aRefusedHaltDoesNotReplaceTheFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects", exchange -> respond(exchange, HttpResponseStatus.BAD_REQUEST.code(), JSON.objectNode().put("message", "bad").toString()));
        server.start();
        try {
            assertDoesNotThrow(() -> ProjectHalts.halt(new NoopLog(), client(server), url(server), "p1", SHA, new IOException("boom"), false));
        } finally {
            server.stop(0);
        }
    }
    private static CommitIndexApi client(HttpServer server) {
        return CommitIndexPublisher.buildClient(url(server), new ApiKeyCredential("k"), 5, 5);
    }
    private static String url(HttpServer server) {
        return RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    private static final class NoopLog implements Log {
        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public void log(Level level, String message, Object... formatArgs) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
