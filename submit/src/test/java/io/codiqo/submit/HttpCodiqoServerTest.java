package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.submit.auth.ApiKeyCredential;
import io.codiqo.submit.auth.CodiqoCredential;
import io.netty.handler.codec.http.HttpResponseStatus;

/** Codiqo's REST API as both plugins reach it: one credential for the run, and the calls that must not fail it */
class HttpCodiqoServerTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PROJECT = "com.example:app";
    private static final String HASH_KEY = "0123456789abcdef0123456789abcdef";
    private static final Callable<CodiqoCredential> NO_LOGIN = () -> {
        throw new IOException("no stored login for this machine");
    };

    /** one login for the whole run: resolved per call, a missing one opened a browser login at each of them */
    @Test
    void theCredentialIsResolvedOnceForEveryCall() throws Exception {
        List<String> calls = Lists.newCopyOnWriteArrayList();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/v1/analyses", exchange -> {
            calls.add(exchange.getRequestMethod() + StringUtils.SPACE + exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(HttpResponseStatus.NO_CONTENT.code(), -1);
            exchange.close();
        });
        api.start();
        AtomicInteger resolved = new AtomicInteger();
        try {
            CodiqoServer server = server(String.format("http://127.0.0.1:%d", api.getAddress().getPort()), () -> {
                resolved.incrementAndGet();
                return new ApiKeyCredential("test-api-key");
            });

            server.exclude(SHA, exclusion());
            server.exclude(SHA, exclusion());
        } finally {
            api.stop(0);
        }

        assertEquals(List.of("PATCH /api/v1/analyses/commit/" + SHA + "/exclude", "PATCH /api/v1/analyses/commit/" + SHA + "/exclude"), calls);
        assertEquals(1, resolved.get());
    }
    /** a hidden-source run's exclusion and hotspots leave without the code, whichever of them is sent */
    @Test
    void everyModelSentLeavesHidden() throws Exception {
        List<String> bodies = Lists.newCopyOnWriteArrayList();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/v1", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(HttpResponseStatus.NO_CONTENT.code(), -1);
            exchange.close();
        });
        api.start();
        try {
            CodiqoServer server = new HttpCodiqoServer(String.format("http://127.0.0.1:%d", api.getAddress().getPort()), RunArgs.DEFAULT_RESOURCE_URL,
                    () -> new ApiKeyCredential("test-api-key"), 5, 5, PROJECT, SourceHiding.of(true, Optional.of(HASH_KEY)), new NoopLog());

            server.exclude(SHA, new AnalysisExcludeModel()
                    .reason("no diff files match registered languages — changed files: [Secret.sql]")
                    .category(AnalysisExcludeCategory.NO_ANALYZABLE_DIFF)
                    .detail("Secret.sql changed")
                    .files(List.of()));
            server.submitHotspots(new HotspotSnapshotModel().commitSha(SHA).classes(List.of()));
        } finally {
            api.stop(0);
        }

        assertEquals(2, bodies.size());
        for (String body : bodies) {
            assertTrue(Strings.CS.contains(body, "\"sourceHidden\":true"), body);
            assertFalse(Strings.CS.contains(body, "Secret"), body);
        }
    }
    /** the run still fails with its own cause, which a missing login thrown from the halt would replace */
    @Test
    void aHaltWithoutALoginIsNotRecordedAndThrowsNothing() {
        assertFalse(server("http://127.0.0.1:1", NO_LOGIN).halt(SHA, new IOException("opencode serve exited with code 1")));
    }
    /** the review runs without the hint, as it does when the fetch itself fails */
    @Test
    void previousFindingsWithoutALoginAreNone() {
        assertEquals(List.of(), server("http://127.0.0.1:1", NO_LOGIN).previousFindings(SHA));
    }
    private static CodiqoServer server(String apiUrl, Callable<CodiqoCredential> credential) {
        return new HttpCodiqoServer(apiUrl, RunArgs.DEFAULT_RESOURCE_URL, credential, 5, 5, PROJECT, SourceHiding.NONE, new NoopLog());
    }
    private static AnalysisExcludeModel exclusion() {
        return new AnalysisExcludeModel().reason("merge commit (multiple parents)").category(AnalysisExcludeCategory.MERGE_COMMIT).files(List.of());
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
