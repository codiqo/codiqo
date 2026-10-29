package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.submit.auth.ApiKeyCredential;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class IndexedCommitsTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String INDEXED = "0123456789abcdef0123456789abcdef01234567";
    private static final String UNKNOWN = "89abcdef0123456789abcdef0123456789abcdef";
    private static final String STATUS_PATH = "/api/v1/projects/p1/commits/";

    @Test
    void aCommitTheServerKnowsIsIndexedAndOneItAnswers404ForIsNot() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(RunArgs.LOOPBACK_HOST, 0), 0);
        server.createContext(STATUS_PATH, exchange -> {
            if (exchange.getRequestURI().getPath().endsWith(INDEXED)) {
                ObjectNode answer = JSON.objectNode().put("analysisState", "completed");
                answer.putObject("commit").put("sha", INDEXED);
                respond(exchange, HttpResponseStatus.OK.code(), answer.toString());
            } else {
                respond(exchange, HttpResponseStatus.NOT_FOUND.code(), JSON.objectNode().put("message", "not indexed").toString());
            }
        });
        server.start();
        try {
            IndexedCommits indexed = indexedCommits(server);

            assertTrue(indexed.contains(INDEXED));
            assertFalse(indexed.contains(UNKNOWN));
        } finally {
            server.stop(0);
        }
    }
    /** a lookup the server refuses is no answer: reading it as "not indexed" would score the merged line a second time */
    @Test
    void aRefusedLookupFailsTheGate() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(RunArgs.LOOPBACK_HOST, 0), 0);
        server.createContext(STATUS_PATH, exchange -> respond(exchange, HttpResponseStatus.FORBIDDEN.code(), JSON.objectNode().put("message", "no").toString()));
        server.start();
        try {
            IndexedCommits indexed = indexedCommits(server);

            assertThrows(ApiException.class, () -> indexed.contains(UNKNOWN));
        } finally {
            server.stop(0);
        }
    }
    private static IndexedCommits indexedCommits(HttpServer server) {
        String url = RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
        return IndexedCommits.of(new NoopLog(), CommitIndexPublisher.buildClient(url, new ApiKeyCredential("k"), 5, 5), url, "p1");
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
