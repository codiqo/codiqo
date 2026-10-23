package io.codiqo.llm.review;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.util.RequestAuthorizer;
import io.netty.handler.codec.http.HttpHeaderNames;

/**
 * A loop back HTTP server made for one review, its exchanges on virtual threads, with a secret of its own. Any process
 * on the machine can reach a loop back port, so the handler serves a caller only when {@link #isAuthorized} accepts
 * the secret it presents. {@link LlmRelay} and {@link ReviewAnswers} are both served this way.
 */
public final class LoopbackServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final String secret;

    private LoopbackServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.secret = RandomStringUtils.secure().nextAlphanumeric(RunArgs.LOOPBACK_SECRET_LENGTH);
    }
    public int getPort() {
        return server.getAddress().getPort();
    }
    public String getSecret() {
        return secret;
    }
    /** the executor the exchanges run on, for a client of the server's owner to make its calls on as well */
    public ExecutorService getExecutor() {
        return executor;
    }
    /** serves the path with the handler and starts listening; the handler's owner is made with the server first */
    public void serve(String path, HttpHandler handler) {
        server.createContext(path, handler);
        server.start();
    }
    /** compared in constant time, so the time a refusal takes says nothing about how much of the secret was right */
    public boolean isAuthorized(HttpExchange exchange) {
        String presented = StringUtils.defaultString(exchange.getRequestHeaders().getFirst(HttpHeaderNames.AUTHORIZATION.toString()));
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), RequestAuthorizer.bearer(secret).getBytes(StandardCharsets.UTF_8));
    }
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
    public static LoopbackServer create() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(RunArgs.LOOPBACK_HOST, 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        return new LoopbackServer(server, executor);
    }
}
