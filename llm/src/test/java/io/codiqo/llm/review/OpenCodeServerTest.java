package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import tools.jackson.databind.node.JsonNodeFactory;

class OpenCodeServerTest {
    /**
     * What {@code opencode serve} does while it boots: its web interface, every path outside {@code /api/}, answers as
     * soon as the socket listens and without credentials, while the API answers 503 service_starting until the server
     * has booted, here 1.5 seconds after it listens.
     */
    private static final String FAKE_OPENCODE = """
            import com.sun.net.httpserver.HttpServer;
            import java.net.InetSocketAddress;
            import java.nio.charset.StandardCharsets;
            import java.util.Arrays;
            import java.util.Base64;

            public class FakeOpenCode {
                public static void main(String[] args) throws Exception {
                    int port = Integer.parseInt(args[Arrays.asList(args).indexOf("--port") + 1]);
                    String credentials = "opencode:" + System.getenv("OPENCODE_SERVER_PASSWORD");
                    String authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                    long booted = System.nanoTime() + 1_500_000_000L;
                    server.createContext("/", exchange -> {
                        int status = 200;
                        String body = "{\\"data\\":[]}";
                        if (!exchange.getRequestURI().getPath().startsWith("/api/")) {
                            body = "<html></html>";
                        } else if (!authorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            status = 401;
                        } else if (System.nanoTime() < booted) {
                            status = 503;
                            body = "{\\"code\\":\\"service_starting\\"}";
                        }
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
                    server.start();
                }
            }
            """;

    @TempDir
    Path tempDir;

    /** a review that started on the web interface's answer had its first API call refused with 503 service_starting */
    @Test
    void theServerIsReadyOnlyOnceItsApiAnswers() throws Exception {
        RunArgs args = new RunArgs();
        args.setReviewExecutable(fakeOpenCode().toString());

        try (OpenCodeServer server = OpenCodeServer.start(args, JsonNodeFactory.instance.objectNode(), tempDir, new NoopLog())) {
            HttpRequest mcp = HttpRequest.newBuilder(HttpUrl.get(server.getUrl()).newBuilder().addPathSegments("api/mcp").build().uri())
                    .header(HttpHeaderNames.AUTHORIZATION.toString(), Credentials.basic(OpenCodeClient.USER, server.getPassword()))
                    .GET()
                    .build();

            assertEquals(HttpResponseStatus.OK.code(), HttpClient.newHttpClient().send(mcp, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
    /** an {@code opencode} executable that runs {@link #FAKE_OPENCODE} with the JDK running the test */
    private Path fakeOpenCode() throws IOException {
        Path source = Files.writeString(tempDir.resolve("FakeOpenCode.java"), FAKE_OPENCODE, StandardCharsets.UTF_8);
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        String script = """
                #!/bin/sh
                exec '%s' '%s' "$@"
                """.formatted(java, source);
        Path toReturn = Files.writeString(tempDir.resolve("opencode"), script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(toReturn, PosixFilePermissions.fromString("rwxr-xr-x"));
        return toReturn;
    }
}
