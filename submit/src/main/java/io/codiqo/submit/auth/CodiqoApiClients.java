package io.codiqo.submit.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import org.apache.commons.lang3.Strings;

import io.codiqo.client.ApiClient;
import lombok.experimental.UtilityClass;

/** The generated Codiqo client, authorised per request so a browser login's token is refreshed as it ages. */
@UtilityClass
public class CodiqoApiClients {
    public ApiClient newApiClient(String apiUrl, CodiqoCredential credential, long connectTimeoutSeconds, long readTimeoutSeconds) {
        ApiClient toReturn = new ApiClient();
        toReturn.updateBaseUri(Strings.CS.removeEnd(apiUrl, "/"));
        toReturn.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        toReturn.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        /**
         * The interceptor cannot throw a checked exception, so a failed token refresh leaves it as an
         * UncheckedIOException, outside the generated client's own IOException handling. ApiRetry recognises it, retries
         * a transient failure (the auth server briefly down) and reports a definitive one as the call's ApiException.
         */
        toReturn.setRequestInterceptor(builder -> {
            try {
                credential.accept(builder::setHeader);
            } catch (IOException err) {
                throw new UncheckedIOException("could not authorize the call to " + apiUrl + ": " + err.getMessage(), err);
            }
        });
        return toReturn;
    }
}
