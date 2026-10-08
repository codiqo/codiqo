package io.codiqo.gradle;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.gradle.api.logging.Logger;
import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;

/**
 * Info at Gradle's lifecycle level, which a build shows by default: the browser login prints the URL to visit there,
 * and Gradle hides plain info unless run with {@code --info}.
 */
public final class GradleLifecycleLog implements Log {
    private final AtomicInteger numErrors = new AtomicInteger();
    private final Logger logger;

    public GradleLifecycleLog(Logger logger) {
        this.logger = Objects.requireNonNull(logger);
    }
    @Override
    public boolean isLoggable(Level level) {
        return level != Level.TRACE;
    }
    @Override
    public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        String text = ArrayUtils.isEmpty(formatArgs) ? message : String.format(message, formatArgs);
        if (Objects.nonNull(error)) {
            text = text + ": " + ExceptionUtils.getRootCauseMessage(error);
        }
        switch (level) {
            case ERROR -> {
                numErrors.incrementAndGet();
                logger.error(text);
            }
            case WARN -> logger.warn(text);
            case INFO -> logger.lifecycle(text);
            case DEBUG, TRACE -> logger.debug(text);
            default -> throw new IllegalArgumentException("unknown log level: " + level);
        }
    }
    @Override
    public int numErrors() {
        return numErrors.get();
    }
}
