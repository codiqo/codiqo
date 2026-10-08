package io.codiqo.submit.auth;

import java.io.IOException;
import java.util.Set;

/**
 * An OAuth error answer (RFC 6749 section 5.2). The code is kept apart from the message because it decides what
 * happens next: {@code invalid_grant} on a refresh means the login is over and the browser has to be asked again.
 */
public class OAuthException extends IOException {
    public static final String INVALID_GRANT = "invalid_grant";
    /**
     * Answers a new browser login fixes: the grant ended, the client this login registered is gone, or the resource it
     * was issued for changed (another {@code resourceUrl}).
     */
    private static final Set<String> ENDS_LOGIN = Set.of(INVALID_GRANT, "invalid_client", "unauthorized_client", "invalid_target");

    private final String error;

    public OAuthException(String error, String description) {
        super(description);
        this.error = error;
    }
    public String getError() {
        return error;
    }
    public boolean isInvalidGrant() {
        return INVALID_GRANT.equals(getError());
    }
    public boolean endsLogin() {
        return ENDS_LOGIN.contains(getError());
    }
}
