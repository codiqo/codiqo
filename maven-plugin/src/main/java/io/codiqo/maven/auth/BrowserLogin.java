package io.codiqo.maven.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.builder.api.DefaultApi20;
import com.github.scribejava.core.httpclient.HttpClient;
import com.github.scribejava.core.httpclient.HttpClientConfig;
import com.github.scribejava.core.model.DeviceAuthorization;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuth2AccessTokenErrorResponse;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;

import dorkbox.desktop.Desktop;
import io.codiqo.maven.auth.CredentialStore.Credentials;
import io.codiqo.util.Env;
import lombok.RequiredArgsConstructor;

/**
 * Authorises a machine through the browser, using the OAuth 2.0 device grant (RFC 8628).
 *
 * <p>
 * The grant itself is ScribeJava's: it builds both requests, parses the responses, and — in
 * {@code pollAccessTokenDeviceAuthorizationGrant} — owns the polling loop with its interval and {@code slow_down}
 * back-off. What is left here is the part no OAuth library can know: exchanging the session for an api key, which
 * is better-auth's own endpoint, and the order a Maven run resolves its credential in.
 */
public class BrowserLogin {
    public static final String CLIENT_ID = "codiqo-maven-plugin";

    private static final String DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    /** better-auth's api-key plugin default: names outside 1..32 characters are rejected as INVALID_NAME_LENGTH */
    private static final int MAX_KEY_NAME_LENGTH = 32;

    /**
     * A laptop credential renews with one browser click, so it does not need to outlive the working day. One day is
     * also the shortest the api-key plugin accepts — its {@code keyExpiration.minExpiresIn} default is 1 day.
     */
    private static final Duration KEY_LIFETIME = Duration.ofDays(1);

    /** RFC 8628's own default for a response that omits the interval */
    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 5;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String authUrl;
    private final Log log;

