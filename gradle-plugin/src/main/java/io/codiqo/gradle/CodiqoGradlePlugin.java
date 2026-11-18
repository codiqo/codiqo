package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.build.event.BuildEventsListenerRegistry;
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.jacoco.core.JaCoCo;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.api.testing.TestRecords;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.gradle.model.AnalysisRequest;

public abstract class CodiqoGradlePlugin implements Plugin<Project> {
    private static final String EXTENSION_NAME = "codiqo";
    private static final String TASK_GROUP = "codiqo";
    private static final String SUBMIT_TASK = "codiqoSubmitAnalysis";
    private static final String INDEX_TASK = "codiqoIndexCommits";
    private static final String LOGIN_TASK = "codiqoLogin";
    private static final String JACOCO_PLUGIN_ID = "jacoco";

    private static final String TIMEOUT_DEFAULT_KEY = "junit.jupiter.execution.timeout.default";
    private static final String TIMEOUT_THREAD_MODE_KEY = "junit.jupiter.execution.timeout.thread.mode.default";
    private static final String SAME_THREAD = "SAME_THREAD";

    private static final Pattern AGENT_ARTIFACT_VERSION = Pattern.compile("^(\\d+\\.\\d+\\.\\d+)");
    private static final String PROGRESS_SERVICE = "codiqoBuildProgress";
    @VisibleForTesting
    public static final Set<String> TEST_TIMEOUTS = Set.of("codiqo.testTimeoutMinutes", "codiqo.perTestTimeoutMinutes");

    @Inject
    protected abstract BuildEventsListenerRegistry getEventsListenerRegistry();

