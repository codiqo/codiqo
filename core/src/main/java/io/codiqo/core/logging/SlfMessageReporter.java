package io.codiqo.core.logging;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;
import lombok.RequiredArgsConstructor;
import net.sourceforge.pmd.util.AssertionUtil;

@RequiredArgsConstructor
public class SlfMessageReporter implements Log {
    private final AtomicInteger numErrors = new AtomicInteger();
    private final Logger logger;

    @Override
    public boolean isLoggable(Level level) {
        switch (level) {
            case ERROR:
                return logger.isErrorEnabled();
            case WARN:
                return logger.isWarnEnabled();
            case INFO:
                return logger.isInfoEnabled();
            case DEBUG:
                return logger.isDebugEnabled();
            case TRACE:
                return logger.isTraceEnabled();
            default:
                throw AssertionUtil.shouldNotReachHere("Invalid log level: " + level);
        }
    }
    @Override
    public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        if (Objects.isNull(error)) {
            Objects.requireNonNull(message, "cannot call this method with null message and error");
            log(level, message, formatArgs);
            return;
        }
        if (level == Level.ERROR) {
            numErrors.incrementAndGet();
        }
        String fullMessage = ExceptionUtils.getRootCauseMessage(error);
        if (StringUtils.isNotEmpty(message)) {
            if (ArrayUtils.isEmpty(formatArgs)) {
                logImpl(level, message + ": " + fullMessage);
            } else {
                logImpl(level, String.format(message, formatArgs) + ": " + fullMessage);
            }
        }
        logImpl(level, fullMessage);
    }
    @Override
    public void log(Level level, String message, Object... formatArgs) {
        if (level == Level.ERROR) {
            numErrors.incrementAndGet();
        }
        if (ArrayUtils.isEmpty(formatArgs)) {
            logImpl(level, message);
        } else {
            logImpl(level, String.format(message, formatArgs));
        }
    }
    @Override
    public int numErrors() {
        return numErrors.get();
    }
    protected void logImpl(Level level, String message) {
        switch (level) {
            case ERROR:
                logger.error(message);
                break;
            case WARN:
                logger.warn(message);
                break;
            case INFO:
                logger.info(message);
                break;
            case DEBUG:
                logger.debug(message);
                break;
            case TRACE:
                logger.trace(message);
                break;
            default:
                throw AssertionUtil.shouldNotReachHere("Invalid log level: " + level);
        }
    }
}
