package io.codiqo.submit.auth;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

import io.codiqo.api.RunArgs;
import io.codiqo.util.RequestAuthorizer;
import io.netty.handler.codec.http.HttpHeaderNames;

/**
 * The access token of a browser login, refreshed shortly before it expires. Access tokens live minutes (the backend
 * verifies them offline, so that is how long a removed member keeps access), while a build or a review can run for an
 * hour, so the refresh happens per request rather than once at startup.
 *
 * <p>Several holders may share one login: the review relay and the submission in one Maven run, the Gradle daemon and
 * its workers, or two builds at once. The server rotates the refresh token and accepts each one once, so a refresh runs
 * under the store's lock and starts from what is stored: when another holder refreshed first, its tokens are adopted
 * instead of spending a refresh token that is already gone.
 */
public final class OAuthCredential implements CodiqoCredential {
    private final OAuthClient client;
    private final CredentialStore store;
    private OAuthTokens tokens;

    public OAuthCredential(OAuthClient client, CredentialStore store, OAuthTokens tokens) {
        this.client = Objects.requireNonNull(client);
        this.store = Objects.requireNonNull(store);
        this.tokens = Objects.requireNonNull(tokens);
    }
    @Override
    public void accept(BiConsumer<String, String> header) throws IOException {
        header.accept(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(fresh().getAccessToken()));
    }
    @Override
    public String describe() {
        return "browser login";
    }
    /** the tokens to send now, refreshed first when they are about to expire */
    public synchronized OAuthTokens fresh() throws IOException {
        if (tokens.expiresWithin(RunArgs.CREDENTIAL_EXPIRY_HEADROOM)) {
            tokens = store.locked(this::refreshed);
        }
        return tokens;
    }
    /** called under the store's lock, so what is stored is the newest login any holder has */
    private OAuthTokens refreshed() throws IOException {
        /**
         * another holder's newer login is adopted only when it is this login: a login made since for another
         * organization registered another client, and its tokens answer for that organization. Every holder starts
         * from a stored login, so none on disk means the developer logged out during the run: it is not written back.
         */
        Optional<OAuthTokens> stored = store.findOAuth();
        boolean ours = stored.map(login -> Objects.equals(login.getClientId(), tokens.getClientId())).orElse(false);
        OAuthTokens current = ours ? stored.orElse(tokens) : tokens;
        if (current.expiresWithin(RunArgs.CREDENTIAL_EXPIRY_HEADROOM)) {
            OAuthTokens toReturn = client.refresh(current);
            /** kept before it is stored: the refresh token rotated, so losing the new one to a failed write would end the login */
            tokens = toReturn;
            /** the newer login stays on disk; this one lives on in memory for the rest of the run */
            if (ours) {
                store.store(toReturn);
            }
            return toReturn;
        }
        return current;
    }
}