    @Override
    public void apply(Project project) {
        CodiqoExtension ext = project.getExtensions().create(EXTENSION_NAME, CodiqoExtension.class);

        registerAnalysisTask(project);
        project.getTasks().register(LOGIN_TASK, CodiqoLoginTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Authorize this machine through the browser, so builds need no codiqo.apiKey");
        });
        project.getTasks().register(INDEX_TASK, CodiqoIndexCommitsTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Index the commit window with the backend and write the commits still needing analysis to a file");
        });

        /**
         * Snapshot the Gradle model off the task-execution path so subproject classpaths are resolved at configuration
         * time, which works under parallel execution. The scheduled tasks are captured here, where the graph exists,
         * and their TaskState is read at execution time — state is set as each task completes, so by the time the
         * analysis runs (ordered after all of them) it sees every failure without depending on event delivery.
         */
        project.getGradle().getTaskGraph().whenReady(graph -> {
            CodiqoSubmitAnalysisTask task = (CodiqoSubmitAnalysisTask) project.getTasks().getByName(SUBMIT_TASK);
            if (graph.hasTask(task)) {
                Map<String, Throwable> unresolved = Maps.newLinkedHashMap();
                AnalysisRequest request = GradleModelCollector.collect(project.getRootProject(), ext, unresolved);
                requireRuntime(request);
                task.setRequest(request);
                task.setScheduledTasks(List.copyOf(graph.getAllTasks()));
                task.setUnresolvedClasspaths(unresolved);

                /**
                 * A classpath that does not resolve fails the build (BuildFailures), and a failed build is never analysed in
                 * full, so its tests would run for a coverage nothing reads: every commit of a stretch whose repository is
                 * unreachable would pay the whole test suite for an exclusion.
                 */
                if (MapUtils.isNotEmpty(unresolved)) {
                    graph.getAllTasks().stream().filter(Test.class::isInstance).forEach(test -> test.onlyIf("codiqo: a classpath did not resolve, so no test result is read", skipped -> false));
                }
            }
        });

        Optional.ofNullable(project.findProperty("codiqo.buildProgressFile"))
                .map(Object::toString)
                .map(StringUtils::trim)
                .filter(StringUtils::isNotEmpty)
                .ifPresent(path -> trackBuildProgress(project, new File(path)));

        project.getRootProject().getAllprojects().forEach(module -> module.getPluginManager()
                .withPlugin(JACOCO_PLUGIN_ID, applied -> module.afterEvaluate(CodiqoGradlePlugin::pinJacocoToolVersion)));

        /**
         * codiqo owns coverage and test execution in the analysis build, mirroring the Maven coverage- and
         * surefire-injectors: every Test task's jacoco extension is normalized to the uniform per-module exec file the
         * analysis reads, and every Test task gets the timeout envelope the Maven fork imposes. Runs at
         * task-graph-ready — after all project configuration — so a project's own jacoco or test settings never win
         * the race.
         */
        project.getGradle().getTaskGraph().whenReady(graph -> {
            List<Test> tests = graph.getAllTasks().stream().filter(Test.class::isInstance).map(Test.class::cast).toList();
            /**
             * Only the two timeouts, and only for a build that tests: a plain test build of a project whose init.d applies
             * the plugin must not fail on a review setting it never uses. The analysis build checks all of them.
             */
            if (CollectionUtils.isNotEmpty(tests)) {
                RunArgs timeouts = GradleSettings.runArgs(Maps.filterKeys(GradleSettings.of(project, ext), TEST_TIMEOUTS::contains));
                boolean ignoreTestFailures = GradleSettings.flag(project, "codiqo.ignoreTestFailures", ext.isIgnoreTestFailures());
                for (Test test : tests) {
                    ownJacoco(test);
                    ownTestExecution(test, timeouts, ignoreTestFailures);
                }
            }
        });
    }
    /**
     * module progress for a watcher outside the build, as the Maven fork's event spy writes it. The counts are taken
     * when the graph is ready, leaving out the analysis task: it is not part of building any module, and counting
     * it would keep the root project running until the analysis ends.
     */
    private void trackBuildProgress(Project project, File progressFile) {
        Provider<BuildProgressService> service = project.getGradle().getSharedServices().registerIfAbsent(PROGRESS_SERVICE,
                BuildProgressService.class, spec -> spec.getParameters().getProgressFile().set(progressFile));
        getEventsListenerRegistry().onTaskCompletion(service);

        project.getGradle().getTaskGraph().whenReady(graph -> {
            Map<String, Integer> tasksByProject = Maps.newLinkedHashMap();
            Map<String, String> ids = Maps.newLinkedHashMap();
            graph.getAllTasks().stream()
                    .filter(task -> BooleanUtils.negate(SUBMIT_TASK.equals(task.getName())))
                    .forEach(task -> {
                        Project owner = task.getProject();
                        tasksByProject.merge(owner.getPath(), 1, Integer::sum);
                        // the root's path is a bare ":", which says nothing in a status line
                        ids.putIfAbsent(owner.getPath(), Objects.equals(owner, owner.getRootProject()) ? ":" + owner.getName() : owner.getPath());
                    });
            service.get().expect(tasksByProject, ids);
        });
    }
    /**
     * The full analysis's JDK check (DefaultLanguageProcessors.requireRuntime), run while no task has started: Gradle
     * builds and tests the commit before the analysis worker starts, so a check in the worker let every scheduled run
     * on a machine with the wrong JDK pay for the whole build again before it failed. Here, in the daemon, a refusal
     * also halts nothing, since only the worker halts the project. It reads the request the worker receives, so it
     * checks the JDK and language server the worker would start.
     */
    private static void requireRuntime(AnalysisRequest request) {
        try {
            DefaultLanguageProcessors.requireRuntime(GradleSettings.runArgs(request.getSettings()));
        } catch (IOException err) {
            /**
             * Rethrown as it is, not wrapped: Gradle heads its failure report with the thrown exception, and the wrapper
             * ExceptionUtils.wrapAndThrow makes headed it with "UndeclaredThrowableException (no error message)",
             * leaving the line that says what to change as a cause below it.
             */
            ExceptionUtils.rethrow(err);
        }
    }
    private static void registerAnalysisTask(Project project) {
        project.getTasks().register(SUBMIT_TASK, CodiqoSubmitAnalysisTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Analyze the current checkout and submit the Codiqo analysis to the backend");

            /**
             * The analysis runs after every other task in the build. Ordering only against Test tasks was not enough:
             * a build invoked with `testClasses` has no Test task in the graph at all, so the ordering was vacuous and
             * the analysis raced compilation — reading a half-written class directory, and missing the compile failure
             * it is supposed to report. Both are silent wrong answers, not errors.
             *
             * <p>Lazy Callable, so the tasks are enumerated only when Gradle resolves the execution graph: the
             * subproject tasks do not all exist at apply() time, since the init script applies jacoco later.
             */
            task.mustRunAfter((Callable<List<Task>>) () -> tasksToFollow(project));
        });
    }
    private static void ownJacoco(Test test) {
        // absent only when the jacoco plugin is not applied to the task's project (non-java projects)
        JacocoTaskExtension jacoco = test.getExtensions().findByType(JacocoTaskExtension.class);
        if (Objects.nonNull(jacoco)) {
            jacoco.setEnabled(true);
            jacoco.setDestinationFile(GradleBuildSupport.jacocoExecPart(test));

            /**
             * the project's instrumentation filters are dropped rather than merged: the Maven side attaches a bare
             * agent whose only arguments are the destfile and append, so a project that narrows jacoco to a subset of
             * its packages would otherwise report changed code as uncovered on Gradle and covered on Maven for the
             * very same commit.
             */
            jacoco.setIncludes(List.of());
            jacoco.setExcludes(List.of());

            /**
             * the exec file is what the analysis reads, so the two settings deciding whether it is written at all are
             * pinned too: a project that switched output to TCP_SERVER or turned off the exit dump produces a green
             * build and empty coverage.
             */
            jacoco.setDumpOnExit(true);
            jacoco.setOutput(JacocoTaskExtension.Output.FILE);
        }
    }
    /**
     * pin the agent to the same JaCoCo codiqo parses the exec file with. An older toolVersion cannot instrument
     * classes compiled by a newer JDK — it raises "Unsupported class file major version", the agent skips the class,
     * and the build still goes green with coverage silently missing.
     *
     * <p>Runs at afterEvaluate rather than with the per-task normalization at taskGraph.whenReady: by the time the
     * graph is ready the extension's toolVersion is already finalized, so a late set is silently dropped.
     */
    private static void pinJacocoToolVersion(Project project) {
        JacocoPluginExtension jacoco = project.getExtensions().findByType(JacocoPluginExtension.class);
        if (Objects.nonNull(jacoco)) {
            jacoco.setToolVersion(agentArtifactVersion());
        }
    }
    /**
     * {@link JaCoCo#VERSION} carries the OSGi build qualifier (0.8.15.202606040825) while the Maven coordinate is the
     * bare 0.8.15, so the qualifier has to come off or the agent artifact does not exist. Failing loudly beats falling
     * back to Gradle's bundled default, which is the instrument-with-one-version/parse-with-another divergence this
     * pin exists to prevent.
     */
    @VisibleForTesting
    public static String agentArtifactVersion() {
        Matcher matcher = AGENT_ARTIFACT_VERSION.matcher(JaCoCo.VERSION);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new GradleException("could not derive a JaCoCo artifact version from " + JaCoCo.VERSION);
    }
    private static void ownTestExecution(Test test, RunArgs timeouts, boolean ignoreTestFailures) {
        /**
         * mirrors the Maven fork's -Dsurefire.timeout: a test task that hangs is killed rather than consuming the
         * whole analysis budget. Gradle has no equivalent of surefire.forkedProcessExitTimeoutInSeconds, so a fork
         * that leaks a non-daemon thread is reaped by this same task timeout instead of a separate grace period.
         */
        test.getTimeout().set(timeouts.getTestTimeout());

        /**
         * mirrors -Dmaven.test.failure.ignore=true: the analysis is the deliverable, so a red test must not abort the
         * build before codiqoSubmitAnalysis runs. A task that hits the timeout above still fails, and only --continue
         * carries the build past that — hence the documented invocation.
         */
        test.setIgnoreFailures(ignoreTestFailures);

        /**
         * mirrors the Maven surefire injector (JUnitTimeoutInjector, whose comment gives the evidence): JUnit reads
         * configuration parameters from JVM system properties, {@code timeout.default} times test and lifecycle methods
         * alike, and SAME_THREAD keeps them on the test framework's own thread, where Spring's test transactions, its
         * mock users and every extension that keeps state per thread live. A hung test is interrupted and reported as a
         * failure, which the ignoreFailures above tolerates. One stuck in something that ignores an interrupt (a blocking
         * socket read, a monitor deadlock, a CPU spin) runs until the task timeout kills the test JVM, and the test agent
         * below keeps that JVM's coverage and names the test. JUnit 4 and TestNG have no equivalent global default and
         * ignore the unknown properties. A project's own {@code timeout.testable.method.default} is more specific and still
         * wins; {@code codiqo.perTestTimeoutMinutes=0} turns the per-test timeout off.
         */
        if (timeouts.getPerTestTimeout().compareTo(Duration.ZERO) > 0) {
            test.systemProperty(TIMEOUT_DEFAULT_KEY, timeouts.getPerTestTimeout().getSeconds());
            test.systemProperty(TIMEOUT_THREAD_MODE_KEY, SAME_THREAD);
        }

        /**
         * codiqo's test agent writes JaCoCo's coverage while the tests run and records the test threads, so a test JVM the
         * task timeout kills keeps its coverage and names the test that never finished (TestRecords). The directory is
         * emptied first: the agent deletes its record when the JVM exits normally, so a record left by an earlier build of
         * the same checkout would otherwise read as this build's.
         */
        File records = GradleBuildSupport.testRecordDir(test);
        FileUtils.deleteQuietly(records);
        TestRecords.jvmArgument(records).ifPresent(test::jvmArgs);

        /**
         * the JUnit XML is the analysis's only evidence that this task forked and ran something — it is what
         * JavaLanguageSpec.expectsCoverage reads to tell a module whose tests produced no coverage from a module that
         * legitimately has none. A project that turned the report off would make every module look test-less.
         */
        test.getReports().getJunitXml().getRequired().set(true);
    }
    /**
     * every task in the build except the codiqo analysis task itself, since naming itself would be a cycle.
     */
    private static List<Task> tasksToFollow(Project project) {
        List<Task> toReturn = Lists.newArrayList();
        for (Project module : project.getRootProject().getAllprojects()) {
            for (Task task : module.getTasks()) {
                if (BooleanUtils.negate(SUBMIT_TASK.equals(task.getName()))) {
                    toReturn.add(task);
                }
            }
        }
        return toReturn;
    }
}
