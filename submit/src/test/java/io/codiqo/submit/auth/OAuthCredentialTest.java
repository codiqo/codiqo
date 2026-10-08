package io.codiqo.submit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Maps;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.codiqo.util.RequestAuthorizer;
import java.util.Map;

/** The access token lives minutes; these are the rules that keep an hour-long build signed in on top of it. */
class OAuthCredentialTest {
    @TempDir
    Path home;

    private FakeAuthorizationServer server;
    private CredentialStore store;

    @BeforeEach
    void start() throws IOException {
        server = new FakeAuthorizationServer();
        store = new CredentialStore(home, server.url());
    }
    @AfterEach
    void stop() {
        server.close();
    }
    @Test
    void aFreshTokenIsSentAsIsWithoutCallingTheServer() throws Exception {
        OAuthTokens fresh = new OAuthTokens("client-1", "access-now", Instant.now().plusSeconds(600), "refresh-0");

        Map<String, String> headers = Maps.newLinkedHashMap();
        new OAuthCredential(server.client(), store, fresh).accept(headers::put);

        assertEquals(Map.of(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer("access-now")), headers);
        assertTrue(server.tokenRequests.isEmpty());
    }
    @Test
    void aTokenAboutToExpireIsRefreshedAndTheRotationStored() throws Exception {
        String refresh = server.rotateElsewhere("none");
        OAuthTokens expiring = new OAuthTokens("client-1", "stale", Instant.now().plusSeconds(10), refresh);
        store.store(expiring);
        OAuthCredential credential = new OAuthCredential(server.client(), store, expiring);

        String bearer = credential.fresh().getAccessToken();

        assertNotEquals("stale", bearer);
        assertEquals("refresh_token", server.tokenRequests.getFirst().get("grant_type"));
        assertEquals(FakeAuthorizationServer.RESOURCE, server.tokenRequests.getFirst().get("resource"));
        OAuthTokens stored = store.findOAuth().orElseThrow();
        assertEquals(bearer, stored.getAccessToken());
        assertNotEquals(refresh, stored.getRefreshToken(), "the rotated refresh token is what the next run needs");
    }
    /**
     * Two processes share the login: the other one refreshed first, so this one's refresh token is already spent.
     * It picks up what the other stored instead of failing.
     */
    @Test
    void aRefreshAnotherProcessWonIsPickedUpFromTheStore() throws Exception {
        String spent = server.rotateElsewhere("none");
        String current = server.rotateElsewhere(spent);
        store.store(new OAuthTokens("client-1", "theirs", Instant.now().plusSeconds(600), current));

        OAuthCredential credential = new OAuthCredential(server.client(), store, new OAuthTokens("client-1", "mine", Instant.now().minusSeconds(1), spent));

        assertEquals("theirs", credential.fresh().getAccessToken());
    }
    /**
     * Two holders of one login in one run (the review relay and the submission) find the token expiring at the same
     * moment. The refresh token is accepted once, so only one of them may spend it; the other adopts the result.
     */
    @Test
    void concurrentHoldersSpendTheRefreshTokenOnce() throws Exception {
        String refresh = server.rotateElsewhere("none");
        OAuthTokens expiring = new OAuthTokens("client-1", "stale", Instant.now().plusSeconds(10), refresh);
        store.store(expiring);
        OAuthCredential relay = new OAuthCredential(server.client(), store, expiring);
        OAuthCredential submission = new OAuthCredential(server.client(), store, expiring);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> relay.fresh().getAccessToken());
            Future<String> second = executor.submit(() -> submission.fresh().getAccessToken());

