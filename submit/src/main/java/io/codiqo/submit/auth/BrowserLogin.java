package io.codiqo.submit.auth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

import dorkbox.desktop.Desktop;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;

/**
 * Authorises this machine through the browser with the authorization code grant and PKCE, the flow MCP clients use
 * against the same server: the developer signs in, picks the organization and approves, and the token that comes
 * back is bound to that organization and to the scopes below.
 *
 * <p>The scopes are what the build plugins do: read Codiqo, submit analyses, and run local reviews through the
 * backend's LLM proxy, which spends the organization's budget and is why the consent page names it.
 */
public class BrowserLogin {
    public static final String CLIENT_NAME = "Codiqo build plugin";
    public static final List<String> SCOPES = List.of("openid", "offline_access", "codiqo:read", "codiqo:people", "codiqo:llm", "codiqo:submit");

    /** RFC 7636 allows 43 to 128 unreserved characters; 43 alphanumerics carry about 256 bits */
    private static final int VERIFIER_LENGTH = 43;

    private final OAuthClient client;
    private final Log log;

    public BrowserLogin(OAuthClient client, Log log) {
        this.client = Objects.requireNonNull(client);
        this.log = Objects.requireNonNull(log);
    }
    /**
     * The browser is opened before anything is printed, so a machine that has none fails there rather than sending a
     * developer to a page and waiting ten minutes for an answer nobody can give.
     */
    public OAuthTokens login() throws IOException {
        try (LoopbackReceiver receiver = LoopbackReceiver.start()) {
            String redirectUri = receiver.redirectUri();
            String clientId = client.register(CLIENT_NAME, redirectUri, SCOPES);

            String verifier = random();
            String state = random();
            URI authorization = client.authorizationUrl(clientId, redirectUri, SCOPES, state, challenge(verifier));
            openBrowser(authorization);
            prompt(authorization);

            Map<String, String> answer = receiver.await(RunArgs.OAUTH_APPROVAL_TIMEOUT);
            if (answer.containsKey("code")) {
                /** A state mismatch is a callback another page sent: the browser came back, but not from the login this process started. */
                if (state.equals(answer.get("state"))) {
                    OAuthTokens toReturn = client.exchange(clientId, answer.get("code"), redirectUri, verifier);
                    log.info("  authorized");
                    return toReturn;
                }
                throw new IOException("the browser answered for another login (state mismatch); run the login again");
            }
            throw new OAuthException(StringUtils.defaultIfBlank(answer.get("error"), "access_denied"),
                    StringUtils.defaultIfBlank(answer.get("error_description"), "the login was not approved"));
        }
    }
    /** the URL is printed as well: the browser may open behind the terminal, or on the wrong profile */
    private void prompt(URI authorization) {
        log.info(StringUtils.EMPTY);
        log.info("  To authorize this machine, approve Codiqo in the browser that just opened.");
        log.info("  If none opened, visit:");
        log.info("    " + authorization);
        log.info(StringUtils.EMPTY);
        log.info("  Waiting for approval ...");
    }
    /**
     * dorkbox's Desktop owns the per-platform detail and raises {@code IOException} when it has no way to open a
     * browser at all. That failure travels: a machine with no browser cannot be authorised this way.
     */
    protected void openBrowser(URI uri) throws IOException {
        Desktop.browseURL(uri.toString());
    }
    private static String random() {
        return RandomStringUtils.secure().nextAlphanumeric(VERIFIER_LENGTH);
    }
    private static String challenge(String verifier) {
        return BaseEncoding.base64Url().omitPadding().encode(Hashing.sha256().hashString(verifier, StandardCharsets.US_ASCII).asBytes());
    }
}
