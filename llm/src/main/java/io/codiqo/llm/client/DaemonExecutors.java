package io.codiqo.llm.client;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.experimental.UtilityClass;

@UtilityClass
public class DaemonExecutors {
    public ExecutorService newCachedDaemonPool(String namePrefix) {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, BigDecimal.ONE.intValue(), TimeUnit.MINUTES, new SynchronousQueue<>(), threadFactory);
    }
}
