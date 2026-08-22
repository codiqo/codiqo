package io.codiqo.maven.surefire;

import java.util.Objects;
import java.util.Optional;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import lombok.extern.slf4j.Slf4j;

/**
 * core extension that caps every JUnit 5/6 test with a default per-test timeout so a single hanging test does not
 * consume the whole-fork budget and abort the module's analysis. the timeout is delivered as JUnit Platform
 * configuration parameters passed as {@code -D} system properties on the surefire/failsafe {@code argLine} of every
 * non-pom reactor module — JUnit reads configuration parameters from JVM system properties, so this reaches the launcher
 * even in JUnit 6. (the {@code <properties><configurationParameters>} element is NOT usable here: a lifecycle
 * participant that adds it at runtime is silently dropped by surefire's mojo configurator, whereas argLine — the same
 * channel the JaCoCo agent injector uses — is honored.) with SEPARATE_THREAD mode a hung test (I/O, lock, sleep, an
 * un-timed HTTP call) is preemptively interrupted and reported as a FAILURE, which the fork's
 * -Dmaven.test.failure.ignore=true tolerates, so the build proceeds. it runs inside the codiqo-forked build (loaded via
 * maven.ext.class.path); the codiqo plugin supplies the timeout through
 * {@link SurefireInjectorConfig#PROP_PER_TEST_TIMEOUT_SECONDS}; the extension stays inert when that property is absent
 * or non-positive. JUnit 4 and TestNG have no equivalent global default and simply ignore the unknown -D; a pure CPU
 * spin is only reaped by the whole-fork surefire.timeout backstop.
 */
@Slf4j
@Singleton
@Named("codiqo-junit-timeout-injector")
public class JUnitTimeoutInjector extends AbstractMavenLifecycleParticipant {
    private static final String CONFIGURATION = "configuration";
    private static final String ARG_LINE = "argLine";
    private static final String POM_PACKAGING = "pom";

    private static final String TIMEOUT_DEFAULT_KEY = "junit.jupiter.execution.timeout.default";
    private static final String TIMEOUT_THREAD_MODE_KEY = "junit.jupiter.execution.timeout.thread.mode.default";
    private static final String SEPARATE_THREAD = "SEPARATE_THREAD";

    @Override
    public void afterProjectsRead(MavenSession session) {
        long timeoutSeconds = NumberUtils.toLong(System.getProperty(SurefireInjectorConfig.PROP_PER_TEST_TIMEOUT_SECONDS), 0L);
        if (timeoutSeconds > 0L) {
            for (MavenProject project : session.getAllProjects()) {
                inject(project, timeoutSeconds);
            }
        }
    }
    private static void inject(MavenProject project, long timeoutSeconds) {
        if (POM_PACKAGING.equals(project.getPackaging())) {
            return;
        }

        String timeoutArgs = timeoutArgs(timeoutSeconds);
        String argLineProperty = StringUtils.trimToEmpty(project.getProperties().getProperty(ARG_LINE));

        injectArgLine(resolveSurefire(project), timeoutArgs, argLineProperty);
        SurefirePlugins.findDeclared(project, SurefirePlugins.FAILSAFE_ARTIFACT_ID)
                .ifPresent(failsafe -> injectArgLine(failsafe, timeoutArgs, argLineProperty));

        log.info("[codiqo] injected JUnit per-test timeout into {} ({}s, SEPARATE_THREAD)", project.getArtifactId(), timeoutSeconds);
    }
    private static void injectArgLine(Plugin plugin, String timeoutArgs, String argLineProperty) {
        /**
         * append to the plugin-level argLine (applies to executions that do not declare their own argLine) and to every
         * execution that sets its own argLine — an execution-level value overrides the plugin-level one, so a shared
         * parent's default-test execution would otherwise drop the timeout. mirrors the JaCoCo agent injector.
         */
        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        if (Objects.isNull(config)) {
            config = new Xpp3Dom(CONFIGURATION);
            plugin.setConfiguration(config);
        }
        appendArgs(config, timeoutArgs, argLineProperty);

        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom executionConfig = (Xpp3Dom) execution.getConfiguration();
            if (Objects.nonNull(executionConfig) && Objects.nonNull(executionConfig.getChild(ARG_LINE))) {
                appendArgs(executionConfig, timeoutArgs, argLineProperty);
            }
        }
    }
    private static void appendArgs(Xpp3Dom config, String timeoutArgs, String argLineProperty) {
        Xpp3Dom argLine = config.getChild(ARG_LINE);
        if (Objects.isNull(argLine)) {
            argLine = new Xpp3Dom(ARG_LINE);
            config.addChild(argLine);

            /**
             * with no explicit argLine surefire falls back to the ${argLine} PROJECT property; the created value
             * replaces that fallback, so a static argLine property (JVM opts, and the coverage injector's carry-over)
             * must be preserved ahead of the timeout flags.
             */
            argLine.setValue(StringUtils.isBlank(argLineProperty) ? timeoutArgs : argLineProperty + " " + timeoutArgs);
            return;
        }

        String base = StringUtils.defaultString(argLine.getValue());
        argLine.setValue(StringUtils.isBlank(base) ? timeoutArgs : base + " " + timeoutArgs);
    }
    private static String timeoutArgs(long timeoutSeconds) {
        return "-D" + TIMEOUT_DEFAULT_KEY + "=" + timeoutSeconds + " -D" + TIMEOUT_THREAD_MODE_KEY + "=" + SEPARATE_THREAD;
    }
    private static Plugin resolveSurefire(MavenProject project) {
        Optional<Plugin> declared = SurefirePlugins.findDeclared(project, SurefirePlugins.SUREFIRE_ARTIFACT_ID);
        if (declared.isPresent()) {
            return declared.get();
        }

        Plugin created = new Plugin();
        created.setGroupId(SurefirePlugins.MAVEN_PLUGINS_GROUP_ID);
        created.setArtifactId(SurefirePlugins.SUREFIRE_ARTIFACT_ID);
        project.getBuild().addPlugin(created);
        return created;
    }
}
