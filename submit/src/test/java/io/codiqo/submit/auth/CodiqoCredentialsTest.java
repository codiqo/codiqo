package io.codiqo.submit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Maps;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.codiqo.util.RequestAuthorizer;
import java.util.Map;

/** The order a build finds its credential in, the same for Maven and Gradle. */
class CodiqoCredentialsTest {
    @TempDir
    Path home;

    private FakeAuthorizationServer server;
    private String previousHome;

    @BeforeEach
    void start() throws IOException {
        server = new FakeAuthorizationServer();
        previousHome = System.setProperty(CredentialStore.HOME_PROPERTY, home.toString());
    }
    @AfterEach
    void stop() {
        server.close();
        if (previousHome == null) {
            System.clearProperty(CredentialStore.HOME_PROPERTY);
        } else {
            System.setProperty(CredentialStore.HOME_PROPERTY, previousHome);
        }
    }
    @Test
    void aConfiguredKeyWinsOverAStoredLogin() throws IOException {
        store().store(new OAuthTokens("client-1", "access", Instant.now().plusSeconds(600), "refresh"));

        CodiqoCredential credential = resolve("cdq_ci_key", false);

        assertInstanceOf(ApiKeyCredential.class, credential);
        assertEquals(Map.of(ApiKeyCredential.HEADER, "cdq_ci_key"), headers(credential));
    }
    /** a pipeline that lost its secret must fail, not wait on a browser nobody will answer */
    @Test
    void aKeyNamingAnUnsetVariableIsAnError() {
        IOException err = assertThrows(IOException.class, () -> CodiqoCredentials.resolve("env:CODIQO_TEST_UNSET_VARIABLE", server.url(),
                FakeAuthorizationServer.RESOURCE, true, new RecordingLog()));
        assertTrue(err.getMessage().contains("resolves to nothing"), err.getMessage());
    }
    @Test
    void aStoredLoginIsUsedWithoutTheBrowser() throws IOException {
        store().store(new OAuthTokens("client-1", "access-stored", Instant.now().plusSeconds(600), "refresh"));

        CodiqoCredential credential = resolve(null, false);

        assertInstanceOf(OAuthCredential.class, credential);
        assertEquals(Map.of(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer("access-stored")), headers(credential));
    }
    @Test
    void aStoredLoginThatExpiredIsRefreshedUpFront() throws IOException {
        String refresh = server.rotateElsewhere("none");
        store().store(new OAuthTokens("client-1", "expired", Instant.now().minusSeconds(60), refresh));

        resolve(null, false);

        assertEquals(1, server.tokenRequests.size(), "refreshed while the developer is still at the terminal, not mid-build");
    }
    /** no answer from the auth server is no verdict on the login: it is kept, and the refresh is retried with the first call */
    @Test
    void anUnreachableAuthServerDoesNotEndAStoredLogin() throws IOException {
        String unreachable = "http://127.0.0.1:9";
        new CredentialStore(home, unreachable).store(new OAuthTokens("client-1", "expired", Instant.now().minusSeconds(60), "refresh-x"));

        CodiqoCredential credential = CodiqoCredentials.resolve(null, unreachable, FakeAuthorizationServer.RESOURCE, false, new RecordingLog()).orElseThrow();

        assertInstanceOf(OAuthCredential.class, credential);
    }
    /** an ended login is not offered to a worker that cannot open a browser: it gets nothing, and says why */
    @Test
    void anEndedLoginWithoutABrowserResolvesToNothing() throws IOException {
        store().store(new OAuthTokens("client-1", "expired", Instant.now().minusSeconds(60), "revoked"));

        assertTrue(CodiqoCredentials.resolve(null, server.url(), FakeAuthorizationServer.RESOURCE, false, new RecordingLog()).isEmpty());
    }
    @Test
    void aKeyFromBeforeOAuthIsStillUsed() throws IOException {
        store().storeApiKey("cdq_legacy", Instant.now().plus(1, ChronoUnit.DAYS).toString());

        CodiqoCredential credential = resolve(null, false);

        assertInstanceOf(ApiKeyCredential.class, credential);
        assertEquals(Map.of(ApiKeyCredential.HEADER, "cdq_legacy"), headers(credential));
    }
    private CredentialStore store() {
        return new CredentialStore(server.url());
    }
    private CodiqoCredential resolve(String configuredKey, boolean allowBrowser) throws IOException {
        return CodiqoCredentials.resolve(configuredKey, server.url(), FakeAuthorizationServer.RESOURCE, allowBrowser, new RecordingLog()).orElseThrow();
    }
    private static Map<String, String> headers(CodiqoCredential credential) throws IOException {
        Map<String, String> toReturn = Maps.newLinkedHashMap();
        credential.accept(toReturn::put);
        return toReturn;
    }
}
