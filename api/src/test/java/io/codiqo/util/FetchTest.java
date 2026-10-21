package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.LogFactory;

class FetchTest {
    private static final String ARCHIVE = "archive.tar.gz";
    private static final int OK = 200;
    private static final int UNAVAILABLE = 503;
    private static final int DECLARED_LENGTH = 1024;
    private static final byte[] PART = new byte[16];
    private static final int REQUEST_BUFFER = 8192;

    @Test
    void anErrorStatusIsAFailedDownload(@TempDir Path cache) throws Exception {
        HttpServer server = serve(exchange -> {
            exchange.sendResponseHeaders(UNAVAILABLE, -1);
            exchange.close();
        });
        try (Fetch fetch = new Fetch(new RunArgs(), cache)) {
            DownloadFailedException err = assertThrows(DownloadFailedException.class, () -> fetch.download(logs(), url(server)));

            assertTrue(err.getMessage().endsWith("status: " + UNAVAILABLE), err.getMessage());
        } finally {
            server.stop(0);
        }
    }
    @Test
    void anUnreachableHostIsAFailedDownload(@TempDir Path cache) throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }
        try (Fetch fetch = new Fetch(new RunArgs(), cache)) {
            assertThrows(DownloadFailedException.class, () -> fetch.download(logs(), URI.create("http://127.0.0.1:" + port + "/" + ARCHIVE).toURL()));
        }
    }
    /**
     * A host that closes the connection part way through the body, as a reset stream ends it, fails while the body is
     * read. A raw socket, since the JDK's server keeps the connection open and the test would wait for the read timeout.
     */
    @Test
    void aBodyCutShortIsAFailedDownload(@TempDir Path cache) throws Exception {
        try (ServerSocket host = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread cut = Thread.ofVirtual().start(() -> {
                try (Socket client = host.accept(); OutputStream out = client.getOutputStream()) {
                    client.getInputStream().read(new byte[REQUEST_BUFFER]);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + DECLARED_LENGTH + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(PART);
                } catch (IOException err) {
                    throw new UncheckedIOException(err);
                }
            });
            try (Fetch fetch = new Fetch(new RunArgs(), cache)) {
                assertThrows(DownloadFailedException.class, () -> fetch.download(logs(), URI.create("http://127.0.0.1:" + host.getLocalPort() + "/" + ARCHIVE).toURL()));
            }
            cut.join();
        }
    }
    /** a cache that cannot be written fails on every run of that runner, so it must halt like any failure of codiqo's */
    @Test
    void aCacheThatCannotBeWrittenIsNotAFailedDownload(@TempDir Path dir) throws Exception {
        Path notADirectory = Files.createFile(dir.resolve("cache"));
        HttpServer server = serve(exchange -> {
            exchange.sendResponseHeaders(OK, PART.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(PART);
            }
        });
        try (Fetch fetch = new Fetch(new RunArgs(), notADirectory)) {
            IOException err = assertThrows(IOException.class, () -> fetch.download(logs(), url(server)));

            assertFalse(err instanceof DownloadFailedException, err.toString());
        } finally {
            server.stop(0);
        }
    }
    private static HttpServer serve(HttpHandler handler) throws IOException {
        HttpServer toReturn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        toReturn.createContext("/", handler);
        toReturn.start();
        return toReturn;
    }
    private static URL url(HttpServer server) throws Exception {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + ARCHIVE).toURL();
    }
    /** only a download that succeeded logs, so a failed one never asks for a logger */
    private static LogFactory logs() {
        return clazz -> fail("a failed download logs nothing");
    }
}
