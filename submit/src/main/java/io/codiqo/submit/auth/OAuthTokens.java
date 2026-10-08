package io.codiqo.submit.auth;

import java.time.Duration;
import java.time.Instant;

import lombok.Value;

/**
 * What a browser login leaves behind. The client is registered per login, so its id travels with the tokens it was
 * issued: a refresh must name the client that holds the refresh token.
 */
@Value
public class OAuthTokens {
    String clientId;
    String accessToken;
    Instant accessTokenExpiresAt;
    String refreshToken;

    public boolean expiresWithin(Duration headroom) {
        return getAccessTokenExpiresAt().minus(headroom).isBefore(Instant.now());
    }
}
