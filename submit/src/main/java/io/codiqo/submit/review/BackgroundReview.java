package io.codiqo.submit.review;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A local review running next to the analysis of the same commit. It needs only git and the agents, so it is started
 * before the build and joined just before the submission: a review that takes as long as the build adds no time.
 *
 * <p>Its outcome is the caller's to read: a review that failed, ran out of time or was waited for by a run being stopped
 * comes back as the future's own failure, which the caller settles into the review's verdict. Closing cancels a review
 * that is still running; the interrupt makes the reviewer stop its OpenCode server and delete the server's home, which
 * a review abandoned without the interrupt would leave running after the build exits.
 */
public final class BackgroundReview<T> implements AutoCloseable {
    private static final Duration JOIN_ON_CLOSE = Duration.ofSeconds(30);

    private final FutureTask<T> task;
    private final Thread thread;

    private BackgroundReview(FutureTask<T> task, Thread thread) {
        this.task = task;
        this.thread = thread;
    }
    public T await(Duration timeout) throws ExecutionException, TimeoutException, InterruptedException {
        return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override
    public void close() throws InterruptedException {
        if (task.cancel(true)) {
            thread.join(JOIN_ON_CLOSE.toMillis());
        }
    }
    public static <T> BackgroundReview<T> start(Callable<T> review) {
        FutureTask<T> task = new FutureTask<>(review);
        Thread thread = new Thread(task, "codiqo-local-review");
        thread.setDaemon(true);
        thread.start();
        return new BackgroundReview<>(task, thread);
    }
}
