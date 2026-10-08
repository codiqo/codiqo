package io.codiqo.util;

import java.io.IOException;
import java.util.function.BiConsumer;

import org.apache.commons.lang3.StringUtils;

/**
 * Authenticates an outgoing request by handing its header to the caller, who sets it on whatever request it is
 * building. The value is produced as the request is sent, so a token that ages during a long run is refreshed rather
 * than read once and sent after it lapsed.
 */
@FunctionalInterface
public interface RequestAuthorizer {
    String BEARER = "Bearer";

    void accept(BiConsumer<String, String> header) throws IOException;

    static String bearer(String token) {
        return BEARER + StringUtils.SPACE + token;
    }
}
