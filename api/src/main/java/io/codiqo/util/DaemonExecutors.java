package io.codiqo.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import lombok.experimental.UtilityClass;

@UtilityClass
public class DaemonExecutors {
    private static final int KEEP_ALIVE_MINUTES = 1;

    public ExecutorService newCachedDaemonPool(String namePrefix) {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES, new SynchronousQueue<>(),
                new ThreadFactoryBuilder().setNameFormat(namePrefix + "-%d").setDaemon(true).build());
    }
}
