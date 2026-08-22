package io.codiqo.gradle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.jacoco.core.JaCoCo;

import io.codiqo.api.RunArgs;

public class CodiqoGradlePlugin implements Plugin<Project> {
    private static final String EXTENSION_NAME = "codiqo";
    private static final String TASK_GROUP = "codiqo";
    private static final String DUMP_TASK = "codiqoDumpAnalysis";
    private static final String JACOCO_PLUGIN_ID = "jacoco";

    private static final String TIMEOUT_DEFAULT_KEY = "junit.jupiter.execution.timeout.default";
    private static final String TIMEOUT_THREAD_MODE_KEY = "junit.jupiter.execution.timeout.thread.mode.default";
    private static final String SEPARATE_THREAD = "SEPARATE_THREAD";

    private static final Pattern AGENT_ARTIFACT_VERSION = Pattern.compile("^(\\d+\\.\\d+\\.\\d+)");

    @Override
    public void apply(Project project) {
        CodiqoExtension ext = project.getExtensions().create(EXTENSION_NAME, CodiqoExtension.class);
        project.getTasks().register(DUMP_TASK, CodiqoDumpAnalysisTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Analyze the current checkout and dump the Codiqo analysis submission as YAML");

            /**
             * When tests run in the same build, the dump merges each Test task's build/jacoco/codiqo-<task>.exec part
             * into the module's codiqo.exec, so it must run after every Test task has written its part — otherwise the
             * dump's fast JDT import finishes first under parallel execution and coverage comes out empty. Lazy
             * Callable: the subproject test tasks are registered later (the init script applies jacoco), so they are
             * enumerated only when Gradle resolves the execution graph.
             */
            task.mustRunAfter((Callable<List<Test>>) () -> allTestTasks(project));
        });
        project.getTasks().register("codiqoIndexCommits", CodiqoIndexCommitsTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Walk git history over the commit window and write analyzable commit SHAs to a file");
        });

        /**
         * Snapshot the Gradle model off the task-execution path so subproject classpaths are resolved at configuration
         * time (works under parallel execution, no --no-parallel).
         */
        project.getGradle().getTaskGraph().whenReady(graph -> {
            CodiqoDumpAnalysisTask task = (CodiqoDumpAnalysisTask) project.getTasks().findByName(DUMP_TASK);
            if (Objects.nonNull(task) && graph.hasTask(task)) {
                task.setRequest(GradleModelCollector.collect(project.getRootProject(), ext));
            }
        });

        /**
         * codiqo owns coverage and test execution in the analysis build (mirrors the Maven coverage-injector and
         * surefire-injector, which the Maven side loads on the forked build's maven.ext.class.path): every Test task's
         * jacoco extension is normalized to the uniform per-module exec file the analysis reads, and every Test task
         * gets the same timeout envelope the Maven fork imposes through -Dsurefire.timeout and the JUnit per-test
         * default. runs at task-graph-ready — after all project configuration — so a project's own jacoco or test
         * settings never win the race; both the jacoco agent argument and the test JVM's system properties are
         * resolved from these objects only at process fork.
         */
        project.getRootProject().getAllprojects().forEach(module -> module.getPluginManager()
                .withPlugin(JACOCO_PLUGIN_ID, applied -> module.afterEvaluate(CodiqoGradlePlugin::pinJacocoToolVersion)));

        project.getGradle().getTaskGraph().whenReady(graph -> {
            RunArgs timeouts = resolveTimeouts(ext);
            graph.getAllTasks().stream()
                    .filter(Test.class::isInstance)
                    .map(Test.class::cast)
                    .forEach(test -> {
                        ownJacoco(test);
                        ownTestExecution(test, timeouts, ext.isIgnoreTestFailures());
                    });
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
             * very same commit. excludeClassLoaders and includeNoLocationClasses are deliberately left alone — their
             * Gradle defaults already equal the bare agent's.
             */
            jacoco.setIncludes(List.of());
            jacoco.setExcludes(List.of());

            /**
             * the exec file is what the analysis reads, so the two settings that decide whether it is written at all
             * are pinned too: a project that switched output to TCP_SERVER or turned off the exit dump produces a
             * green build and empty coverage.
             */
            jacoco.setDumpOnExit(true);
            jacoco.setOutput(JacocoTaskExtension.Output.FILE);
        }
    }
    /**
     * pin the agent to the same JaCoCo codiqo parses the exec file with. an older toolVersion cannot instrument classes
     * compiled by a newer JDK — it raises "Unsupported class file major version", the agent skips the class, and the
     * build still goes green with coverage silently missing. mirrors the Maven injector, which supplies its own resolved
     * agent jar instead of the project's.
     *
     * this runs at afterEvaluate rather than with the per-task normalization at taskGraph.whenReady: by the time the
     * graph is ready the extension's toolVersion is already finalized, so a late set is silently dropped and the agent
     * stays on Gradle's bundled default (0.8.14 on Gradle 9.4) while the analysis parses the exec with a different
     * version. afterEvaluate is still late enough to beat a project that sets its own toolVersion in its build script.
     */
    private static void pinJacocoToolVersion(Project project) {
        JacocoPluginExtension jacoco = project.getExtensions().findByType(JacocoPluginExtension.class);
        if (Objects.nonNull(jacoco)) {
            jacoco.setToolVersion(agentArtifactVersion());
        }
    }
    /**
     * {@link JaCoCo#VERSION} carries the OSGi build qualifier (0.8.15.202606040825); the Maven coordinate the
     * jacocoAgent configuration resolves is the bare 0.8.15, so the qualifier has to come off or the agent artifact
     * does not exist. failing loudly beats falling back to Gradle's bundled default, which is the silent
     * instrument-with-one-version/parse-with-another divergence this pin exists to prevent.
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
         * build before codiqoDumpAnalysis runs. a task that hits the timeout above still fails, and only --continue
         * carries the build past that — hence the documented invocation.
         */
        test.setIgnoreFailures(ignoreTestFailures);

        /**
         * mirrors the Maven surefire injector: JUnit reads configuration parameters from JVM system properties, and
         * SEPARATE_THREAD makes a hung test (I/O, a lock, a sleep, an un-timed HTTP call) preemptively interrupted and
         * reported as a failure, which the ignoreFailures above tolerates so the build proceeds. JUnit 4 and TestNG
         * have no equivalent global default and ignore the unknown properties; a pure CPU spin is only reaped by the
         * task timeout.
         */
        if (timeouts.getPerTestTimeout().compareTo(Duration.ZERO) > 0) {
            test.systemProperty(TIMEOUT_DEFAULT_KEY, timeouts.getPerTestTimeout().getSeconds());
            test.systemProperty(TIMEOUT_THREAD_MODE_KEY, SEPARATE_THREAD);
        }

        /**
         * the JUnit XML is the analysis's only evidence that this task forked and ran something — it is what
         * JavaLanguageSpec.expectsCoverage reads to tell a module whose tests produced no coverage from a module that
         * legitimately has no tests. pinned for the same reason the exec file's own switches are: a project that turned
         * the report off would make every module look test-less and codiqo.failOnUninstrumentedModule could never fire.
         */
        test.getReports().getJunitXml().getRequired().set(true);
    }
    /**
     * RunArgs owns the derivation (an unset per-test timeout becomes testTimeout/2, capped) so the Gradle daemon and
     * the analysis worker cannot disagree about the envelope they are enforcing.
     */
    static RunArgs resolveTimeouts(CodiqoExtension ext) {
        RunArgs toReturn = new RunArgs();
        toReturn.setTestTimeout(Duration.ofMinutes(ext.getTestTimeoutMinutes()));
        Optional.ofNullable(ext.getPerTestTimeoutMinutes()).ifPresent(minutes -> toReturn.setPerTestTimeout(Duration.ofMinutes(minutes)));
        toReturn.validate();
        return toReturn;
    }
    private static List<Test> allTestTasks(Project project) {
        List<Test> toReturn = new ArrayList<>();
        for (Project p : project.getRootProject().getAllprojects()) {
            toReturn.addAll(p.getTasks().withType(Test.class));
        }
        return toReturn;
    }
}
