package io.codiqo.api.logging;

public interface LogFactory {
    Log getLogger(Class<?> clazz);
}
