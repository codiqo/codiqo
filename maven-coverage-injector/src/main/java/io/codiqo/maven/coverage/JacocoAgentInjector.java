package io.codiqo.maven.coverage;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * core extension that makes codiqo the sole owner of coverage in the analysis fork: the fork runs with
 * -Djacoco.skip=true (the project's own jacoco never executes, whatever its shape — per-module, aggregated destFile,
 * or misconfigured), and this participant attaches the JaCoCo agent to the surefire/failsafe fork of EVERY non-pom
 * reactor module with a uniform per-module destfile. because the project's prepare-agent is skipped, any argLine
 * reference to its agent property (@{argLine}, ${argLine}, or a configured propertyName) would reach the test JVM as
 * literal text and abort it — those tokens are stripped before the agent is appended. it runs inside the codiqo-forked
 * build (loaded via maven.ext.class.path); the codiqo plugin supplies the agent jar path through
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
    private static final String PROPERTY_NAME = "propertyName";
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

        String destFile = new File(project.getBuild().getDirectory(), JACOCO_EXEC).getAbsolutePath();

        /**
         * quote the whole -javaagent argument so surefire/failsafe keep it as a single token when the agent jar or
         * destfile path contains a space (macOS user dirs, spaced CI workspaces); argLine is tokenized on whitespace,
         * so an unquoted spaced path would be truncated. mirrors jacoco-maven-plugin's own prepare-agent.
         */
        String javaAgent = org.codehaus.plexus.util.StringUtils.quoteAndEscape("-javaagent:" + agentJar + "=destfile=" + destFile + ",append=true", '"');

        Set<String> agentPropertyNames = jacocoAgentPropertyNames(project);
        String argLineProperty = StringUtils.trimToEmpty(project.getProperties().getProperty(ARG_LINE));

        injectAgent(resolveSurefire(project), javaAgent, agentPropertyNames, argLineProperty);
        findDeclaredPlugin(project, FAILSAFE_ARTIFACT_ID).ifPresent(failsafe -> injectAgent(failsafe, javaAgent, agentPropertyNames, argLineProperty));

        log.info("[codiqo] injected JaCoCo agent into {} test argLine (destfile: {})", project.getArtifactId(), destFile);
    }
    private static void injectAgent(Plugin plugin, String javaAgent, Set<String> agentPropertyNames, String argLineProperty) {
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
        appendAgent(config, javaAgent, agentPropertyNames, argLineProperty);

        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom executionConfig = (Xpp3Dom) execution.getConfiguration();
            if (Objects.nonNull(executionConfig) && Objects.nonNull(executionConfig.getChild(ARG_LINE))) {
                appendAgent(executionConfig, javaAgent, agentPropertyNames, argLineProperty);
            }
        }
    }
    private static void appendAgent(Xpp3Dom config, String javaAgent, Set<String> agentPropertyNames, String argLineProperty) {
        /**
         * the agent deliberately goes LAST: running jacoco before another transforming -javaagent (e.g. allure's
         * aspectjweaver) was verified to destroy recording in that JVM entirely (aggregator-server 448 -> 0 covered
         * methods), while jacoco-last only shifts the class ids of the few classes the other agent rewrites — those
         * are excluded from coverage by the analysis-side mismatch guard
         */
        Xpp3Dom argLine = config.getChild(ARG_LINE);
        if (Objects.isNull(argLine)) {
            argLine = new Xpp3Dom(ARG_LINE);
            config.addChild(argLine);

            /**
             * with no explicit argLine surefire falls back to the ${argLine} PROJECT property; the injected value
             * replaces that fallback, so a static argLine property (JVM opts some projects define there) must be
             * carried over
             */
            argLine.setValue(StringUtils.isBlank(argLineProperty) ? javaAgent : argLineProperty + " " + javaAgent);
            return;
        }

        String base = stripAgentPropertyTokens(StringUtils.defaultString(argLine.getValue()), agentPropertyNames);
        argLine.setValue(StringUtils.isBlank(base) ? javaAgent : base + " " + javaAgent);
    }
    private static String stripAgentPropertyTokens(String argLine, Set<String> agentPropertyNames) {
        /**
         * the analysis fork runs with -Djacoco.skip=true, so the project's prepare-agent never populates its agent
         * property — a leftover @{argLine}/${propertyName} reference would reach the test JVM as literal text and
         * abort the fork with "Unrecognized option"
         */
        String toReturn = argLine;
        for (String name : agentPropertyNames) {
            toReturn = StringUtils.replace(toReturn, "@{" + name + "}", "");
            toReturn = StringUtils.replace(toReturn, "${" + name + "}", "");
        }
        return toReturn.trim();
    }
    private static Set<String> jacocoAgentPropertyNames(MavenProject project) {
        Set<String> toReturn = new HashSet<>();
        toReturn.add(ARG_LINE);

        List<Plugin> declared = new ArrayList<>(project.getBuild().getPlugins());
        PluginManagement pluginManagement = project.getBuild().getPluginManagement();
        if (Objects.nonNull(pluginManagement)) {
            declared.addAll(pluginManagement.getPlugins());
        }

        declared.stream().filter(JacocoAgentInjector::isJacoco).forEach(plugin -> {
            addPropertyName(toReturn, (Xpp3Dom) plugin.getConfiguration());
            plugin.getExecutions().forEach(execution -> addPropertyName(toReturn, (Xpp3Dom) execution.getConfiguration()));
        });
        return toReturn;
    }
    private static void addPropertyName(Set<String> names, Xpp3Dom config) {
        if (Objects.nonNull(config)) {
            Xpp3Dom propertyName = config.getChild(PROPERTY_NAME);
            if (Objects.nonNull(propertyName) && StringUtils.isNotBlank(propertyName.getValue())) {
                names.add(propertyName.getValue().trim());
            }
        }
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
    private static boolean isJacoco(Plugin plugin) {
        return BooleanUtils.and(new boolean[] { JACOCO_GROUP_ID.equals(plugin.getGroupId()), JACOCO_MAVEN_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId()) });
    }
}
