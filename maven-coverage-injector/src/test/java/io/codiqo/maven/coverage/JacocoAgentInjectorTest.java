package io.codiqo.maven.coverage;

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
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JacocoAgentInjectorTest {
    private static final String SUREFIRE = "maven-surefire-plugin";
    private static final String FAILSAFE = "maven-failsafe-plugin";
    private static final String JACOCO_GROUP_ID = "org.jacoco";
    private static final String JACOCO_PLUGIN = "jacoco-maven-plugin";
    private static final String MAVEN_PLUGINS = "org.apache.maven.plugins";

    @AfterEach
    void clearAgentProperty() {
        System.clearProperty(CoverageInjectorConfig.PROP_AGENT_JAR);
    }

    @Test
    void quotesAgentArgumentWhenPathContainsSpace() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        build.addPlugin(surefire);

        runInjector(build, "/opt/build agent/org.jacoco.agent-runtime.jar");

        String argLine = argLine(surefire);
        assertTrue(argLine.startsWith("\"-javaagent:"), "spaced agent path must be quoted as one token");
        assertTrue(argLine.endsWith("\""), "quoted argument must be closed");
    }

    @Test
    void injectsWhenJacocoIsOnlyPluginManaged() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        build.addPlugin(surefire);

        PluginManagement management = new PluginManagement();
        management.addPlugin(plugin(JACOCO_GROUP_ID, JACOCO_PLUGIN));
        build.setPluginManagement(management);

        runInjector(build, "/opt/agent/jacoco.jar");

        assertTrue(argLine(surefire).contains("-javaagent:"), "pluginManagement-only jacoco must not suppress injection");
    }

    @Test
    void skipsModuleWithActiveJacocoPlugin() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        build.addPlugin(surefire);
        build.addPlugin(plugin(JACOCO_GROUP_ID, JACOCO_PLUGIN));

        runInjector(build, "/opt/agent/jacoco.jar");

        assertNull(surefire.getConfiguration(), "a module that activates jacoco itself must be left untouched");
    }

    @Test
    void injectsIntoDeclaredFailsafe() {
        Build build = build();
        build.addPlugin(plugin(MAVEN_PLUGINS, SUREFIRE));
        Plugin failsafe = plugin(MAVEN_PLUGINS, FAILSAFE);
        build.addPlugin(failsafe);

        runInjector(build, "/opt/agent/jacoco.jar");

        assertTrue(argLine(failsafe).contains("-javaagent:"), "declared failsafe must also receive the agent");
    }

    @Test
    void doesNotFabricateFailsafeWhenAbsent() {
        Build build = build();
        build.addPlugin(plugin(MAVEN_PLUGINS, SUREFIRE));

        runInjector(build, "/opt/agent/jacoco.jar");

        assertFalse(
                build.getPlugins().stream().anyMatch(p -> FAILSAFE.equals(p.getArtifactId())),
                "failsafe must never be fabricated where the module does not use it");
    }

    @Test
    void skipsPomPackagingAggregator() {
        Build build = build();

        runInjector(build, "pom", "/opt/agent/jacoco.jar");

        assertTrue(build.getPlugins().isEmpty(), "pom aggregator must not get a fabricated surefire plugin");
    }

    @Test
    void fabricatesSurefireWhenNoTestPluginDeclared() {
        Build build = build();

        runInjector(build, "/opt/agent/jacoco.jar");

        Plugin surefire = build.getPlugins().stream()
                .filter(p -> SUREFIRE.equals(p.getArtifactId()))
                .findFirst()
                .orElseThrow();
        assertTrue(argLine(surefire).contains("-javaagent:"), "a module with no test plugin must get a fabricated surefire carrying the agent");
        assertFalse(build.getPlugins().stream().anyMatch(p -> FAILSAFE.equals(p.getArtifactId())), "failsafe must not be fabricated");
    }

    @Test
    void appendsToExecutionLevelArgLineOverride() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        surefire.addExecution(executionWithArgLine("default-test", "${jvm.unsafe.options}"));
        build.addPlugin(surefire);

        runInjector(build, "/opt/agent/jacoco.jar");

        assertTrue(argLine(surefire).contains("-javaagent:"), "plugin-level argLine must receive the agent");

        Xpp3Dom executionConfig = (Xpp3Dom) surefire.getExecutions().get(0).getConfiguration();
        String executionArgLine = executionConfig.getChild("argLine").getValue();
        assertTrue(executionArgLine.contains("-javaagent:"), "an execution that overrides argLine must also receive the agent");
        assertTrue(executionArgLine.contains("${jvm.unsafe.options}"), "the original execution argLine must be preserved");
    }

    @Test
    void preservesExistingArgLine() {
        Build build = build();
        Plugin surefire = plugin(MAVEN_PLUGINS, SUREFIRE);
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom argLine = new Xpp3Dom("argLine");
        argLine.setValue("-Xmx1g");
        config.addChild(argLine);
        surefire.setConfiguration(config);
        build.addPlugin(surefire);

        runInjector(build, "/opt/agent/jacoco.jar");

        String result = argLine(surefire);
        assertTrue(result.startsWith("-Xmx1g "), "existing argLine must be preserved");
        assertTrue(result.contains("-javaagent:"), "agent must be appended to the existing argLine");
    }

    private static void runInjector(Build build, String agentJar) {
        runInjector(build, "jar", agentJar);
    }

    private static void runInjector(Build build, String packaging, String agentJar) {
        Model model = new Model();
        model.setArtifactId("module-under-test");
        model.setPackaging(packaging);
        model.setBuild(build);
        MavenProject project = new MavenProject(model);

        MavenSession session = mock(MavenSession.class);
        when(session.getAllProjects()).thenReturn(List.of(project));

        System.setProperty(CoverageInjectorConfig.PROP_AGENT_JAR, agentJar);
        new JacocoAgentInjector().afterProjectsRead(session);
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
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom argLine = new Xpp3Dom("argLine");
        argLine.setValue(value);
        config.addChild(argLine);

        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setConfiguration(config);
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
