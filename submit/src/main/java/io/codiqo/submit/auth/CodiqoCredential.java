package io.codiqo.submit.auth;

import io.codiqo.util.RequestAuthorizer;

/**
 * What every call to Codiqo authenticates with: a Codiqo API key, or the OAuth access token of a browser login. The
 * request is authorised as it is sent, so a token that expires during a long run is refreshed rather than failing the
 * call that happens to come after its expiry.
 */
public interface CodiqoCredential extends RequestAuthorizer {
    String describe();
}
