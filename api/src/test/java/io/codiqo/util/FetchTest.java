package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.LogFactory;

class FetchTest {
    private static final int UNAVAILABLE = 503;

    @Test
    void anErrorStatusIsAFailedDownload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(UNAVAILABLE, -1);
            exchange.close();
        });
        server.start();
        try (Fetch fetch = new Fetch(new RunArgs())) {
            DownloadFailedException err = assertThrows(DownloadFailedException.class,
                    () -> fetch.download(logs(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + archive()).toURL()));

            assertTrue(err.getMessage().endsWith("status: " + UNAVAILABLE), err.getMessage());
        } finally {
            server.stop(0);
        }
    }
    @Test
    void anUnreachableHostIsAFailedDownload() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }
        try (Fetch fetch = new Fetch(new RunArgs())) {
            assertThrows(DownloadFailedException.class, () -> fetch.download(logs(), URI.create("http://127.0.0.1:" + port + "/" + archive()).toURL()));
        }
    }
    /** a name no earlier download left in the shared cache, which would be answered from there */
    private static String archive() {
        return "fetch-test-" + UUID.randomUUID() + ".tar.gz";
    }
    /** only a download that succeeded logs, so a failed one never asks for a logger */
    private static LogFactory logs() {
        return clazz -> fail("a failed download logs nothing");
    }
}
