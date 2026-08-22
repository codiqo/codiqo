package io.codiqo.maven;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import lombok.experimental.UtilityClass;

/**
 * Resolves where a reactor module's test run writes its JUnit XML, from the effective POM rather than from the
 * directory layout around {@code target/classes}. Surefire's and failsafe's {@code reportsDirectory} declares no user
 * property, so the configured value is the only thing that says where the reports land: a module that redirects it
 * would otherwise look like a module whose tests never ran.
 */
@UtilityClass
public class TestReportDirectories {
    private static final String SUREFIRE_ARTIFACT_ID = "maven-surefire-plugin";
    private static final String FAILSAFE_ARTIFACT_ID = "maven-failsafe-plugin";
    private static final String REPORTS_DIRECTORY = "reportsDirectory";
    private static final String SUREFIRE_DEFAULT_DIRECTORY = "surefire-reports";
    private static final String FAILSAFE_DEFAULT_DIRECTORY = "failsafe-reports";

    /**
     * Every candidate is returned rather than one winner. A plugin-level value, a per-execution override and the
     * plugin's own default can all be live in the same module — failsafe is routinely configured per execution — and
     * which one a given execution wrote to is decided by surefire, not here. A directory that was never written does
     * not exist, so offering all of them cannot mistake an unwritten location for a written one.
     */
    public Collection<File> resolve(MavenProject project) {
        Set<File> toReturn = new LinkedHashSet<>();
        toReturn.addAll(candidates(project, SUREFIRE_ARTIFACT_ID, SUREFIRE_DEFAULT_DIRECTORY));
        toReturn.addAll(candidates(project, FAILSAFE_ARTIFACT_ID, FAILSAFE_DEFAULT_DIRECTORY));
        return toReturn;
    }
    private static Collection<File> candidates(MavenProject project, String artifactId, String defaultDirectoryName) {
        Set<File> toReturn = new LinkedHashSet<>();

        findDeclared(project, artifactId).ifPresent(plugin -> {
            reportsDirectory(project, plugin.getConfiguration()).ifPresent(toReturn::add);
            for (PluginExecution execution : plugin.getExecutions()) {
                reportsDirectory(project, execution.getConfiguration()).ifPresent(toReturn::add);
            }
        });

        toReturn.add(new File(project.getBuild().getDirectory(), defaultDirectoryName));
        return toReturn;
    }
    /**
     * Deliberately a copy of the same lookup in {@code io.codiqo.maven.surefire.SurefirePlugins}, not a call to it. The
     * four {@code codiqo-maven-*} extensions are {@code provided} on purpose — maven loads them through
     * {@code maven.ext.class.path}, so the plugin realm never receives them and importing one here compiles but throws
     * NoClassDefFoundError at execution time. The injector cannot depend on this module either: it stays lean, with no
     * codiqo dependency of its own.
     */
    private static Optional<Plugin> findDeclared(MavenProject project, String artifactId) {
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
    private static Optional<File> reportsDirectory(MavenProject project, Object configuration) {
        /**
         * the value arrives interpolated: maven's model builder substitutes ${project.*} and POM properties across the
         * whole effective model, plugin configuration included (pinned by
         * TestReportDirectoriesTest.mavenInterpolatesPluginConfigurationInTheEffectiveModel). an expression only the
         * execution scope could resolve would survive as a literal and name a directory that cannot exist, which is
         * how a value we cannot make sense of should behave — the plugin default offered alongside it still applies
         */
        if (configuration instanceof Xpp3Dom dom) {
            return Optional.ofNullable(dom.getChild(REPORTS_DIRECTORY))
                    .map(Xpp3Dom::getValue)
                    .map(StringUtils::trimToNull)
                    .map(value -> resolveAgainstBaseDirectory(project, value));
        }
        return Optional.empty();
    }
    private static File resolveAgainstBaseDirectory(MavenProject project, String value) {
        /** mirrors how maven binds a relative File parameter: relative to the module, not to the invocation directory */
        File configured = new File(value);
        if (configured.isAbsolute()) {
            return configured;
        }
        return new File(project.getBasedir(), value);
    }
}
