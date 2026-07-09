package io.codiqo.llm;

import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;

public class NoopLog implements Log {
    public static final Log INSTANCE = new NoopLog();

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
