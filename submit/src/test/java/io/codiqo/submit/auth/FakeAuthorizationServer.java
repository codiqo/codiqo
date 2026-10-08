package io.codiqo.submit.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.codiqo.util.RequestAuthorizer;

/**
 * A stand-in for Better Auth's OAuth endpoints that enforces what the real one does, as pinned against a real database
 * in codiqo-web's oauth-registration e2e test: codes are bound to the client, redirect and PKCE challenge, every token
 * request must name the resource, and a refresh rotates the refresh token, refusing the old one with invalid_grant.
 *
 * <p>The approval is automatic: {@link #approve(URI)} plays the browser and answers the authorize URL with a
 * redirect to the loopback callback, or with the configured error.
 */
final class FakeAuthorizationServer implements AutoCloseable {
    static final String RESOURCE = "https://mcp.codiqo.test/mcp";
    static final long TOKEN_LIFETIME_SECONDS = 900;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final HttpServer server;
    private final Map<String, Map<String, String>> codes = Maps.newConcurrentMap();
    private final Map<String, String> liveRefreshTokens = Maps.newConcurrentMap();
    private final AtomicInteger issued = new AtomicInteger();
    final List<JsonNode> registrations = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> authorizations = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    volatile String denyWith;
    volatile String answerState;
    volatile long tokenLifetimeSeconds = TOKEN_LIFETIME_SECONDS;

    FakeAuthorizationServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/oauth2/register", this::register);
        server.createContext("/api/auth/oauth2/token", this::token);
        server.start();
    }
    String url() {
        return RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
    }
    OAuthClient client() {
        return new OAuthClient(url(), RESOURCE);
    }
    /** what the browser does once the developer approves: follow the authorize URL back to the loopback redirect */
    URI approve(URI authorize) {
        Map<String, String> query = query(authorize.getRawQuery());
        authorizations.add(query);

        String redirect = query.get("redirect_uri");
        if (StringUtils.isNotBlank(denyWith)) {
            return URI.create(redirect + "?error=" + denyWith + "&error_description=The+user+denied+the+request&state=" + query.get("state"));
        }
        String code = "code-" + issued.incrementAndGet();
        codes.put(code, query);
        return URI.create(redirect + "?code=" + code + "&state=" + StringUtils.defaultIfBlank(answerState, query.get("state")));
    }
    /** a refresh token issued now, as if the login had happened in another process */
    String rotateElsewhere(String refreshToken) {
        String toReturn = "refresh-" + issued.incrementAndGet();
        liveRefreshTokens.remove(refreshToken);
        liveRefreshTokens.put(toReturn, "client");
        return toReturn;
    }
    @Override
    public void close() {
        server.stop(0);
    }
    private void register(HttpExchange exchange) throws IOException {
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        registrations.add(body);
        respond(exchange, HttpResponseStatus.CREATED.code(), Map.of("client_id", "client-" + registrations.size(), "scope", body.path("scope").asString()));
    }
    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenRequests.add(form);

        if (!RESOURCE.equals(form.get("resource"))) {
            respond(exchange, HttpResponseStatus.BAD_REQUEST.code(), Map.of("error", "invalid_target", "error_description", "the token would not be a JWT for the backend"));
            return;
        }
        if ("authorization_code".equals(form.get("grant_type"))) {
            Map<String, String> authorized = codes.remove(StringUtils.defaultString(form.get("code")));
            if (authorized == null || !authorized.get("redirect_uri").equals(form.get("redirect_uri"))
                    || !authorized.get("client_id").equals(form.get("client_id"))
                    || !authorized.get("code_challenge").equals(challenge(form.get("code_verifier")))) {
                respond(exchange, HttpResponseStatus.BAD_REQUEST.code(), Map.of("error", "invalid_grant", "error_description", "code, redirect, client or verifier mismatch"));
                return;
            }
            issueTokens(exchange, form.get("client_id"));
            return;
        }
        if ("refresh_token".equals(form.get("grant_type")) && liveRefreshTokens.remove(StringUtils.defaultString(form.get("refresh_token"))) != null) {
            issueTokens(exchange, form.get("client_id"));
            return;
        }
        respond(exchange, HttpResponseStatus.BAD_REQUEST.code(), Map.of("error", "invalid_grant", "error_description", "the refresh token is no longer valid"));
    }
    private void issueTokens(HttpExchange exchange, String clientId) throws IOException {
        int n = issued.incrementAndGet();
        String refresh = "refresh-" + n;
        liveRefreshTokens.put(refresh, clientId);
        respond(exchange, HttpResponseStatus.OK.code(), Map.of(
                "access_token", "header.access-" + n + ".signature",
                "token_type", RequestAuthorizer.BEARER,
                "expires_in", tokenLifetimeSeconds,
                "refresh_token", refresh));
    }
    private void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    static Map<String, String> query(String raw) {
        Map<String, String> toReturn = Maps.newLinkedHashMap();
        for (String pair : Splitter.on('&').omitEmptyStrings().split(StringUtils.defaultString(raw))) {
            toReturn.put(URLDecoder.decode(StringUtils.substringBefore(pair, "="), StandardCharsets.UTF_8),
                    URLDecoder.decode(StringUtils.substringAfter(pair, "="), StandardCharsets.UTF_8));
        }
        return toReturn;
    }
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(StringUtils.defaultString(verifier).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception err) {
            throw new IllegalStateException(err);
        }
    }
}
