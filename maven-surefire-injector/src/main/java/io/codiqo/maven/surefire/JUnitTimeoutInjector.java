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
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import lombok.extern.slf4j.Slf4j;

/**
 * core extension that caps every JUnit 5 test with a default per-test timeout so a single hanging test does not consume
 * the whole-fork budget and abort the module's analysis. it injects the JUnit Platform configuration parameters into
 * the surefire/failsafe plugin of every non-pom reactor module. the timeout is measured per testable/lifecycle method
 * and, with SEPARATE_THREAD mode, actually interrupts a hung test (I/O, lock, sleep waits) — a timed-out test is a test
 * FAILURE, which the fork's -Dmaven.test.failure.ignore=true tolerates, so the build proceeds. it runs inside the
 * codiqo-forked build (loaded via maven.ext.class.path); the codiqo plugin supplies the timeout through
 * {@link SurefireInjectorConfig#PROP_PER_TEST_TIMEOUT_SECONDS}; the extension stays inert when that property is absent
 * or non-positive. JUnit 4 and TestNG have no equivalent global default and are unaffected; a pure CPU spin is only
 * reaped by the whole-fork surefire.timeout backstop.
 */
@Slf4j
@Singleton
@Named("codiqo-junit-timeout-injector")
public class JUnitTimeoutInjector extends AbstractMavenLifecycleParticipant {
    private static final String MAVEN_PLUGINS_GROUP_ID = "org.apache.maven.plugins";
    private static final String SUREFIRE_ARTIFACT_ID = "maven-surefire-plugin";
    private static final String FAILSAFE_ARTIFACT_ID = "maven-failsafe-plugin";

    private static final String CONFIGURATION = "configuration";
    private static final String PROPERTIES = "properties";
    private static final String CONFIGURATION_PARAMETERS = "configurationParameters";
    private static final String POM_PACKAGING = "pom";

    private static final String TIMEOUT_DEFAULT_KEY = "junit.jupiter.execution.timeout.default";
    private static final String TIMEOUT_THREAD_MODE_KEY = "junit.jupiter.execution.timeout.thread.mode.default";
    private static final String SEPARATE_THREAD = "SEPARATE_THREAD";
    private static final String NEWLINE = "\n";

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

        String parameters = timeoutParameters(timeoutSeconds);

        injectConfigurationParameters(resolveSurefire(project), parameters);
        findDeclaredPlugin(project, FAILSAFE_ARTIFACT_ID).ifPresent(failsafe -> injectConfigurationParameters(failsafe, parameters));

        log.info("[codiqo] injected JUnit per-test timeout into {} ({}s, SEPARATE_THREAD)", project.getArtifactId(), timeoutSeconds);
    }
    private static void injectConfigurationParameters(Plugin plugin, String parameters) {
        /**
         * set the timeout on the plugin-level configuration (applies to executions that do not declare their own
         * configurationParameters) and on every execution that declares its own — an execution-level value overrides
         * the plugin-level one under Maven's leaf merge, so a parent's explicit configurationParameters would
         * otherwise drop the timeout.
         */
        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        if (Objects.isNull(config)) {
            config = new Xpp3Dom(CONFIGURATION);
            plugin.setConfiguration(config);
        }
        appendConfigurationParameters(config, parameters);

        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom executionConfig = (Xpp3Dom) execution.getConfiguration();
            if (Objects.nonNull(executionConfig) && hasConfigurationParameters(executionConfig)) {
                appendConfigurationParameters(executionConfig, parameters);
            }
        }
    }
    private static void appendConfigurationParameters(Xpp3Dom config, String parameters) {
        /**
         * codiqo's parameters go LAST in the newline-separated properties text so that on a duplicate key the JUnit
         * Platform properties parse resolves to codiqo's value — the safety cap stays authoritative even when the
         * project already declares its own timeout configurationParameters.
         */
        Xpp3Dom properties = config.getChild(PROPERTIES);
        if (Objects.isNull(properties)) {
            properties = new Xpp3Dom(PROPERTIES);
            config.addChild(properties);
        }

        Xpp3Dom configurationParameters = properties.getChild(CONFIGURATION_PARAMETERS);
        if (Objects.isNull(configurationParameters)) {
            configurationParameters = new Xpp3Dom(CONFIGURATION_PARAMETERS);
            properties.addChild(configurationParameters);
        }

        String base = StringUtils.trimToEmpty(configurationParameters.getValue());
        configurationParameters.setValue(StringUtils.isBlank(base) ? parameters : base + NEWLINE + parameters);
    }
    private static String timeoutParameters(long timeoutSeconds) {
        return TIMEOUT_DEFAULT_KEY + " = " + timeoutSeconds + NEWLINE
                + TIMEOUT_THREAD_MODE_KEY + " = " + SEPARATE_THREAD;
    }
    private static boolean hasConfigurationParameters(Xpp3Dom config) {
        Xpp3Dom properties = config.getChild(PROPERTIES);
        return Objects.nonNull(properties) && Objects.nonNull(properties.getChild(CONFIGURATION_PARAMETERS));
    }
    private static Plugin resolveSurefire(MavenProject project) {
        Optional<Plugin> declared = findDeclaredPlugin(project, SUREFIRE_ARTIFACT_ID);
        if (declared.isPresent()) {
            return declared.get();
        }

        Plugin created = new Plugin();
        created.setGroupId(MAVEN_PLUGINS_GROUP_ID);
        created.setArtifactId(SUREFIRE_ARTIFACT_ID);
        project.getBuild().addPlugin(created);
        return created;
    }
    private static Optional<Plugin> findDeclaredPlugin(MavenProject project, String artifactId) {
        Optional<Plugin> inBuild = project.getBuild().getPlugins().stream()
                .filter(plugin -> artifactId.equals(plugin.getArtifactId()))
                .findFirst();
        if (inBuild.isPresent()) {
            return inBuild;
        }

        PluginManagement pluginManagement = project.getBuild().getPluginManagement();
        if (Objects.nonNull(pluginManagement)) {
            return pluginManagement.getPlugins().stream()
                    .filter(plugin -> artifactId.equals(plugin.getArtifactId()))
                    .findFirst();
        }
        return Optional.empty();
    }
}
