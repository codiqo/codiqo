package io.codiqo.maven;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.Log;

import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeException;
import dev.failsafe.RetryPolicy;
import dev.failsafe.function.CheckedSupplier;
import io.codiqo.client.ApiException;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ApiRetry {
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofMinutes(1);
    private static final Duration MAX_TOTAL_DURATION = Duration.ofMinutes(15);
    private static final double JITTER_FACTOR = 0.3;

    public static <T> T call(Log log, String operation, String apiUrl, CheckedSupplier<T> supplier) throws ApiException {
        RetryPolicy<T> policy = RetryPolicy.<T> builder()
                .handleIf(ApiRetry::isRetryable)
                .withBackoff(INITIAL_DELAY, MAX_DELAY)
                .withJitter(JITTER_FACTOR)
                .withMaxAttempts(MAX_ATTEMPTS)
                .withMaxDuration(MAX_TOTAL_DURATION)
                .onRetryScheduled(event -> log.warn(operation + " failed " + event.getAttemptCount() + "/" + MAX_ATTEMPTS
                        + " (" + apiUrl + "): " + summarize(event.getLastException())
                        + "; retry in " + formatDelay(event.getDelay())))
                .onRetriesExceeded(event -> log.error(operation + " gave up after " + MAX_ATTEMPTS + " attempts ("
                        + apiUrl + "): " + summarize(event.getException())))
                .build();
        try {
            return Failsafe.with(policy).get(supplier);
        } catch (FailsafeException err) {
            if (err.getCause() instanceof ApiException) {
                throw (ApiException) err.getCause();
            }
            throw err;
        }
    }
    private static boolean isRetryable(Throwable err) {
        if (err instanceof IOException) {
            return true;
        }
        if (err instanceof ApiException) {
            int code = ((ApiException) err).getCode();
            return code == 0 || code == 429 || code >= 500;
        }
        return false;
    }
    private static String summarize(Throwable err) {
        if (err instanceof ApiException && ((ApiException) err).getCode() > 0) {
            return "HTTP " + ((ApiException) err).getCode();
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
        if (millis < 1000) {
            return millis + "ms";
        }
        return String.format("%.1fs", millis / 1000.0);
    }
}
