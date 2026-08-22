package io.codiqo.maven.surefire;

import java.util.Objects;
import java.util.Optional;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;

import lombok.experimental.UtilityClass;

/**
 * Locates the surefire family's plugin declarations in a reactor module's model. Both the codiqo-forked build (which
 * appends to their {@code argLine}) and the codiqo plugin (which reads back where they write their JUnit XML) have to
 * find the same declaration, and a module can carry it in either {@code build/plugins} or {@code build/pluginManagement}.
 */
@UtilityClass
public class SurefirePlugins {
    public static final String MAVEN_PLUGINS_GROUP_ID = "org.apache.maven.plugins";
    public static final String SUREFIRE_ARTIFACT_ID = "maven-surefire-plugin";
    public static final String FAILSAFE_ARTIFACT_ID = "maven-failsafe-plugin";

    public Optional<Plugin> findDeclared(MavenProject project, String artifactId) {
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
