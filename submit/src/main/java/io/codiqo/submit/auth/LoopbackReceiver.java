package io.codiqo.submit.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.RequiredArgsConstructor;

/**
 * Where the browser lands after the login (RFC 8252 section 7.3): a one-shot server on a free loopback port. The
 * authorization server matches a loopback redirect without its port, so every login may pick a new one.
 */
@RequiredArgsConstructor
public final class LoopbackReceiver implements AutoCloseable {
    public static final String CALLBACK_PATH = "/callback";

    private static final String HTML = HttpHeaderValues.TEXT_HTML + "; charset=utf-8";
    private static final String TEMPLATE_NAME = "login-callback";

    private static final TemplateEngine TEMPLATE_ENGINE;

    static {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/html/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());

        TEMPLATE_ENGINE = new TemplateEngine();
        TEMPLATE_ENGINE.setTemplateResolver(resolver);
    }

    private final HttpServer server;
    private final CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

    public String redirectUri() {
        return RunArgs.loopbackUrl(server.getAddress().getPort()).encodedPath(CALLBACK_PATH).build().toString();
    }
    public Map<String, String> await(Duration timeout) throws IOException {
        try {
            return callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException err) {
            throw new IOException("no answer from the browser within " + timeout + "; run the login again", err);
        } catch (ExecutionException err) {
            throw new IOException(err.getCause());
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the browser", err);
        }
    }
    @Override
    public void close() {
        server.stop(0);
    }
    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI());
        boolean approved = query.containsKey("code");
        Context page = new Context(Locale.ENGLISH);
        page.setVariable("approved", approved);
        page.setVariable("error", StringUtils.defaultIfBlank(query.get("error_description"), query.get("error")));

        byte[] body = TEMPLATE_ENGINE.process(TEMPLATE_NAME, page).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HTML);
        exchange.sendResponseHeaders((approved ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST).code(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        callback.complete(query);
    }
    public static LoopbackReceiver start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(RunArgs.LOOPBACK_HOST, 0), 0);
        LoopbackReceiver toReturn = new LoopbackReceiver(server);
        server.createContext(CALLBACK_PATH, toReturn::handle);
        server.start();
        return toReturn;
    }
    private static Map<String, String> query(URI uri) {
        return new QueryStringDecoder(uri)
                .parameters()
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFirst(), (first, second) -> first, LinkedHashMap::new));
    }
}
