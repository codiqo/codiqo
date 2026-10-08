package io.codiqo.submit.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.QueryStringEncoder;
import okhttp3.HttpUrl;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OAuth 2.1 endpoints of Codiqo's authorization server (Better Auth under {@code /api/auth}), the same server MCP
 * clients sign in through. Every token request names the protected resource (RFC 8707): without it the server issues
 * an opaque token the backend cannot verify, with it a JWT bound to that resource.
 */
public class OAuthClient {
    private static final String API_PATH = "api/auth";
    private static final int BODY_PREVIEW = 400;
    private static final String FORM = HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString();
    private static final String JSON = HttpHeaderValues.APPLICATION_JSON.toString();
    private static final String CONTENT_TYPE = HttpHeaderNames.CONTENT_TYPE.toString();

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(RunArgs.OAUTH_REQUEST_TIMEOUT).build();
    private final HttpUrl authUrl;
    private final String resource;

    public OAuthClient(String authUrl, String resource) {
        this.authUrl = HttpUrl.get(authUrl);
        this.resource = Objects.requireNonNull(resource);
    }
    /**
     * Dynamic registration (RFC 7591) of a public native client. One is registered per login rather than kept, since
     * the client is only good for the refresh tokens issued to it and a login replaces those anyway.
     */
    public String register(String clientName, String redirectUri, List<String> scopes) throws IOException {
        Map<String, Object> body = Maps.newLinkedHashMap();
        body.put("client_name", clientName);
        body.put("redirect_uris", List.of(redirectUri));
        body.put("token_endpoint_auth_method", "none");
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        body.put("scope", Joiner.on(StringUtils.SPACE).join(scopes));

        JsonNode registered = send(HttpRequest.newBuilder(endpoint("oauth2/register"))
                .header(CONTENT_TYPE, JSON)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
        return required(registered, "client_id");
    }
    public URI authorizationUrl(String clientId, String redirectUri, List<String> scopes, String state, String codeChallenge) {
        Map<String, String> query = Maps.newLinkedHashMap();
        query.put("response_type", "code");
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("scope", Joiner.on(StringUtils.SPACE).join(scopes));
        query.put("state", state);
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        query.put("resource", resource);
        return encoded(endpoint("oauth2/authorize").toString(), query);
    }
    public OAuthTokens exchange(String clientId, String code, String redirectUri, String codeVerifier) throws IOException {
        Map<String, String> params = Maps.newLinkedHashMap();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", redirectUri);
        params.put("client_id", clientId);
        params.put("code_verifier", codeVerifier);
        params.put("resource", resource);

        JsonNode issued = token(params);
        return new OAuthTokens(clientId, required(issued, "access_token"), expiresAt(issued), required(issued, "refresh_token"));
    }
    /**
     * The server rotates the refresh token, so what comes back replaces everything that was held. RFC 6749 section 6
     * lets a server keep the old refresh token by leaving it out of the answer, in which case the one just used stays.
     */
    public OAuthTokens refresh(OAuthTokens current) throws IOException {
        Map<String, String> params = Maps.newLinkedHashMap();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", current.getRefreshToken());
        params.put("client_id", current.getClientId());
        params.put("resource", resource);

        JsonNode issued = token(params);
        String refreshToken = StringUtils.defaultIfBlank(issued.path("refresh_token").asString(StringUtils.EMPTY), current.getRefreshToken());
        return new OAuthTokens(current.getClientId(), required(issued, "access_token"), expiresAt(issued), refreshToken);
    }
    private JsonNode token(Map<String, String> params) throws IOException {
        return send(HttpRequest.newBuilder(endpoint("oauth2/token"))
                .header(CONTENT_TYPE, FORM)
                .POST(HttpRequest.BodyPublishers.ofString(encoded(StringUtils.EMPTY, params).getRawQuery())));
    }
    private JsonNode send(HttpRequest.Builder request) throws IOException {
        HttpResponse<String> response;
        try {
            response = http.send(request.timeout(RunArgs.OAUTH_REQUEST_TIMEOUT).header(HttpHeaderNames.ACCEPT.toString(), JSON).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + request.build().uri(), err);
        }

        Optional<JsonNode> body = parse(response.body());
        if (HttpStatusClass.valueOf(response.statusCode()) == HttpStatusClass.SUCCESS) {
            return body.orElseThrow(() -> new IOException("%s answered HTTP %d with a body that is not JSON: %s".formatted(response.uri(),
                    response.statusCode(), StringUtils.abbreviate(response.body(), BODY_PREVIEW))));
        }
        /**
         * Only a client error carries an OAuth verdict. RFC 6749 section 5.2 answers every token error with 400 (or 401
         * for client authentication), and every code it defines is final. A 5xx is the server failing, even when it
         * names a code such as {@code server_error} or {@code temporarily_unavailable}: thrown as an OAuthException it
         * was taken for a verdict, so a refresh during a brief outage failed the submission with no retry and sent the
         * developer to log in again. As a plain IOException it is retried like any other network failure. A catch-all
         * route also answers {"error": {...}} on a 500, which is no OAuth error at all, hence the string check.
         */
        Optional<JsonNode> error = body.map(node -> node.path("error")).filter(JsonNode::isString);
        if (BooleanUtils.and(new boolean[] { HttpStatusClass.valueOf(response.statusCode()) == HttpStatusClass.CLIENT_ERROR, error.isPresent() })) {
            String code = error.get().asString();
            JsonNode description = body.get().path("error_description");
            throw new OAuthException(code, description.isString() ? StringUtils.defaultIfBlank(description.asString(), code) : code);
        }
        throw new IOException("%s answered HTTP %d: %s".formatted(response.uri(), response.statusCode(), response.body()));
    }
    /** an error page in front of the authorization server (a proxy, a catch-all route) need not be JSON */
    private Optional<JsonNode> parse(String body) {
        if (StringUtils.isBlank(body)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(body));
        } catch (JacksonException err) {
            return Optional.empty();
        }
    }
    private URI endpoint(String path) {
        return authUrl.newBuilder().addPathSegments(API_PATH).addPathSegments(path).build().uri();
    }
    private static Instant expiresAt(JsonNode issued) {
        long expiresIn = issued.path("expires_in").asLong(0);
        if (expiresIn > 0) {
            return Instant.now().plusSeconds(expiresIn);
        }
        return Instant.now().plus(RunArgs.OAUTH_ASSUMED_ACCESS_TOKEN_LIFETIME);
    }
    private static String required(JsonNode node, String field) throws IOException {
        String toReturn = node.path(field).asString(StringUtils.EMPTY);
        if (StringUtils.isBlank(toReturn)) {
            /** Only the field names are reported: the node may carry a token next to the missing one, and logs are not secret. */
            throw new IOException("the authorization server answered without %s; it sent %s".formatted(field, node.propertyNames()));
        }
        return toReturn;
    }
    /** the parameters as the query of {@code base}; a form body is that query on its own */
    private static URI encoded(String base, Map<String, String> params) {
        QueryStringEncoder toReturn = new QueryStringEncoder(base);
        params.forEach(toReturn::addParam);
        return URI.create(toReturn.toString());
    }
}
