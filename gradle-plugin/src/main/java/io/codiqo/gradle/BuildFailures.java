package io.codiqo.gradle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;

import lombok.experimental.UtilityClass;

/**
 * Which task failures make the analysed classes untrustworthy, so the commit has to be reported rather than scored.
 *
 * <p>Read straight from {@link org.gradle.api.tasks.TaskState}, not from a build-event listener: Gradle drains its
 * listener queue no earlier than {@code buildFinished}, after this analysis has run, so a listener could report an
 * empty failure set for a build that had already failed. Task state is set as each task completes, and the analysis
 * task is ordered after all of them.
 *
 * <p>Test-task failures are excluded. codiqo sets {@code ignoreFailures} on every Test task, so the only way one still
 * fails is codiqo's own task timeout firing — which says nothing about whether the compiled output can be trusted.
 * A test task that times out costs coverage, and coverage is measured separately.
 */
@UtilityClass
public class BuildFailures {
    private static final int DETAIL_LIMIT = 4096;

    public static Optional<String> detail(Collection<Task> tasks) {
        List<String> failures = new ArrayList<>();
        for (Task task : tasks) {
            if (countsAgainstTheBuild(task)) {
                failures.add(task.getPath() + ": " + messageChain(task.getState().getFailure()));
            }
        }
        if (CollectionUtils.isNotEmpty(failures)) {
            return Optional.of(StringUtils.abbreviate(StringUtils.join(failures, System.lineSeparator()), DETAIL_LIMIT));
        }
        return Optional.empty();
    }
    private static boolean countsAgainstTheBuild(Task task) {
        if (task instanceof Test) {
            return false;
        }
        return Objects.nonNull(task.getState().getFailure());
    }
    /**
     * the failure's message and its causes': a task's own message is a bare "Execution failed for task X" that repeats
     * the path, while the cause beneath names what went wrong. {@code getThrowableList} stops at a repeated throwable,
     * so a self-referential cause chain terminates rather than recursing.
     */
    private static String messageChain(Throwable failure) {
        return ExceptionUtils.getThrowableList(failure).stream()
                .map(Throwable::getMessage)
                .filter(StringUtils::isNotBlank)
                .map(String::strip)
                .distinct()
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
