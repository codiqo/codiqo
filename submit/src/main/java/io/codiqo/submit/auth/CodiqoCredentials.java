package io.codiqo.submit.auth;

import java.io.IOException;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;
import io.codiqo.util.Env;
import lombok.experimental.UtilityClass;

/**
 * How a build finds its credential, the same for Maven and Gradle: an explicitly configured key, then the login an
 * earlier run left behind, and only then the browser. Nothing here asks whether the run is interactive; a caller that
 * must never open a browser (a Gradle worker, a CI step) passes {@code allowBrowser = false}.
 */
@UtilityClass
public class CodiqoCredentials {
    public Optional<CodiqoCredential> resolve(String configuredApiKey, String authUrl, String resourceUrl, boolean allowBrowser, Log log)
            throws IOException {
        Optional<String> configured = Env.resolve(configuredApiKey);
        if (configured.isPresent()) {
            return Optional.of(new ApiKeyCredential(configured.get()));
        }
        /**
         * A key that was configured and did not resolve can only be an "env:VAR" naming a variable that is unset: a
         * misconfiguration, and not an invitation to open a browser. A pipeline that lost its secret has to fail here
         * rather than wait on an approval nobody will give.
         */
        if (StringUtils.isNotBlank(configuredApiKey)) {
            throw new IOException("codiqo.apiKey is set to '" + configuredApiKey + "' but resolves to nothing; export that variable, "
                    + "or unset codiqo.apiKey to log in through the browser");
        }

        CredentialStore store = new CredentialStore(authUrl);
        OAuthClient client = new OAuthClient(authUrl, resourceUrl);
        Optional<OAuthTokens> stored = store.findOAuth();
        if (stored.isPresent()) {
            OAuthCredential credential = new OAuthCredential(client, store, stored.get());
            if (isUsable(credential, log)) {
                log.log(Level.DEBUG, "using the browser login stored in %s", store.file());
                return Optional.of(credential);
            }
        }

        Optional<String> legacyKey = store.findApiKey();
        if (legacyKey.isPresent()) {
            log.log(Level.DEBUG, "using the key stored in %s", store.file());
            return Optional.of(new ApiKeyCredential(legacyKey.get()));
        }

        if (allowBrowser) {
            return Optional.of(login(authUrl, resourceUrl, log));
        }
        return Optional.empty();
    }
    /** a fresh login, stored for the runs after this one */
    public CodiqoCredential login(String authUrl, String resourceUrl, Log log) throws IOException {
        CredentialStore store = new CredentialStore(authUrl);
        OAuthClient client = new OAuthClient(authUrl, resourceUrl);

        OAuthTokens tokens = new BrowserLogin(client, log).login();
        store.store(tokens);
        log.info("  login stored in " + store.file());
        return new OAuthCredential(client, store, tokens);
    }
    /**
     * Refreshed up front, so a login that has ended (revoked, or its refresh token expired) sends the developer to
     * the browser now rather than failing the submission at the end of the build.
     */
    private static boolean isUsable(OAuthCredential credential, Log log) throws IOException {
        try {
            credential.fresh();
            return true;
        } catch (OAuthException err) {
            if (err.endsLogin()) {
                log.info("the stored browser login has ended (%s: %s); logging in again", err.getError(), err.getMessage());
                return false;
            }
            throw err;
        } catch (IOException err) {
            /** no answer at all is no verdict on the login: the refresh is retried with the first call, like any request */
            log.warn("could not refresh the stored browser login now (%s); it is retried with the first call", err.getMessage());
            return true;
        }
    }
}
