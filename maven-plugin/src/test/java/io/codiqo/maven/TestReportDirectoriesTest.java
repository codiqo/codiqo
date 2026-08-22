package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.maven.surefire.SurefirePlugins;

/**
 * Where a module's JUnit XML lands is a plugin parameter, not a layout convention: {@code reportsDirectory} declares no
 * user property, so the effective POM is the only place that answers it.
 */
class TestReportDirectoriesTest {
    private static final String MODULE_DIR = "/tmp/module";

    @Test
    void bothPluginDefaultsAreOfferedWhenNothingIsConfigured() {
        Collection<File> resolved = TestReportDirectories.resolve(project());

        assertTrue(resolved.contains(new File(MODULE_DIR + "/target", "surefire-reports")), resolved.toString());
        assertTrue(resolved.contains(new File(MODULE_DIR + "/target", "failsafe-reports")), resolved.toString());
    }
    @Test
    void aPluginLevelReportsDirectoryIsHonoured() {
        Plugin surefire = plugin(SurefirePlugins.SUREFIRE_ARTIFACT_ID);
        surefire.setConfiguration(reportsDirectory("/elsewhere/unit-reports"));

        Collection<File> resolved = TestReportDirectories.resolve(project(surefire));

        assertTrue(resolved.contains(new File("/elsewhere/unit-reports")), resolved.toString());
    }
    /** failsafe is routinely configured per execution rather than at plugin level */
    @Test
    void anExecutionLevelReportsDirectoryIsHonoured() {
        PluginExecution execution = new PluginExecution();
        execution.setId("integration-test");
        execution.setConfiguration(reportsDirectory("/elsewhere/it-reports"));

        Plugin failsafe = plugin(SurefirePlugins.FAILSAFE_ARTIFACT_ID);
        failsafe.getExecutions().add(execution);

        Collection<File> resolved = TestReportDirectories.resolve(project(failsafe));

        assertTrue(resolved.contains(new File("/elsewhere/it-reports")), resolved.toString());
    }
    /** maven binds a relative File parameter against the module, not the invocation directory */
    @Test
    void aRelativeReportsDirectoryResolvesAgainstTheModule() {
        Plugin surefire = plugin(SurefirePlugins.SUREFIRE_ARTIFACT_ID);
        surefire.setConfiguration(reportsDirectory("build/reports"));

        Collection<File> resolved = TestReportDirectories.resolve(project(surefire));

        assertTrue(resolved.contains(new File(MODULE_DIR, "build/reports")), resolved.toString());
    }
    /**
     * A caller cannot reach this through a POM — {@link #mavenInterpolatesPluginConfigurationInTheEffectiveModel}
     * pins that {@code ${project.*}} arrives resolved — but an expression only the execution scope could resolve
     * would survive as a literal. It must not throw, must not displace the plugin default, and must itself name
     * something that cannot exist rather than something that might accidentally match. The suffix deliberately
     * differs from the default directory, so the assertion cannot pass by coincidence.
     */
    @Test
    void anUnresolvedExpressionNeitherMatchesNorDisplacesTheDefault() {
        Plugin surefire = plugin(SurefirePlugins.SUREFIRE_ARTIFACT_ID);
        surefire.setConfiguration(reportsDirectory("${session.executionRootDirectory}/codiqo-test-reports"));

        Collection<File> resolved = TestReportDirectories.resolve(project(surefire));

        assertTrue(resolved.contains(new File(MODULE_DIR + "/target", "surefire-reports")), resolved.toString());
        assertTrue(resolved.contains(new File(MODULE_DIR, "${session.executionRootDirectory}/codiqo-test-reports")), resolved.toString());
        assertTrue(resolved.stream().noneMatch(File::exists), resolved.toString());
    }
    /**
     * The assumption the whole class rests on: reading {@code reportsDirectory} out of the effective POM is only sound
     * because maven has already interpolated it. If a future maven stopped substituting plugin configuration, a module
     * writing its reports to a computed directory would silently look like a module whose tests never ran, and
     * {@code failOnUninstrumentedModule} would stop detecting a detached JaCoCo agent. Fail here instead.
     */
    @Test
    void mavenInterpolatesPluginConfigurationInTheEffectiveModel(@TempDir Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>x</groupId><artifactId>y</artifactId><version>1</version>
                  <properties><codiqo.reports>computed-reports</codiqo.reports></properties>
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-surefire-plugin</artifactId>
                      <configuration>
                        <reportsDirectory>${project.build.directory}/${codiqo.reports}</reportsDirectory>
                      </configuration>
                    </plugin>
                  </plugins></build>
                </project>
                """);

        Model effective = new DefaultModelBuilderFactory().newInstance()
                .build(new DefaultModelBuildingRequest()
                        .setPomFile(pom.toFile())
                        .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                        .setProcessPlugins(false)
                        .setSystemProperties(System.getProperties()))
                .getEffectiveModel();

        Xpp3Dom configuration = (Xpp3Dom) effective.getBuild().getPlugins().iterator().next().getConfiguration();

        assertEquals(
                new File(effective.getBuild().getDirectory(), "computed-reports").getPath(),
                configuration.getChild("reportsDirectory").getValue());
    }
    private static MavenProject project(Plugin... plugins) {
        Build build = new Build();
        build.setDirectory(MODULE_DIR + "/target");
        for (Plugin plugin : plugins) {
            build.addPlugin(plugin);
        }

        Model model = new Model();
        model.setBuild(build);

        MavenProject toReturn = new MavenProject(model);
        toReturn.setFile(new File(MODULE_DIR, "pom.xml"));
        return toReturn;
    }
    private static Plugin plugin(String artifactId) {
        Plugin toReturn = new Plugin();
        toReturn.setGroupId(SurefirePlugins.MAVEN_PLUGINS_GROUP_ID);
        toReturn.setArtifactId(artifactId);
        return toReturn;
    }
    private static Xpp3Dom reportsDirectory(String value) {
        Xpp3Dom reportsDirectory = new Xpp3Dom("reportsDirectory");
        reportsDirectory.setValue(value);

        Xpp3Dom toReturn = new Xpp3Dom("configuration");
        toReturn.addChild(reportsDirectory);
        return toReturn;
    }
}
