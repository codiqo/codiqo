package io.codiqo.maven.logging;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.event.Level;

import lombok.RequiredArgsConstructor;
import net.sourceforge.pmd.util.AssertionUtil;

@RequiredArgsConstructor
public class MavenMessageReporter implements io.codiqo.api.logging.Log {
    private final AtomicInteger numErrors = new AtomicInteger();
    private final org.apache.maven.plugin.logging.Log logger;

    @Override
    public boolean isLoggable(Level level) {
        switch (level) {
            case ERROR:
                return logger.isErrorEnabled();
            case WARN:
                return logger.isWarnEnabled();
            case INFO:
                return logger.isInfoEnabled();
            case TRACE:
            case DEBUG:
                return logger.isDebugEnabled();
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
            case TRACE:
            case DEBUG:
                logger.debug(message);
                break;
            default:
                throw AssertionUtil.shouldNotReachHere("Invalid log level: " + level);
        }
    }
}
