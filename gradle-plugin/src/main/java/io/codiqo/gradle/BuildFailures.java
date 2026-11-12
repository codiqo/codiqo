package io.codiqo.gradle;

import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Pair;
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
 * whose failure that is. A classpath of the main or test source set that the model collector could not resolve counts as
 * such a failure too, whether or not a task that needs it ran.
 *
 * <p>Read straight from {@link org.gradle.api.tasks.TaskState}, not from a build-event listener: Gradle drains its
 * listener queue no earlier than {@code buildFinished}, after this analysis has run, so a listener could report an
 * empty failure set for a build that had already failed. Task state is set as each task completes, and the analysis
 * task is ordered after all of them.
 *
 * <p>Test-task failures are excluded. codiqo sets {@code ignoreFailures} on every Test task, so one still fails on
 * codiqo's own task timeout firing, which says nothing about whether the compiled output can be trusted (a test task that
 * times out costs coverage, and coverage is measured separately), or on a classpath that does not resolve: the
 * collector reports the test source set's, while another Test task's (an integrationTest) costs that task's coverage.
 */
@UtilityClass
public class BuildFailures {
    private static final int DETAIL_LIMIT = 4096;
    /**
     * Gradle's exceptions for a module or artifact in none of the repositories it searched. Within one build Gradle names
     * the repositories only the first time: a task that needs a module the build already failed to find gets "Could not
     * find" alone. Such a report is the environment's when the same module was searched in a remote repository earlier
     * in the build; a module missing from the author's own flatDir or file repository never names a URL and stays the
     * author's. Read from the failure's own class, never from its text, where a compile error may name a class of the
     * author's so called.
     */
    private static final Set<String> NOT_FOUND = Set.of("ModuleVersionNotFoundException", "ArtifactNotFoundException");
    private static final String NOT_FOUND_MESSAGE = "Could not find";
    private static final Pattern REMOTE_URL = Pattern.compile("https?://\\S+");

