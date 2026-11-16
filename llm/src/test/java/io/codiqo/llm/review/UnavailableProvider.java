package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.util.RequestAuthorizer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * The model's end point behind a review's relay in a test: it answers every call with a 503, which the relay counts as a
 * failure of the provider.
 */
final class UnavailableProvider implements AutoCloseable {
    private final HttpServer server;

    private UnavailableProvider(HttpServer server) {
        this.server = server;
    }
    /** the end point a relay forwards the agents' calls to */
    ReviewEndpoint endpoint() {
        return new ReviewEndpoint(RunArgs.loopbackUrl(server.getAddress().getPort()).encodedPath("/v1").build().toString(), null, null);
    }
    @Override
    public void close() {
        server.stop(0);
    }
    static UnavailableProvider start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(HttpResponseStatus.SERVICE_UNAVAILABLE.code(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return new UnavailableProvider(server);
    }
    /** one call to the model through the relay at this base URL, as an agent makes it, which the provider fails */
    static void callThrough(String relayUrl, String relaySecret) throws IOException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(relayUrl + "/models")).GET().header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(relaySecret)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE.code(), response.statusCode());
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            InterruptedIOException toThrow = new InterruptedIOException("interrupted while calling the model through the relay");
            toThrow.initCause(err);
            throw toThrow;
        }
    }
}
