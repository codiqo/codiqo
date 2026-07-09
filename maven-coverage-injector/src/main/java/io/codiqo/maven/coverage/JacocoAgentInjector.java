package io.codiqo.maven.coverage;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import lombok.extern.slf4j.Slf4j;

/**
 * core extension that attaches the JaCoCo agent to the surefire/failsafe fork of every reactor module that does not
 * already declare the jacoco-maven-plugin. it runs inside the codiqo-forked build (loaded via maven.ext.class.path) and
 * merges the agent into the resolved argLine, so it survives projects that set an explicit argLine (e.g. a shared
 * parent's {@code ${jvm.unsafe.options}}). the codiqo plugin supplies the agent jar path through
 * {@link CoverageInjectorConfig#PROP_AGENT_JAR}; the extension stays inert when that property is absent.
 */
@Slf4j
@Singleton
@Named("codiqo-jacoco-injector")
public class JacocoAgentInjector extends AbstractMavenLifecycleParticipant {
    private static final String MAVEN_PLUGINS_GROUP_ID = "org.apache.maven.plugins";
    private static final String SUREFIRE_ARTIFACT_ID = "maven-surefire-plugin";
    private static final String FAILSAFE_ARTIFACT_ID = "maven-failsafe-plugin";

    private static final String JACOCO_GROUP_ID = "org.jacoco";
    private static final String JACOCO_MAVEN_PLUGIN_ARTIFACT_ID = "jacoco-maven-plugin";

    private static final String CONFIGURATION = "configuration";
    private static final String ARG_LINE = "argLine";
    private static final String JACOCO_EXEC = "jacoco.exec";
    private static final String POM_PACKAGING = "pom";

    @Override
    public void afterProjectsRead(MavenSession session) {
        String agentJar = System.getProperty(CoverageInjectorConfig.PROP_AGENT_JAR);
        if (StringUtils.isNotBlank(agentJar)) {
            for (MavenProject project : session.getAllProjects()) {
                inject(project, agentJar.trim());
            }
        }
    }
    private static void inject(MavenProject project, String agentJar) {
        if (POM_PACKAGING.equals(project.getPackaging())) {
            return;
        }
        if (hasJacocoPlugin(project)) {
            log.info("[codiqo] {} already declares jacoco-maven-plugin; leaving coverage to the project", project.getArtifactId());
            return;
        }

        String destFile = new File(project.getBuild().getDirectory(), JACOCO_EXEC).getAbsolutePath();

        /**
         * quote the whole -javaagent argument so surefire/failsafe keep it as a single token when the agent jar or
         * destfile path contains a space (macOS user dirs, spaced CI workspaces); argLine is tokenized on whitespace,
         * so an unquoted spaced path would be truncated. mirrors jacoco-maven-plugin's own prepare-agent.
         */
        String javaAgent = org.codehaus.plexus.util.StringUtils.quoteAndEscape("-javaagent:" + agentJar + "=destfile=" + destFile + ",append=true", '"');

        injectAgent(resolveSurefire(project), javaAgent);
        findDeclaredPlugin(project, FAILSAFE_ARTIFACT_ID).ifPresent(failsafe -> injectAgent(failsafe, javaAgent));

        log.info("[codiqo] injected JaCoCo agent into {} test argLine (destfile: {})", project.getArtifactId(), destFile);
    }
    private static void injectAgent(Plugin plugin, String javaAgent) {
        /**
         * append to the plugin-level argLine (applies to executions that do not declare their own argLine) and to every
         * execution that sets its own argLine — an execution-level value overrides the plugin-level one, so a shared
         * parent's default-test execution (with an explicit argLine) would otherwise drop the agent.
         */
        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        if (Objects.isNull(config)) {
            config = new Xpp3Dom(CONFIGURATION);
            plugin.setConfiguration(config);
        }
        appendAgent(config, javaAgent);

        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom executionConfig = (Xpp3Dom) execution.getConfiguration();
            if (Objects.nonNull(executionConfig) && Objects.nonNull(executionConfig.getChild(ARG_LINE))) {
                appendAgent(executionConfig, javaAgent);
            }
        }
    }
    private static void appendAgent(Xpp3Dom config, String javaAgent) {
        Xpp3Dom argLine = config.getChild(ARG_LINE);
        if (Objects.isNull(argLine)) {
            argLine = new Xpp3Dom(ARG_LINE);
            config.addChild(argLine);
        }
        String base = argLine.getValue();
        argLine.setValue(StringUtils.isBlank(base) ? javaAgent : base + " " + javaAgent);
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
    private static boolean hasJacocoPlugin(MavenProject project) {
        /**
         * only a jacoco-maven-plugin activated in <build><plugins> binds prepare-agent; a pluginManagement-only
         * declaration supplies version/config defaults and never attaches the agent, so it must not suppress injection.
         */
        return project.getBuild().getPlugins().stream().anyMatch(JacocoAgentInjector::isJacoco);
    }
    private static boolean isJacoco(Plugin plugin) {
        return BooleanUtils.and(new boolean[] { JACOCO_GROUP_ID.equals(plugin.getGroupId()), JACOCO_MAVEN_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId()) });
    }
}
