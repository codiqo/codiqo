package io.codiqo.maven;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import lombok.experimental.UtilityClass;

/**
 * Stops a forked build together with everything it started. The invoker's own timeout destroys only the shell it
 * launched: the forked Maven JVM and its test JVMs are re-parented and carry on, writing into the checkout. Observed on
 * Jetty: the analysis indexed a tree surefire was still writing to, the checkout could not be deleted
 * ({@code DirectoryNotEmptyException}), and the leftovers lived until the runner reaped them at the end of the job. So
 * the deadline is enforced here instead, while the tree is still connected to this JVM and can be walked.
 *
 * <p>Only processes that did not exist before the fork are touched, so nothing the host started for itself is.
 */
@UtilityClass
public class ForkProcessTree {
    public Set<Long> snapshot() {
        return ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
    }
    /**
     * @param before the descendants recorded by {@link #snapshot()} ahead of the fork
     * @return the number of processes stopped
     */
    public int stop(Set<Long> before, Duration grace) throws InterruptedException {
        List<ProcessHandle> started = ProcessHandle.current().descendants()
                .filter(process -> !before.contains(process.pid()))
                .toList();
        started.forEach(ProcessHandle::destroyForcibly);
        // the caller reads and deletes the checkout next, so wait until nothing is left to write to it
        CompletableFuture<?>[] exits = started.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(exits).get(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException err) {
            // a process that outlives SIGKILL for longer than the grace is beyond what waiting can fix
        }
        return started.size();
    }
}
