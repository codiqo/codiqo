package io.codiqo.maven.surefire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JUnitTimeoutInjectorTest {
    private static final String SUREFIRE = "maven-surefire-plugin";
    private static final String FAILSAFE = "maven-failsafe-plugin";
    private static final String MAVEN_PLUGINS = "org.apache.maven.plugins";

    private static final long TIMEOUT_SECONDS = 900L;
    private static final String DEFAULT_PARAM = "junit.jupiter.execution.timeout.default = 900";
    private static final String THREAD_MODE_PARAM = "junit.jupiter.execution.timeout.thread.mode.default = SEPARATE_THREAD";

    @AfterEach
    void clearTimeoutProperty() {
        System.clearProperty(SurefireInjectorConfig.PROP_PER_TEST_TIMEOUT_SECONDS);
    }

    @Test
    void injectsTimeoutIntoSurefire() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        build.addPlugin(surefire);

        runInjector(build, TIMEOUT_SECONDS);

        String params = configParams(surefire);
        assertTrue(params.contains(DEFAULT_PARAM), "surefire must receive the default per-test timeout");
        assertTrue(params.contains(THREAD_MODE_PARAM), "surefire must receive SEPARATE_THREAD so hung tests are interrupted");
    }

    @Test
    void injectsIntoDeclaredFailsafe() {
        Build build = build();
        build.addPlugin(plugin(MAVEN_PLUGINS, SUREFIRE));
        Plugin failsafe = plugin(MAVEN_PLUGINS, FAILSAFE);
        build.addPlugin(failsafe);

        runInjector(build, TIMEOUT_SECONDS);

        assertTrue(configParams(failsafe).contains(DEFAULT_PARAM), "declared failsafe must also receive the timeout");
    }

    @Test
    void doesNotFabricateFailsafeWhenAbsent() {
        Build build = build();
        build.addPlugin(plugin(MAVEN_PLUGINS, SUREFIRE));

        runInjector(build, TIMEOUT_SECONDS);

        assertFalse(
                build.getPlugins().stream().anyMatch(p -> FAILSAFE.equals(p.getArtifactId())),
                "failsafe must never be fabricated where the module does not use it");
    }

    @Test
    void appendsAfterExistingConfigurationParameters() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        surefire.setConfiguration(configurationWithConfigParameters("junit.jupiter.execution.timeout.default = 5"));
        build.addPlugin(surefire);

        runInjector(build, TIMEOUT_SECONDS);

        String params = configParams(surefire);
        assertTrue(params.contains("junit.jupiter.execution.timeout.default = 5"), "the project's existing parameters must be preserved");
        assertTrue(params.contains(DEFAULT_PARAM), "codiqo's cap must be added");
        assertTrue(params.indexOf(DEFAULT_PARAM) > params.indexOf("= 5"), "codiqo's cap must be appended last so it wins on the duplicate key");
    }

    @Test
    void appendsToExecutionLevelConfigurationParameters() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        surefire.addExecution(executionWithConfigParameters("default-test", "junit.jupiter.execution.parallel.enabled = true"));
        build.addPlugin(surefire);

        runInjector(build, TIMEOUT_SECONDS);

        assertTrue(configParams(surefire).contains(DEFAULT_PARAM), "plugin-level configuration must receive the timeout");

        Xpp3Dom executionConfig = (Xpp3Dom) surefire.getExecutions().get(0).getConfiguration();
        String executionParams = executionConfig.getChild("properties").getChild("configurationParameters").getValue();
        assertTrue(executionParams.contains(DEFAULT_PARAM), "an execution that overrides configurationParameters must also receive the timeout");
        assertTrue(executionParams.contains("junit.jupiter.execution.parallel.enabled = true"), "the original execution parameters must be preserved");
    }

    @Test
    void fabricatesSurefireWhenNoTestPluginDeclared() {
        Build build = build();

        runInjector(build, TIMEOUT_SECONDS);

        Plugin surefire = build.getPlugins().stream()
                .filter(p -> SUREFIRE.equals(p.getArtifactId()))
                .findFirst()
                .orElseThrow();
        assertTrue(configParams(surefire).contains(DEFAULT_PARAM), "a module with no test plugin must get a fabricated surefire carrying the timeout");
        assertFalse(build.getPlugins().stream().anyMatch(p -> FAILSAFE.equals(p.getArtifactId())), "failsafe must not be fabricated");
    }

    @Test
    void skipsPomPackagingAggregator() {
        Build build = build();

        runInjector(build, "pom", TIMEOUT_SECONDS);

        assertTrue(build.getPlugins().isEmpty(), "pom aggregator must not get a fabricated surefire plugin");
    }

    @Test
    void noOpWhenTimeoutPropertyAbsent() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        build.addPlugin(surefire);

        runInjectorWithoutTimeout(build);

        assertNull(surefire.getConfiguration(), "no timeout must be injected when the property is absent");
    }

    @Test
    void noOpWhenTimeoutIsZero() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        build.addPlugin(surefire);

        runInjector(build, 0L);

        assertNull(surefire.getConfiguration(), "an explicit zero timeout disables injection");
    }

    private static void runInjector(Build build, long seconds) {
        runInjector(build, "jar", seconds);
    }

    private static void runInjector(Build build, String packaging, long seconds) {
        System.setProperty(SurefireInjectorConfig.PROP_PER_TEST_TIMEOUT_SECONDS, String.valueOf(seconds));
        afterProjectsRead(build, packaging);
    }

    private static void runInjectorWithoutTimeout(Build build) {
        System.clearProperty(SurefireInjectorConfig.PROP_PER_TEST_TIMEOUT_SECONDS);
        afterProjectsRead(build, "jar");
    }

    private static void afterProjectsRead(Build build, String packaging) {
        Model model = new Model();
        model.setArtifactId("module-under-test");
        model.setPackaging(packaging);
        model.setBuild(build);
        MavenProject project = new MavenProject(model);

        MavenSession session = mock(MavenSession.class);
        when(session.getAllProjects()).thenReturn(List.of(project));

        new JUnitTimeoutInjector().afterProjectsRead(session);
    }

    private static Xpp3Dom configurationWithConfigParameters(String value) {
        Xpp3Dom configurationParameters = new Xpp3Dom("configurationParameters");
        configurationParameters.setValue(value);

        Xpp3Dom properties = new Xpp3Dom("properties");
        properties.addChild(configurationParameters);

        Xpp3Dom config = new Xpp3Dom("configuration");
        config.addChild(properties);
        return config;
    }

    private static Build build() {
        Build build = new Build();
        build.setDirectory("/tmp/module/target");
        return build;
    }

    private static Plugin plugin(String groupId, String artifactId) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        return plugin;
    }

    private static PluginExecution executionWithConfigParameters(String id, String value) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setConfiguration(configurationWithConfigParameters(value));
        return execution;
    }

    private static String configParams(Plugin plugin) {
        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        assertNotNull(config, "expected an injected configuration on " + plugin.getArtifactId());
        Xpp3Dom properties = config.getChild("properties");
        assertNotNull(properties, "expected injected properties on " + plugin.getArtifactId());
        Xpp3Dom configurationParameters = properties.getChild("configurationParameters");
        assertNotNull(configurationParameters, "expected injected configurationParameters on " + plugin.getArtifactId());
        return configurationParameters.getValue();
    }
}