    public BrowserLogin(String authUrl, Log log) {
        this.authUrl = Strings.CS.removeEnd(authUrl, "/");
        this.log = Objects.requireNonNull(log);
    }
    /**
     * The browser is opened before anything is printed, so a machine that has none fails there rather than sending a
     * developer to a page and giving up afterwards.
     */
    public Credentials login() throws Exception {
        try (OAuth20Service service = new ServiceBuilder(CLIENT_ID).build(new BetterAuthApi(authUrl))) {
            DeviceAuthorization authorization = service.getDeviceAuthorizationCodes();
            /**
             * RFC 8628 makes the interval optional and tells clients to assume five seconds, which ScribeJava turns
             * into 0 — a tight loop against the server we are waiting on
             */
            if (authorization.getIntervalSeconds() < 1) {
                authorization.setIntervalSeconds(DEFAULT_POLL_INTERVAL_SECONDS);
            }

            // deliberately not verification_uri_complete: a code carried in the URL is a code nobody reads, and
            // typing it is what ties the approval to the terminal the developer is actually looking at
            openBrowser(authorization.getVerificationUri());
            prompt(authorization);

            return mintApiKey(service, poll(service, authorization));
        }
    }
    /** the code and the URL are printed as well: the browser may open behind the terminal, or on the wrong profile */
    private void prompt(DeviceAuthorization authorization) {
        log.info("");
        log.info("  To authorize this machine, visit:");
        log.info("    " + authorization.getVerificationUri());
        log.info("  and enter code:  " + authorization.getUserCode());
        log.info("");
        log.info("  Waiting for approval ...");
    }
    /**
     * The session is exchanged for an api key immediately and then dropped: the key is what submissions send and
     * what the developer revokes, and a session token on disk would be a broader credential than we need.
     */
    private Credentials mintApiKey(OAuth20Service service, String sessionToken) throws Exception {
        JsonNode session = call(service, Verb.GET, "/api/auth/get-session", null, sessionToken);
        String organizationId = session.path("session").path("activeOrganizationId").asString("");
        if (StringUtils.isBlank(organizationId)) {
            throw new IOException("no active organization on the approved session — pick one on the approval page and run the login again");
        }

        String body = mapper.writeValueAsString(Map.of(
                "name", keyName(),
                "expiresIn", KEY_LIFETIME.toSeconds(),
                "metadata", Map.of("organizationId", organizationId, "cli", Boolean.TRUE)));

        JsonNode created = call(service, Verb.POST, "/api/auth/api-key/create", body, sessionToken);
        String key = created.path("key").asString("");
        if (StringUtils.isBlank(key)) {
            throw new IOException("the api key mint returned no key: " + created);
        }
        log.info("  authorized — key stored for organization " + organizationId);

        String expiresAt = StringUtils.EMPTY;
        if (created.path("expiresAt").isString()) {
            expiresAt = created.path("expiresAt").asString();
        }
        return new Credentials(key, organizationId, expiresAt);
    }
    private JsonNode call(OAuth20Service service, Verb verb, String path, String body, String bearer) throws Exception {
        OAuthRequest request = new OAuthRequest(verb, authUrl + path);
        request.addHeader("Authorization", "Bearer " + bearer);
        if (Objects.nonNull(body)) {
            request.addHeader("Content-Type", "application/json");
            request.setPayload(body);
        }

        try (Response response = service.execute(request)) {
            if (response.isSuccessful()) {
                return mapper.readTree(response.getBody());
            }
            throw new IOException(String.format("%s returned HTTP %d: %s", path, response.getCode(), response.getBody()));
        }
    }
    /**
     * dorkbox's Desktop owns the per-platform detail — AWT when the JVM is not headless, otherwise {@code open}, the
     * Windows shell handler, or gvfs/kde/xdg-open according to the desktop environment it finds — and raises
     * {@code IOException} when it has no strategy at all. That failure travels: a machine with no browser cannot be
     * authorised this way, and saying so is more use than polling for an approval nobody can give.
     *
     * <p>It launches asynchronously, so a launcher that starts and then fails (an agent with xdg-open but no display)
     * raises nothing here; the poll then runs until the device code expires.
     */
    protected void openBrowser(String uri) throws IOException {
        Desktop.browseURL(uri);
    }
    /**
     * How a submission finds its credential: an explicitly configured key, then whatever an earlier login left
     * behind, and only then the browser. Nothing here asks whether the run is interactive — a run that can open a
     * browser is one where somebody can approve, and a run that cannot fails with the same message either way.
     */
    public static String resolveApiKey(String configuredApiKey, String authUrl, Log log) throws MojoExecutionException {
        Optional<String> configured = Env.resolve(configuredApiKey);
        if (configured.isPresent()) {
            return configured.get();
        }
        /**
         * A key that was configured and did not resolve can only be an "env:VAR" naming a variable that is unset —
         * a misconfiguration, and not an invitation to open a browser. A pipeline that lost its secret has to fail
         * here rather than block on a poll loop nobody will approve.
         */
        if (StringUtils.isNotBlank(configuredApiKey)) {
            throw new MojoExecutionException("codiqo.apiKey is set to '" + configuredApiKey + "' but resolves to nothing — export that variable, or unset codiqo.apiKey to log in through the browser");
        }

        try {
            CredentialStore store = new CredentialStore(authUrl);
            Optional<String> stored = store.find();
            if (stored.isPresent()) {
                log.debug("using the key stored in " + store.file());
                return stored.get();
            }

            Credentials fresh = new BrowserLogin(authUrl, log).login();
            store.store(fresh);
            return fresh.getKey();
        } catch (Exception err) {
            /**
             * The advice belongs here rather than at the failure itself: whatever went wrong inside the login — no
             * browser on this machine, a denial, an expired code — the way past it is a configured key or a login run
             * somewhere with a desktop.
             */
            throw new MojoExecutionException("no codiqo.apiKey configured and the browser login failed: " + err.getMessage()
                    + " — set codiqo.apiKey, or run 'mvn io.codiqo:codiqo-maven-plugin:login' on a workstation and copy ~/.codiqo across", err);
        }
    }
    /**
     * The host name is what tells a developer which machine to revoke, so it is the part worth keeping: better-auth
     * rejects api key names over 32 characters and a fully qualified host name can spend that on its own. The domain
     * suffix goes first, and the truncation takes whatever is still too long.
     */
    private static String keyName() throws IOException {
        String host = StringUtils.substringBefore(InetAddress.getLocalHost().getHostName(), ".");
        return StringUtils.left("codiqo-cli@" + host, MAX_KEY_NAME_LENGTH);
    }
    private static String poll(OAuth20Service service, DeviceAuthorization authorization) throws Exception {
        try {
            OAuth2AccessToken session = service.pollAccessTokenDeviceAuthorizationGrant(authorization);
            return session.getAccessToken();
        } catch (OAuth2AccessTokenErrorResponse err) {
            throw new IOException(StringUtils.defaultIfBlank(err.getErrorDescription(), err.getMessage()), err);
        }
    }

