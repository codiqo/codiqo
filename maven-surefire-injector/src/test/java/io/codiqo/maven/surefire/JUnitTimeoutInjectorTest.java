package io.codiqo.maven.surefire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Objects;

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
    private static final String DEFAULT_ARG = "-Djunit.jupiter.execution.timeout.default=900";
    private static final String THREAD_MODE_ARG = "-Djunit.jupiter.execution.timeout.thread.mode.default=SEPARATE_THREAD";

    @AfterEach
    void clearTimeoutProperty() {
        System.clearProperty(SurefireInjectorConfig.PROP_PER_TEST_TIMEOUT_SECONDS);
    }

    @Test
    void injectsTimeoutArgsIntoSurefire() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        build.addPlugin(surefire);

        runInjector(build, TIMEOUT_SECONDS);

        String argLine = argLine(surefire);
        assertTrue(argLine.contains(DEFAULT_ARG), "surefire argLine must carry the default per-test timeout system property");
        assertTrue(argLine.contains(THREAD_MODE_ARG), "surefire argLine must carry SEPARATE_THREAD so hung tests are interrupted");
    }

    @Test
    void injectsIntoDeclaredFailsafe() {
        Build build = build();
        build.addPlugin(plugin(MAVEN_PLUGINS, SUREFIRE));
        Plugin failsafe = plugin(MAVEN_PLUGINS, FAILSAFE);
        build.addPlugin(failsafe);

        runInjector(build, TIMEOUT_SECONDS);

        assertTrue(argLine(failsafe).contains(DEFAULT_ARG), "declared failsafe must also receive the timeout args");
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
    void preservesExistingArgLine() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        surefire.setConfiguration(configurationWithArgLine("-Xmx1g"));
        build.addPlugin(surefire);

        runInjector(build, TIMEOUT_SECONDS);

        String argLine = argLine(surefire);
        assertTrue(argLine.startsWith("-Xmx1g "), "the project's existing argLine must be preserved");
        assertTrue(argLine.contains(DEFAULT_ARG), "the timeout args must be appended after the existing argLine");
    }

    @Test
    void seedsCreatedArgLineWithStaticArgLineProperty() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        build.addPlugin(surefire);

        runInjector(build, "jar", TIMEOUT_SECONDS, "-Duser.timezone=UTC");

        String argLine = argLine(surefire);
        assertTrue(argLine.startsWith("-Duser.timezone=UTC "), "a static argLine POM property must be carried into the created argLine");
        assertTrue(argLine.contains(THREAD_MODE_ARG), "the timeout args must follow the carried-over property");
    }

    @Test
    void appendsToExecutionLevelArgLineOverride() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        surefire.addExecution(executionWithArgLine("default-test", "-Dfoo=bar"));
        build.addPlugin(surefire);

        runInjector(build, TIMEOUT_SECONDS);

        assertTrue(argLine(surefire).contains(DEFAULT_ARG), "plugin-level argLine must receive the timeout args");

        Xpp3Dom executionConfig = (Xpp3Dom) surefire.getExecutions().get(0).getConfiguration();
        String executionArgLine = executionConfig.getChild("argLine").getValue();
        assertTrue(executionArgLine.contains(DEFAULT_ARG), "an execution that overrides argLine must also receive the timeout args");
        assertTrue(executionArgLine.contains("-Dfoo=bar"), "the original execution argLine must be preserved");
    }

    @Test
    void fabricatesSurefireWhenNoTestPluginDeclared() {
        Build build = build();

        runInjector(build, TIMEOUT_SECONDS);

        Plugin surefire = build.getPlugins().stream()
                .filter(p -> SUREFIRE.equals(p.getArtifactId()))
                .findFirst()
                .orElseThrow();
        assertTrue(argLine(surefire).contains(DEFAULT_ARG), "a module with no test plugin must get a fabricated surefire carrying the timeout args");
        assertFalse(build.getPlugins().stream().anyMatch(p -> FAILSAFE.equals(p.getArtifactId())), "failsafe must not be fabricated");
    }

    @Test
    void skipsPomPackagingAggregator() {
        Build build = build();

        runInjector(build, "pom", TIMEOUT_SECONDS, null);

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
        runInjector(build, "jar", seconds, null);
    }

    private static void runInjector(Build build, String packaging, long seconds, String argLineProperty) {
        System.setProperty(SurefireInjectorConfig.PROP_PER_TEST_TIMEOUT_SECONDS, String.valueOf(seconds));
        afterProjectsRead(build, packaging, argLineProperty);
    }

    private static void runInjectorWithoutTimeout(Build build) {
        System.clearProperty(SurefireInjectorConfig.PROP_PER_TEST_TIMEOUT_SECONDS);
        afterProjectsRead(build, "jar", null);
    }

    private static void afterProjectsRead(Build build, String packaging, String argLineProperty) {
        Model model = new Model();
        model.setArtifactId("module-under-test");
        model.setPackaging(packaging);
        model.setBuild(build);
        if (Objects.nonNull(argLineProperty)) {
            model.addProperty("argLine", argLineProperty);
        }
        MavenProject project = new MavenProject(model);

        MavenSession session = mock(MavenSession.class);
        when(session.getAllProjects()).thenReturn(List.of(project));

        new JUnitTimeoutInjector().afterProjectsRead(session);
    }

    private static Xpp3Dom configurationWithArgLine(String value) {
        Xpp3Dom argLine = new Xpp3Dom("argLine");
        argLine.setValue(value);

        Xpp3Dom config = new Xpp3Dom("configuration");
        config.addChild(argLine);
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

    private static PluginExecution executionWithArgLine(String id, String value) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setConfiguration(configurationWithArgLine(value));
        return execution;
    }

    private static String argLine(Plugin plugin) {
        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        assertNotNull(config, "expected an injected configuration on " + plugin.getArtifactId());
        Xpp3Dom argLine = config.getChild("argLine");
        assertNotNull(argLine, "expected an injected argLine on " + plugin.getArtifactId());
        return argLine.getValue();
    }
}
