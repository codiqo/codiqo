package io.codiqo.gradle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.jacoco.core.JaCoCo;

import io.codiqo.api.RunArgs;
import io.codiqo.gradle.model.AnalysisRequest;

public class CodiqoGradlePlugin implements Plugin<Project> {
    private static final String EXTENSION_NAME = "codiqo";
    private static final String TASK_GROUP = "codiqo";
    private static final String DUMP_TASK = "codiqoDumpAnalysis";
    private static final String SUBMIT_TASK = "codiqoSubmitAnalysis";
    private static final String INDEX_TASK = "codiqoIndexCommits";
    private static final Set<String> ANALYSIS_TASKS = Set.of(DUMP_TASK, SUBMIT_TASK);
    private static final String JACOCO_PLUGIN_ID = "jacoco";

    private static final String TIMEOUT_DEFAULT_KEY = "junit.jupiter.execution.timeout.default";
    private static final String TIMEOUT_THREAD_MODE_KEY = "junit.jupiter.execution.timeout.thread.mode.default";
    private static final String SEPARATE_THREAD = "SEPARATE_THREAD";

    private static final Pattern AGENT_ARTIFACT_VERSION = Pattern.compile("^(\\d+\\.\\d+\\.\\d+)");

    @Override
    public void apply(Project project) {
        CodiqoExtension ext = project.getExtensions().create(EXTENSION_NAME, CodiqoExtension.class);

        /**
         * the two analysis tasks differ only in whether the worker posts the result, mirroring the Maven side where
         * submit-commit-analysis is analyze-commit plus the POST. Run one or the other, never both.
         */
        registerAnalysisTask(project, DUMP_TASK, false,
                "Analyze the current checkout and dump the Codiqo analysis submission as YAML");
        registerAnalysisTask(project, SUBMIT_TASK, true,
                "Analyze the current checkout and submit the Codiqo analysis to the backend");
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
            List<CodiqoDumpAnalysisTask> scheduled = Stream.of(DUMP_TASK, SUBMIT_TASK)
                    .map(name -> (CodiqoDumpAnalysisTask) project.getTasks().findByName(name))
                    .filter(Objects::nonNull)
                    .filter(graph::hasTask)
                    .toList();
            if (CollectionUtils.isNotEmpty(scheduled)) {
                AnalysisRequest request = GradleModelCollector.collect(project.getRootProject(), ext);
                List<Task> graphTasks = List.copyOf(graph.getAllTasks());
                scheduled.forEach(task -> {
                    task.setRequest(request);
                    task.setScheduledTasks(graphTasks);
                });
            }
        });

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
            RunArgs timeouts = resolveTimeouts(
                    longProp(project, "codiqo.testTimeoutMinutes", ext.getTestTimeoutMinutes()),
                    perTestTimeoutMinutes(project, ext));
            graph.getAllTasks().stream()
                    .filter(Test.class::isInstance)
                    .map(Test.class::cast)
                    .forEach(test -> {
                        ownJacoco(test);
                        ownTestExecution(test, timeouts, boolProp(project, "codiqo.ignoreTestFailures", ext.isIgnoreTestFailures()));
                    });
        });
    }
    private static void registerAnalysisTask(Project project, String name, boolean submit, String description) {
        project.getTasks().register(name, CodiqoDumpAnalysisTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription(description);
            task.setSubmit(submit);

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
    static String agentArtifactVersion() {
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
         * build before codiqoDumpAnalysis runs. A task that hits the timeout above still fails, and only --continue
         * carries the build past that — hence the documented invocation.
         */
        test.setIgnoreFailures(ignoreTestFailures);

        /**
         * mirrors the Maven surefire injector: JUnit reads configuration parameters from JVM system properties, and
         * SEPARATE_THREAD makes a hung test preemptively interrupted and reported as a failure, which the
         * ignoreFailures above tolerates. JUnit 4 and TestNG have no equivalent global default and ignore the unknown
         * properties; a pure CPU spin is only reaped by the task timeout.
         */
        if (timeouts.getPerTestTimeout().compareTo(Duration.ZERO) > 0) {
            test.systemProperty(TIMEOUT_DEFAULT_KEY, timeouts.getPerTestTimeout().getSeconds());
            test.systemProperty(TIMEOUT_THREAD_MODE_KEY, SEPARATE_THREAD);
        }

        /**
         * the JUnit XML is the analysis's only evidence that this task forked and ran something — it is what
         * JavaLanguageSpec.expectsCoverage reads to tell a module whose tests produced no coverage from a module that
         * legitimately has none. A project that turned the report off would make every module look test-less.
         */
        test.getReports().getJunitXml().getRequired().set(true);
    }
    /**
     * RunArgs owns the derivation (an unset per-test timeout becomes testTimeout/2, capped) so the Gradle daemon and
     * the analysis worker cannot disagree about the envelope they are enforcing.
     */
    static RunArgs resolveTimeouts(long testTimeoutMinutes, Long perTestTimeoutMinutes) {
        RunArgs toReturn = new RunArgs();
        toReturn.setTestTimeout(Duration.ofMinutes(testTimeoutMinutes));
        Optional.ofNullable(perTestTimeoutMinutes).ifPresent(minutes -> toReturn.setPerTestTimeout(Duration.ofMinutes(minutes)));
        toReturn.validate();
        return toReturn;
    }
    /**
     * every task in the build except the codiqo analysis tasks — naming itself would be a cycle, and naming its
     * sibling would serialise a dump and a submit that are never meant to run together anyway.
     */
    private static List<Task> tasksToFollow(Project project) {
        List<Task> toReturn = new ArrayList<>();
        for (Project module : project.getRootProject().getAllprojects()) {
            for (Task task : module.getTasks()) {
                if (BooleanUtils.negate(ANALYSIS_TASKS.contains(task.getName()))) {
                    toReturn.add(task);
                }
            }
        }
        return toReturn;
    }
    /**
     * -P overrides are read here as well as in GradleModelCollector, because the timeouts are applied to the Test
     * tasks in the daemon rather than travelling to the worker in the request. Reading only the extension made every
     * documented -Pcodiqo.testTimeoutMinutes silently inert, including the ones codiqo-action passes.
     */
    private static Long perTestTimeoutMinutes(Project project, CodiqoExtension ext) {
        return Optional.ofNullable(project.findProperty("codiqo.perTestTimeoutMinutes"))
                .map(Object::toString)
                .map(Long::parseLong)
                .orElse(ext.getPerTestTimeoutMinutes());
    }
    private static long longProp(Project project, String name, long fallback) {
        return Optional.ofNullable(project.findProperty(name)).map(Object::toString).map(Long::parseLong).orElse(fallback);
    }
    private static boolean boolProp(Project project, String name, boolean fallback) {
        return Optional.ofNullable(project.findProperty(name)).map(Object::toString).map(Boolean::parseBoolean).orElse(fallback);
    }
}
