package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.submit.auth.ApiKeyCredential;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ProjectTagsTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void theProjectsTagsAreReadInTheServersOrder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects/p1/tags", exchange -> {
            ObjectNode answer = JSON.objectNode();
            answer.putArray("technical").add("kafka").add("caching");
            respond(exchange, 200, answer.toString());
        });
        server.start();
        try {
            ReviewTags tags = ProjectTags.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", new NoopLog());

            assertEquals(List.of("kafka", "caching"), tags.getTechnical());
            assertEquals(List.of(), tags.getFunctional(), "a list the server leaves out is empty");
        } finally {
            server.stop(0);
        }
    }
    /** a project the server does not know yet, or a server without the endpoint, gives the review no tags and no failure */
    @Test
    void anUnknownProjectOrAnOlderServerGivesNoTags() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects", exchange -> respond(exchange, 404, JSON.objectNode().put("message", "not found").toString()));
        server.start();
        try {
            ReviewTags tags = ProjectTags.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", new NoopLog());

            assertEquals(List.of(), tags.getTechnical());
            assertEquals(List.of(), tags.getFunctional());
        } finally {
            server.stop(0);
        }
    }
    /** no server answers this path with 400, so one is a real error, reported rather than read as "no tags" */
    @Test
    void aBadRequestIsAnError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/projects", exchange -> respond(exchange, 400, JSON.objectNode().put("message", "bad").toString()));
        server.start();
        try {
            assertEquals(400, assertThrows(ApiException.class, () -> ProjectTags.fetch(url(server), new ApiKeyCredential("k"), 5, 5, "p1", new NoopLog())).getCode());
        } finally {
            server.stop(0);
        }
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
