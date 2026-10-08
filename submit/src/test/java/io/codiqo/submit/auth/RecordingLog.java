package io.codiqo.submit.auth;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.event.Level;

import io.codiqo.api.logging.Log;

final class RecordingLog implements Log {
    final List<String> lines = new CopyOnWriteArrayList<>();

    @Override
    public boolean isLoggable(Level level) {
        return true;
    }
    @Override
    public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        lines.add(level + " " + (ArrayUtils.isEmpty(formatArgs) ? message : String.format(message, formatArgs)));
    }
    @Override
    public int numErrors() {
        return 0;
    }
}