    @RequiredArgsConstructor
    private static class BetterAuthApi extends DefaultApi20 {
        private final String authUrl;

        @Override
        public String getAccessTokenEndpoint() {
            return authUrl + "/api/auth/device/token";
        }
        @Override
        public String getDeviceAuthorizationEndpoint() {
            return authUrl + "/api/auth/device/code";
        }
        @Override
        protected String getAuthorizationBaseUrl() {
            return authUrl + "/cli";
        }
        @Override
        public OAuth20Service createService(
                String apiKey,
                String apiSecret,
                String callback,
                String defaultScope,
                String responseType,
                OutputStream debugStream,
                String userAgent,
                HttpClientConfig httpClientConfig,
                HttpClient httpClient) {
            return new BetterAuthDeviceGrant(this, apiKey, apiSecret, callback, defaultScope, responseType, debugStream, userAgent, httpClientConfig, httpClient);
        }
    }
    /**
     * RFC 8628 puts both device requests in a form-encoded body, and ScribeJava builds them that way. better-auth
     * refuses them: its router declares {@code allowedMediaTypes: ["application/json"]} and only a handful of
     * endpoints opt back into form encoding — its oidc-provider and mcp plugins do, the device-authorization plugin
     * does not. So the two request bodies are rewritten as json here, which leaves the poll loop, the error mapping
     * and the response extractors to the library and stays correct if better-auth ever accepts forms as well.
     */
    private static class BetterAuthDeviceGrant extends OAuth20Service {
        private final ObjectMapper mapper = new ObjectMapper();

        BetterAuthDeviceGrant(
                DefaultApi20 api,
                String apiKey,
                String apiSecret,
                String callback,
                String defaultScope,
                String responseType,
                OutputStream debugStream,
                String userAgent,
                HttpClientConfig httpClientConfig,
                HttpClient httpClient) {
            super(api, apiKey, apiSecret, callback, defaultScope, responseType, debugStream, userAgent, httpClientConfig, httpClient);
        }
        @Override
        protected OAuthRequest createDeviceAuthorizationCodesRequest(String scope) {
            return jsonRequest(getApi().getDeviceAuthorizationEndpoint(), Map.of("client_id", getApiKey()));
        }
        @Override
        protected OAuthRequest createAccessTokenDeviceAuthorizationGrantRequest(DeviceAuthorization authorization) {
            return jsonRequest(getApi().getAccessTokenEndpoint(), Map.of(
                    "grant_type", DEVICE_CODE_GRANT_TYPE,
                    "device_code", authorization.getDeviceCode(),
                    "client_id", getApiKey()));
        }
        private OAuthRequest jsonRequest(String endpoint, Map<String, String> body) {
            OAuthRequest toReturn = new OAuthRequest(Verb.POST, endpoint);
            toReturn.addHeader("Content-Type", "application/json");
            for (;;) {
                try {
                    toReturn.setPayload(mapper.writeValueAsString(body));
                    return toReturn;
                } catch (Exception err) {
                    ExceptionUtils.wrapAndThrow(err);
                }
            }
        }
    }
}
