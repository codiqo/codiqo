package io.codiqo.core.logging;

import org.slf4j.LoggerFactory;

import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;

public class SlfLogFactory implements LogFactory {
    @Override
    public Log getLogger(Class<?> clazz) {
        return new SlfMessageReporter(LoggerFactory.getLogger(clazz));
    }
}
