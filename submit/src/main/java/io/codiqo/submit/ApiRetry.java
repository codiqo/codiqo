package io.codiqo.submit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;

import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeException;
import dev.failsafe.RetryPolicy;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.submit.auth.OAuthException;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ApiRetry {
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofMinutes(1);
    private static final Duration MAX_TOTAL_DURATION = Duration.ofMinutes(15);
    private static final double JITTER_FACTOR = 0.3;

    private static final int NO_HTTP_STATUS = 0;

    /**
     * takes a {@link Callable} rather than Failsafe's own CheckedSupplier: the supplier type is part of this
     * signature, so exposing the library's would force every caller's module to declare a Failsafe dependency for a
     * lambda's target type alone.
     */
    public static <T> T call(Log log, String operation, String apiUrl, Callable<T> supplier) throws ApiException {
        RetryPolicy<T> policy = RetryPolicy.<T> builder()
                .handleIf(ApiRetry::isRetryable)
                .withBackoff(INITIAL_DELAY, MAX_DELAY)
                .withJitter(JITTER_FACTOR)
                .withMaxAttempts(MAX_ATTEMPTS)
                .withMaxDuration(MAX_TOTAL_DURATION)
                .onRetryScheduled(event -> log.warn(operation + " failed " + event.getAttemptCount() + "/" + MAX_ATTEMPTS + " (" + apiUrl + "): " + summarize(event.getLastException()) + "; retry in " + formatDelay(event.getDelay())))
                .onRetriesExceeded(event -> log.error(operation + " gave up after " + MAX_ATTEMPTS + " attempts (" + apiUrl + "): " + summarize(event.getException())))
                .build();
        try {
            return Failsafe.with(policy).get(supplier::call);
        } catch (UncheckedIOException err) {
            throw new ApiException(err.getMessage(), err.getCause(), NO_HTTP_STATUS, null);
        } catch (FailsafeException err) {
            if (err.getCause() instanceof ApiException apiErr) {
                throw apiErr;
            }
            throw err;
        }
    }
    private static boolean isRetryable(Throwable err) {
        if (err instanceof IOException) {
            return true;
        }
        /**
         * A credential that could not be refreshed, thrown from the request interceptor (CodiqoApiClients). A network
         * failure on the way to the auth server is as transient as one on the way to the API; an OAuth error answer is
         * the server's decision (the login has ended) and retrying it only delays the failure.
         */
        if (err instanceof UncheckedIOException unchecked) {
            return BooleanUtils.negate(unchecked.getCause() instanceof OAuthException);
        }
        if (err instanceof ApiException apiErr) {
            return BooleanUtils.or(new boolean[]{
                    apiErr.getCode() == NO_HTTP_STATUS,
                    apiErr.getCode() == HttpResponseStatus.TOO_MANY_REQUESTS.code(),
                    HttpStatusClass.valueOf(apiErr.getCode()) == HttpStatusClass.SERVER_ERROR});
        }
        return false;
    }
    private static String summarize(Throwable err) {
        if (err instanceof ApiException apiErr && apiErr.getCode() > NO_HTTP_STATUS) {
            return "HTTP " + apiErr.getCode();
        }

        Throwable cause = err;
        if (cause instanceof ApiException && Objects.nonNull(cause.getCause())) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (StringUtils.isBlank(message)) {
            return cause.getClass().getSimpleName();
        }
        return cause.getClass().getSimpleName() + ": " + message;
    }
    private static String formatDelay(Duration delay) {
        long millis = delay.toMillis();
        if (millis < DateUtils.MILLIS_PER_SECOND) {
            return millis + "ms";
        }
        return String.format(Locale.ROOT, "%.1fs", (double) millis / DateUtils.MILLIS_PER_SECOND);
    }
}
