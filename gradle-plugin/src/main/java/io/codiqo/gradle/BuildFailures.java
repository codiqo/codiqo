package io.codiqo.gradle;

import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.tasks.testing.Test;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;

import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.submit.outcome.BuildFailureClassifier;
import lombok.experimental.UtilityClass;

/**
 * Which task failures make the analysed classes untrustworthy, so the commit has to be reported rather than scored, and
 * whose failure that is.
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

    public static Optional<BuildFailure> of(Collection<Task> tasks) {
        Map<String, Throwable> failedTasks = Maps.newLinkedHashMap();
        for (Task task : tasks) {
            if (countsAgainstTheBuild(task)) {
                failedTasks.put(task.getPath(), task.getState().getFailure());
            }
        }
        return classify(failedTasks);
    }
    /**
     * Each task's failure is classified whole, before the detail is cut to its limit: one Error Prone crash alone ran to
     * 25,000 characters, which would have cut a second failing task's crash, or its unresolved dependency, out of what
     * the classifier read. The classifier reads each failure's class names as well, which Maven's console prints and the
     * detail leaves out: a plugin that could not reach its download host throws an UnknownHostException whose message is
     * the host alone. The build is the environment's only when every failed task is: under --continue an unrelated
     * download failure would otherwise hide a compile error the author made.
     */
    @VisibleForTesting
    public static Optional<BuildFailure> classify(Map<String, Throwable> failedTasks) {
        if (MapUtils.isNotEmpty(failedTasks)) {
            List<String> details = Lists.newArrayList();
            List<String> classified = Lists.newArrayList();
            failedTasks.forEach((path, failure) -> {
                List<Throwable> chain = chain(failure);
                details.add(path + ": " + Joiner.on(StringUtils.LF).join(messages(chain)));
                classified.add(path + ": " + chain.stream().map(cause -> cause.getClass().getName() + ": " + StringUtils.defaultString(cause.getMessage())).collect(Collectors.joining(StringUtils.LF)));
            });

            AnalysisExcludeCategory category = AnalysisExcludeCategory.BUILD_FAILURE;
            if (classified.stream().map(BuildFailureClassifier::category).allMatch(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE::equals)) {
                category = AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE;
            }

            return Optional.of(new BuildFailure(
                    StringUtils.abbreviate(Joiner.on(StringUtils.LF).join(details), DETAIL_LIMIT),
                    category,
                    classified.stream().map(BuildFailureClassifier::compilerCrash).flatMap(Optional::stream).findFirst()));
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
     * the messages of the failure and its causes: a task's own message is a bare "Execution failed for task X" that
     * repeats the path, while the causes beneath name what went wrong
     */
    private static List<String> messages(List<Throwable> chain) {
        return chain.stream()
                .map(Throwable::getMessage)
                .filter(StringUtils::isNotBlank)
                .map(String::strip)
                .distinct()
                .toList();
    }
    /**
     * The failure and every cause beneath it, depth first. Gradle's failure to resolve a configuration holds one cause
     * per dependency it could not resolve, but its {@code getCause()} names only the first, so a second missing
     * dependency never reached the detail; {@link ResolveException#getCauses()} lists them all. A throwable already
     * collected is not walked again, so a cause chain that loops terminates.
     */
    private static List<Throwable> chain(Throwable failure) {
        Set<Throwable> toReturn = Sets.newLinkedHashSet();
        Deque<Throwable> pending = Queues.newArrayDeque(List.of(failure));
        while (CollectionUtils.isNotEmpty(pending)) {
            Throwable next = pending.pop();
            if (toReturn.add(next)) {
                List<? extends Throwable> causes = List.of();
                if (next instanceof ResolveException resolve) {
                    causes = resolve.getCauses();
                } else if (Objects.nonNull(next.getCause())) {
                    causes = List.of(next.getCause());
                }
                Lists.reverse(Lists.newArrayList(causes)).forEach(pending::push);
            }
        }
        return Lists.newArrayList(toReturn);
    }

    /** the failed build's detail, cut to its limit, whose failure it is, and the line that shows the compiler crashed */
    public static final class BuildFailure extends ImmutableTriple<String, AnalysisExcludeCategory, Optional<String>> {
        private static final long serialVersionUID = 1L;

        public BuildFailure(String detail, AnalysisExcludeCategory category, Optional<String> compilerCrash) {
            super(detail, category, compilerCrash);
        }
        public String getDetail() {
            return getLeft();
        }
        public AnalysisExcludeCategory getCategory() {
            return getMiddle();
        }
        public Optional<String> getCompilerCrash() {
            return getRight();
        }
    }
}