            assertEquals(first.get(), second.get());
            assertEquals(1, server.tokenRequests.size(), "the refresh token was spent once");
        } finally {
            executor.shutdownNow();
        }
    }
    /**
     * The developer logged in since for another organization, which registered another client: that login is neither
     * adopted by this run, whose tokens answer for its own organization, nor overwritten by this run's refresh.
     */
    @Test
    void anotherClientsLoginIsNeitherAdoptedNorOverwritten() throws Exception {
        OAuthTokens otherOrganization = new OAuthTokens("client-2", "theirs", Instant.now().plusSeconds(600), server.rotateElsewhere("none"));
        store.store(otherOrganization);
        String mine = server.rotateElsewhere("none");
        OAuthCredential credential = new OAuthCredential(server.client(), store, new OAuthTokens("client-1", "stale", Instant.now().plusSeconds(10), mine));

        String bearer = credential.fresh().getAccessToken();

        assertNotEquals("theirs", bearer);
        assertEquals("client-1", server.tokenRequests.getFirst().get("client_id"));
        assertEquals("theirs", store.findOAuth().orElseThrow().getAccessToken(), "the newer login stays on disk");
    }
    /** deleting the credentials file is how a developer logs out: a run still refreshing must not bring the login back */
    @Test
    void aLoginDeletedDuringTheRunIsNotWrittenBack() throws Exception {
        OAuthTokens expiring = new OAuthTokens("client-1", "stale", Instant.now().plusSeconds(10), server.rotateElsewhere("none"));
        store.store(expiring);
        OAuthCredential credential = new OAuthCredential(server.client(), store, expiring);
        Files.delete(store.file());

        assertNotEquals("stale", credential.fresh().getAccessToken(), "the run keeps working on the refreshed tokens");
        assertFalse(Files.exists(store.file()));
    }
    /** a catch-all route answers {"error": {...}} on a 500: that is no OAuth verdict, and must not crash the read */
    @Test
    void anErrorObjectIsNoOAuthAnswer() throws Exception {
        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/api/auth/oauth2/token", exchange -> {
            ObjectNode error = JsonNodeFactory.instance.objectNode();
            error.putObject("error").put("message", "internal");
            byte[] body = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        broken.start();
        try {
            OAuthClient client = new OAuthClient(RunArgs.loopbackUrl(broken.getAddress().getPort()).build().toString(), FakeAuthorizationServer.RESOURCE);

            IOException err = assertThrows(IOException.class, () -> client.refresh(new OAuthTokens("client-1", "old", Instant.now(), "r")));

            assertFalse(err instanceof OAuthException, err.toString());
            assertTrue(err.getMessage().contains("500"), err.getMessage());
        } finally {
            broken.stop(0);
        }
    }
    /**
     * A 5xx that names an OAuth code is still the server failing, not a verdict on the login: it must reach ApiRetry and
     * the stored-login check as a plain IOException, which they retry, rather than as an OAuthException, which they do not.
     */
    @Test
    void aServerErrorWithAnOAuthCodeIsNoVerdict() throws Exception {
        HttpServer unavailable = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        unavailable.createContext("/api/auth/oauth2/token", exchange -> {
            byte[] body = JsonNodeFactory.instance.objectNode().put("error", "temporarily_unavailable").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        unavailable.start();
        try {
            OAuthClient client = new OAuthClient(RunArgs.loopbackUrl(unavailable.getAddress().getPort()).build().toString(), FakeAuthorizationServer.RESOURCE);

            IOException err = assertThrows(IOException.class, () -> client.refresh(new OAuthTokens("client-1", "old", Instant.now(), "r")));

            assertFalse(err instanceof OAuthException, err.toString());
            assertTrue(err.getMessage().contains("503"), err.getMessage());
        } finally {
            unavailable.stop(0);
        }
    }
    @Test
    void anEndedLoginFailsWithInvalidGrant() throws Exception {
        OAuthCredential credential = new OAuthCredential(server.client(), store, new OAuthTokens("client-1", "old", Instant.now().minusSeconds(1), "revoked"));

        OAuthException err = assertThrows(OAuthException.class, credential::fresh);
        assertTrue(err.isInvalidGrant());
    }
}
