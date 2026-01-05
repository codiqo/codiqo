package io.codiqo.maven.logging;

import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MavenLogFactory implements LogFactory {
    private final org.apache.maven.plugin.logging.Log logger;

    @Override
    public Log getLogger(Class<?> clazz) {
        return new MavenMessageReporter(logger);
    }
}
