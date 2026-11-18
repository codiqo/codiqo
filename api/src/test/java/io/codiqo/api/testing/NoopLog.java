package io.codiqo.api.testing;

import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;

final class NoopLog implements Log {
    @Override
    public boolean isLoggable(Level level) {
        return false;
    }
    @Override
    public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
    }
    @Override
    public void log(Level level, String message, Object... formatArgs) {
    }
    @Override
    public int numErrors() {
        return 0;
    }
}
