package io.codiqo.submit.hotspots;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.submit.auth.ApiKeyCredential;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class FixCommitsTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @TempDir
    Path dir;

    /** a scored commit takes its analysis's verdict, whatever its message says; an unscored one keeps its message */
    @Test
    void theServersVerdictWinsForScoredCommits() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            RevCommit fixWording = commit(git, "Fix the test fixture");
            RevCommit plainWording = commit(git, "PAY-1: add error handling for declined cards");
            RevCommit unscored = commit(git, "fix the rounding bug");

            FixCommits fixes = FixCommits.of(Set.of(fixWording.getName(), plainWording.getName()), Set.of(plainWording.getName()));

            assertFalse(fixes.isFix(fixWording), "scored, and not classified as a fix");
            assertTrue(fixes.isFix(plainWording), "scored as a fix although its message never says so");
            assertTrue(fixes.isFix(unscored), "never scored, so its message decides");
            assertTrue(FixCommits.byMessage().isFix(fixWording));
            assertFalse(FixCommits.byMessage().isFix(plainWording));
        }
    }
    /** the server reads the since instant from a plain ISO-8601 string; a date-time parameter could not be read at all */
    @Test
    void theSinceInstantTravelsAsAnIsoString() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            RevCommit scored = commit(git, "fix the test fixture");
            server.createContext("/api/v1/projects/p1/commits/fix-commits", exchange -> {
                query.set(exchange.getRequestURI().getRawQuery());
                ObjectNode answer = JSON.objectNode().put("projectId", "p1");
                answer.putArray("scoredShas").add(scored.getName());
                answer.putArray("fixShas");
                respond(exchange, 200, answer.toString());
            });

            FixCommits fixes = FixCommits.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", Instant.parse("2026-01-01T00:00:00Z"), new NoopLog());

            assertTrue(Strings.CS.equalsAny(query.get(), "since=2026-01-01T00%3A00%3A00Z", "since=2026-01-01T00:00:00Z"), query.get());
            assertFalse(fixes.isFix(scored), "the server's verdict was read");
        } finally {
            server.stop(0);
        }
    }
    /** an older server matches the path to its commit route and rejects "fix-commits" as a SHA: the messages decide */
    @Test
    void anOlderServerLeavesTheMessagesToDecide() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects/p1/commits", exchange -> respond(exchange, 400, JSON.objectNode().put("message", "invalid commit sha").toString()));
        server.start();
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            RevCommit fix = commit(git, "fix the rounding bug");

            FixCommits fixes = FixCommits.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", Instant.parse("2026-01-01T00:00:00Z"), new NoopLog());

            assertTrue(fixes.isFix(fix));
        } finally {
            server.stop(0);
        }
    }
    private RevCommit commit(Git git, String message) throws Exception {
        Files.writeString(dir.resolve("Foo.java"), message, StandardCharsets.UTF_8);
        git.add().addFilepattern("Foo.java").call();
        return git.commit().setMessage(message).setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
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
