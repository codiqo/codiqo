package io.codiqo.maven.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.scribejava.core.model.Verb;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.maven.auth.CredentialStore.Credentials;

/**
 * The login against a stub better-auth. The polling loop belongs to ScribeJava and is not re-tested here; what is
 * worth pinning down is the contract between the two — that the library's form-encoded requests carry what
 * better-auth's schema requires, that a pending answer is retried rather than raised, and that the approved session
 * is exchanged for a key.
 */
class BrowserLoginTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String authUrl;
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> keyRequests = new CopyOnWriteArrayList<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final List<String> opened = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        authUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach
    void stopServer() {
        server.stop(0);
    }
    @Test
    void pollsUntilApprovedThenMintsAKey() throws Exception {
        deviceCodeIssued();
        server.createContext("/api/auth/device/token", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            if (isJson(exchange)) {
                tokenRequests.add(IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8));
                if (tokenRequests.size() == 1) {
                    respond(exchange, 400, Map.of(
                            "error", "authorization_pending", "error_description", "Waiting for user approval"));
                    return;
                }
                respond(exchange, 200, Map.of("access_token", "session-token", "token_type", "Bearer", "expires_in", 900));
                return;
            }
            respondUnsupportedMediaType(exchange);
        });
        handle("/api/auth/get-session", 200, Map.of("session", Map.of("activeOrganizationId", "org-7")));
        server.createContext("/api/auth/api-key/create", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            keyRequests.add(IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8));
            respond(exchange, 200, Map.of("key", "cdq_live_abc", "expiresAt", "2027-01-01T00:00:00Z"));
        });
        server.start();

        Credentials credentials = login().login();

        assertEquals("cdq_live_abc", credentials.getKey());
        assertEquals("org-7", credentials.getOrganizationId());
        assertEquals(2, tokenRequests.size(), "authorization_pending must be retried rather than raised");

        JsonNode body = MAPPER.readTree(tokenRequests.iterator().next());
        assertEquals("dev-code", body.path("device_code").asText());
        assertEquals(BrowserLogin.CLIENT_ID, body.path("client_id").asText());
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", body.path("grant_type").asText());

        // the developer is sent to the URL the server chose, and without the code in it — the code is typed
        assertEquals(List.of(authUrl + "/cli"), opened);

        /**
         * better-auth rejects an api key name longer than 32 characters, and a fully qualified host name can reach
         * that on its own — so the name has to stay inside the limit while still naming the machine to revoke
         */
        JsonNode created = MAPPER.readTree(keyRequests.iterator().next());
        String name = created.path("name").asText();
        assertTrue(name.length() <= 32, name);
        assertTrue(name.startsWith("codiqo-cli@"), name);

        // a laptop key that renews with one click does not need to outlive the day, and 1 day is also the
        // shortest the api-key plugin accepts (keyExpiration.minExpiresIn)
        assertEquals(TimeUnit.DAYS.toSeconds(1), created.path("expiresIn").asLong());
    }
    @Test
    void deniedApprovalFailsWithTheServerReason() throws Exception {
        deviceCodeIssued();
        handle("/api/auth/device/token", 400, Map.of(
                "error", "access_denied", "error_description", "The user denied the request"));
        server.start();

        IOException err = assertThrows(IOException.class, () -> login().login());
        assertEquals("The user denied the request", err.getMessage());
    }
    /**
     * A build agent has nothing to open and nobody to approve, so the desktop's own failure travels out rather than
     * being turned into a poll that runs until the device code expires ten minutes later.
     */
    @Test
    void machineWithoutABrowserFailsImmediately() throws Exception {
        deviceCodeIssued();
        handle("/api/auth/device/token", 400, Map.of(
                "error", "authorization_pending", "error_description", "Waiting for user approval"));
        server.start();

        IOException err = assertThrows(IOException.class, () -> login(false).login());
        assertTrue(err.getMessage().contains("does not support `browseURL`"), err.getMessage());
        assertEquals(List.of("/api/auth/device/code"), requested, "the token endpoint must not be polled at all");
    }
    /** without an active organisation the key would land under an arbitrary one, so the login has to stop */
    @Test
    void approvalWithoutAnActiveOrganizationFails() throws Exception {
        deviceCodeIssued();
        handle("/api/auth/device/token", 200, Map.of("access_token", "session-token"));
        handle("/api/auth/get-session", 200, Map.of("session", Map.of()));
        server.start();

        IOException err = assertThrows(IOException.class, () -> login().login());
        assertTrue(err.getMessage().contains("no active organization"), err.getMessage());
    }
    private BrowserLogin login() {
        return login(true);
    }
    private BrowserLogin login(boolean browserOpens) {
        return new BrowserLogin(authUrl, new SystemStreamLog()) {
            @Override
            protected void openBrowser(String uri) throws IOException {
                opened.add(uri);
                if (browserOpens) {
                    return;
                }
                throw new IOException("Current OS and desktop configuration does not support `browseURL`");
            }
        };
    }
    /**
     * The fields are the ones better-auth's own /device/code returns. The interval is 1 rather than 0 because a
     * response without a usable interval is clamped to RFC 8628's five seconds, which would only slow the suite down.
     */
    private void deviceCodeIssued() {
        handle("/api/auth/device/code", 200, Map.of(
                "device_code", "dev-code",
                "user_code", "WDJBMJHT",
                "verification_uri", authUrl + "/cli",
                "verification_uri_complete", authUrl + "/cli?user_code=WDJBMJHT",
                "expires_in", 600,
                "interval", 1));
    }
    private void handle(String path, int status, Map<String, ?> body) {
        server.createContext(path, exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            if (isJson(exchange)) {
                respond(exchange, status, body);
                return;
            }
            respondUnsupportedMediaType(exchange);
        });
    }
    /**
     * better-auth's router declares {@code allowedMediaTypes: ["application/json"]} and its device-authorization
     * plugin does not opt back into form encoding, so a form-encoded body — which is what RFC 8628 specifies and what
     * ScribeJava sends unaided — is answered with 415. The stub enforces that, otherwise this suite would pass
     * against a plugin the real server rejects.
     */
    private static boolean isJson(HttpExchange exchange) {
        if (Verb.POST.name().equals(exchange.getRequestMethod())) {
            return StringUtils.startsWith(exchange.getRequestHeaders().getFirst("Content-Type"), "application/json");
        }
        return true;
    }
    private static void respondUnsupportedMediaType(HttpExchange exchange) throws IOException {
        respond(exchange, 415, Map.of(
                "message", "Content-Type \"" + exchange.getRequestHeaders().getFirst("Content-Type")
                        + "\" is not allowed. Allowed types: application/json",
                "code", "UNSUPPORTED_MEDIA_TYPE"));
    }
    private static void respond(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
