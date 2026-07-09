package io.codiqo.gradle.logging;

import org.gradle.api.logging.Logger;

import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GradleLogFactory implements LogFactory {
    private final Logger logger;

    @Override
    public Log getLogger(Class<?> clazz) {
        return new GradleMessageReporter(logger);
    }
}