    /** @param unresolvedClasspaths the main and test classpaths the model collector could not resolve, by configuration path */
    public static Optional<BuildFailure> of(Collection<Task> tasks, Map<String, Throwable> unresolvedClasspaths) {
        Map<String, Throwable> failedTasks = Maps.newLinkedHashMap();
        for (Task task : tasks) {
            if (countsAgainstTheBuild(task)) {
                failedTasks.put(task.getPath(), task.getState().getFailure());
            }
        }
        return classify(failedTasks, unresolvedClasspaths);
    }
    /**
     * Each failure is classified whole, before the detail is cut to its limit: one Error Prone crash alone ran to 25,000
     * characters, which would have cut a second failing task's crash, or its unresolved dependency, out of what the
     * classifier read. The classifier reads each failure's class names as well, which Maven's console prints and the
     * detail leaves out: a plugin that could not reach its download host throws an UnknownHostException whose message is
     * the host alone.
     *
     * <p>The build is the environment's only when every failure is, and a failure that bundles several, Gradle's resolve
     * failure with one cause per dependency it could not resolve, only when each of them is: under --continue an unrelated
     * download failure would otherwise hide an error the author made, and a missing module would hide a variant of the
     * author's that does not match in the same configuration.
     *
     * <p>The failed tasks come first: a missing module is reported once per classpath of every module that needs it, and
     * listed first those reports would fill the detail and cut away the task failure that decided the category.
     */
    @VisibleForTesting
    public static Optional<BuildFailure> classify(Map<String, Throwable> failedTasks, Map<String, Throwable> unresolvedClasspaths) {
        List<Pair<String, Throwable>> failures = Lists.newArrayList();
        failedTasks.forEach((path, failure) -> failures.add(Pair.of(path, failure)));
        unresolvedClasspaths.forEach((path, failure) -> failures.add(Pair.of(path, failure)));
        if (CollectionUtils.isNotEmpty(failures)) {
            List<String> details = Lists.newArrayList();
            List<String> classified = Lists.newArrayList();
            Set<String> searchedRemotely = Sets.newHashSet();
            for (Pair<String, Throwable> failure : failures) {
                List<Throwable> chain = chain(failure.getValue());
                details.add(failure.getKey() + ": " + Joiner.on(StringUtils.LF).join(messages(chain)));
                classified.add(failure.getKey() + ": " + text(chain));
                chain.stream()
                        .filter(BuildFailures::isNotFound)
                        .filter(cause -> REMOTE_URL.matcher(cause.getMessage()).find())
                        .map(BuildFailures::notFoundModule)
                        .forEach(searchedRemotely::add);
            }

            AnalysisExcludeCategory category = AnalysisExcludeCategory.BUILD_FAILURE;
            if (failures.stream().flatMap(failure -> parts(failure.getValue()).stream()).allMatch(part -> isEnvironments(part, searchedRemotely))) {
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
    private static boolean isEnvironments(List<Throwable> part, Set<String> searchedRemotely) {
        return BooleanUtils.or(new boolean[] {
                AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE == BuildFailureClassifier.category(text(part)),
                part.stream().filter(BuildFailures::isNotFound).map(BuildFailures::notFoundModule).anyMatch(searchedRemotely::contains) });
    }
    private static boolean isNotFound(Throwable cause) {
        return BooleanUtils.and(new boolean[] { NOT_FOUND.contains(cause.getClass().getSimpleName()), Strings.CS.startsWith(cause.getMessage(), NOT_FOUND_MESSAGE) });
    }
    /** the module a not-found report names, its first line, which a report from the build's cache repeats */
    private static String notFoundModule(Throwable cause) {
        return StringUtils.substringBefore(cause.getMessage(), StringUtils.LF);
    }
    /** the failure's chain, and each cause's class name beside its message, as Maven's console prints them */
    private static String text(List<Throwable> chain) {
        return chain.stream().map(cause -> cause.getClass().getName() + ": " + StringUtils.defaultString(cause.getMessage())).collect(Collectors.joining(StringUtils.LF));
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
     * The failure split at every failure that bundles several causes: each part is the path down to a bundle and one of its
     * causes, split again where that cause bundles several of its own. A failure that bundles none is one part.
     */
    private static List<List<Throwable>> parts(Throwable failure) {
        List<Throwable> chain = chain(failure);
        Optional<Throwable> bundle = chain.stream().filter(cause -> causes(cause).size() > 1).findFirst();
        if (bundle.isPresent()) {
            List<Throwable> path = chain.subList(0, chain.indexOf(bundle.get()) + 1);
            return causes(bundle.get()).stream()
                    .flatMap(cause -> parts(cause).stream())
                    .map(part -> Stream.concat(path.stream(), part.stream()).toList())
                    .toList();
        }
        return List.of(chain);
    }
    /**
     * The failure and every cause beneath it, depth first. Gradle's failure to resolve a configuration holds one cause
     * per dependency it could not resolve, but its {@code getCause()} names only the first, so a second missing
     * dependency never reached the detail. A throwable already collected is not walked again, so a cause chain that
     * loops terminates.
     */
    private static List<Throwable> chain(Throwable failure) {
        Set<Throwable> toReturn = Sets.newLinkedHashSet();
        Deque<Throwable> pending = Queues.newArrayDeque(List.of(failure));
        while (CollectionUtils.isNotEmpty(pending)) {
            Throwable next = pending.pop();
            if (toReturn.add(next)) {
                Lists.reverse(causes(next)).forEach(pending::push);
            }
        }
        return Lists.newArrayList(toReturn);
    }
    /** {@link ResolveException#getCauses()} lists every dependency a configuration could not resolve */
    private static List<Throwable> causes(Throwable failure) {
        if (failure instanceof ResolveException resolve) {
            return List.copyOf(resolve.getCauses());
        }
        return Optional.ofNullable(failure.getCause()).map(List::of).orElse(List.of());
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
