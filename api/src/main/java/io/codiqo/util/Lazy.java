package io.codiqo.util;

import java.util.function.Supplier;

import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.commons.lang3.exception.ExceptionUtils;

public final class Lazy<T> implements Supplier<T> {
    private final ConcurrentInitializer<T> initializer;

    private Lazy(Supplier<T> delegate) {
        this.initializer = LazyInitializer.<T>builder().setInitializer(delegate::get).get();
    }
    public static <T> Lazy<T> of(Supplier<T> delegate) {
        return new Lazy<>(delegate);
    }
    @Override
    public T get() {
        for (;;) {
            try {
                return initializer.get();
            } catch (ConcurrentException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
}
