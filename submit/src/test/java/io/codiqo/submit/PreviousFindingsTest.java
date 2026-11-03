package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.review.PreviousFinding;
import io.codiqo.submit.auth.ApiKeyCredential;
import io.codiqo.submit.auth.CodiqoCredential;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class PreviousFindingsTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PATH = "/api/v1/projects/p1/commits/" + SHA + "/findings";

    @Test
    void theCommitsEarlierBugsAreReadInTheServersOrder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            ObjectNode answer = JSON.objectNode();
            answer.putArray("findings")
                    .add(JSON.objectNode().put("file", "a/Ledger.java").put("line", 12).put("type", "concurrency").put("severity", "major").put("title", "loses an update"))
                    .add(JSON.objectNode().put("file", "a/Ledger.java").put("type", "logic").put("severity", "minor"));
            respond(exchange, HttpResponseStatus.OK, answer.toString());
        });
        server.start();
        try {
            assertEquals(
                    List.of(new PreviousFinding("a/Ledger.java", 12, "concurrency", "major", "loses an update"), new PreviousFinding("a/Ledger.java", null, "logic", "minor", null)),
                    PreviousFindings.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", SHA, false, new NoopLog()));
        } finally {
            server.stop(0);
        }
    }
    /** a commit or project the server does not know, or a server without the endpoint, gives the triage no hint and no failure */
    @Test
    void anUnknownCommitOrAnOlderServerGivesNoFindings() throws Exception {
        for (HttpResponseStatus status : List.of(HttpResponseStatus.NOT_FOUND, HttpResponseStatus.METHOD_NOT_ALLOWED)) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/projects", exchange -> respond(exchange, status, JSON.objectNode().put("message", status.reasonPhrase()).toString()));
            server.start();
            try {
                assertEquals(List.of(), PreviousFindings.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", SHA, false, new NoopLog()), status.toString());
            } finally {
                server.stop(0);
            }
        }
    }
    /** the generated client reads a success without a body as null, which is no hint, never a failed review */
    @Test
    void aSuccessWithoutABodyGivesNoFindings() throws Exception {
        for (HttpResponseStatus status : List.of(HttpResponseStatus.OK, HttpResponseStatus.NO_CONTENT)) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(PATH, exchange -> respond(exchange, status, StringUtils.EMPTY));
            server.start();
            try {
                assertEquals(List.of(), PreviousFindings.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", SHA, false, new NoopLog()), status.toString());
            } finally {
                server.stop(0);
            }
        }
    }
    /** the hint only helps the review, so a failing endpoint is asked once and the review runs without it */
    @Test
    void aFailingServerIsAskedOnceAndGivesNoFindings() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects", exchange -> {
            requests.incrementAndGet();
            respond(exchange, HttpResponseStatus.SERVICE_UNAVAILABLE, JSON.objectNode().put("message", "unavailable").toString());
        });
        server.start();
        try {
            assertEquals(List.of(), PreviousFindings.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", SHA, false, new NoopLog()));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }
    @Test
    void aCredentialThatCannotBeRefreshedGivesNoFindings() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects", exchange -> {
            requests.incrementAndGet();
            respond(exchange, HttpResponseStatus.OK, JSON.objectNode().toString());
        });
        server.start();
        try {
            CodiqoCredential expired = new CodiqoCredential() {
                @Override
                public void accept(BiConsumer<String, String> header) throws IOException {
                    throw new IOException("the auth server is down");
                }
                @Override
                public String describe() {
                    return "expired login";
                }
            };
            assertEquals(List.of(), PreviousFindings.fetch(url(server), expired, 5, 5, "p1", SHA, false, new NoopLog()));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }
    @Test
    void aHiddenSourceProjectAsksForNothing() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects", exchange -> {
            requests.incrementAndGet();
            respond(exchange, HttpResponseStatus.OK, JSON.objectNode().toString());
        });
        server.start();
        try {
            assertEquals(List.of(), PreviousFindings.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", SHA, true, new NoopLog()));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }
    private static String url(HttpServer server) {
        return RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
    }
    /** an empty body is sent with no length at all ({@code -1}), since a length of 0 makes the JDK server stream it chunked */
    private static void respond(HttpExchange exchange, HttpResponseStatus status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status.code(), ArrayUtils.isEmpty(bytes) ? -1 : bytes.length);
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
