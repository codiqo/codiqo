package io.codiqo.submit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import tools.jackson.databind.JsonNode;

/**
 * The login against an authorization server that holds the client to the real contract. The browser is played by
 * following the authorize URL to the loopback callback, so the receiver, the state check and the PKCE exchange all
 * run as they do on a developer's machine.
 */
class BrowserLoginTest {
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<URI> opened = new CopyOnWriteArrayList<>();

    private FakeAuthorizationServer server;

    @BeforeEach
    void start() throws IOException {
        server = new FakeAuthorizationServer();
    }
    @AfterEach
    void stop() {
        server.close();
    }
    @Test
    void registersANativeClientAndExchangesTheCodeWithPkce() throws Exception {
        OAuthTokens tokens = login().login();

        assertEquals("client-1", tokens.getClientId());
        assertTrue(tokens.getAccessToken().startsWith("header.access-"));
        assertTrue(tokens.getRefreshToken().startsWith("refresh-"));

        JsonNode registration = server.registrations.getFirst();
        assertEquals("none", registration.path("token_endpoint_auth_method").asString(), "a public client: there is no secret to keep on a laptop");
        String redirect = registration.path("redirect_uris").get(0).asString();
        assertTrue(redirect.startsWith("http://127.0.0.1:") && redirect.endsWith("/callback"), redirect);
        assertEquals(List.of("authorization_code", "refresh_token"), List.of(
                registration.path("grant_types").get(0).asString(), registration.path("grant_types").get(1).asString()));
        assertTrue(Splitter.on(' ').trimResults().omitEmptyStrings().splitToList(registration.path("scope").asString()).containsAll(List.of("codiqo:llm", "codiqo:submit")),
                "the scopes the consent page has to name");

        Map<String, String> authorize = server.authorizations.getFirst();
        assertEquals("S256", authorize.get("code_challenge_method"));
        assertEquals(FakeAuthorizationServer.RESOURCE, authorize.get("resource"), "without the resource the token is not a JWT the backend accepts");
        assertEquals(Joiner.on(" ").join(BrowserLogin.SCOPES), authorize.get("scope"));

        Map<String, String> exchange = server.tokenRequests.getFirst();
        assertEquals("authorization_code", exchange.get("grant_type"));
        assertEquals(authorize.get("code_challenge"), FakeAuthorizationServer.challenge(exchange.get("code_verifier")));
        assertEquals(1, opened.size(), "one browser tab per login");
    }
    @Test
    void aDeniedApprovalFailsWithTheServersReason() {
        server.denyWith = "access_denied";

        OAuthException err = assertThrows(OAuthException.class, () -> login().login());
        assertEquals("access_denied", err.getError());
        assertEquals("The user denied the request", err.getMessage());
        assertTrue(server.tokenRequests.isEmpty(), "nothing is exchanged after a denial");
    }
    /** a callback carrying someone else's state is not this login's answer, even with a valid-looking code */
    @Test
    void aCallbackForAnotherLoginIsRefused() {
        server.answerState = "forged-state";

        IOException err = assertThrows(IOException.class, () -> login().login());
        assertTrue(err.getMessage().contains("state mismatch"), err.getMessage());
        assertTrue(server.tokenRequests.isEmpty());
    }
    /** a build agent has nothing to open, so the failure travels out instead of a ten-minute wait */
    @Test
    void aMachineWithoutABrowserFailsAtOnce() {
        BrowserLogin login = new BrowserLogin(server.client(), new RecordingLog()) {
            @Override
            protected void openBrowser(URI uri) throws IOException {
                throw new IOException("no browser on this machine");
            }
        };

        IOException err = assertThrows(IOException.class, login::login);
        assertEquals("no browser on this machine", err.getMessage());
        assertTrue(server.tokenRequests.isEmpty());
    }
    private BrowserLogin login() {
        return new BrowserLogin(server.client(), new RecordingLog()) {
            @Override
            protected void openBrowser(URI uri) throws IOException {
                opened.add(uri);
                URI callback = server.approve(uri);
                // the browser's request to the loopback receiver, made off the login's thread as a browser would
                Thread.ofVirtual().start(() -> {
                    try {
                        http.send(HttpRequest.newBuilder(callback).GET().build(), HttpResponse.BodyHandlers.discarding());
                    } catch (Exception err) {
                        throw new IllegalStateException(err);
                    }
                });
            }
        };
    }
}
