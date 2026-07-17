package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenJacocoDestFileTest {
    @TempDir
    Path analyzedRoot;

    @Test
    void executionLevelAggregatedDestFileIsDetected() {
        MavenProject module = module("module-a");
        module.getBuild().addPlugin(jacocoWithExecutionDestFile(analyzedRoot.resolve("target/jacoco.exec").toString()));

        assertEquals(analyzedRoot.resolve("target/jacoco.exec").toFile(), Maven.autoDetectJacocoDestFile(module));
    }
    @Test
    void pluginLevelDestFileIsDetected() {
        MavenProject module = module("module-a");
        Plugin jacoco = jacocoPlugin();
        jacoco.setConfiguration(destFileConfiguration("/custom/location/jacoco.exec"));
        module.getBuild().addPlugin(jacoco);

        assertEquals(new File("/custom/location/jacoco.exec"), Maven.autoDetectJacocoDestFile(module));
    }
    @Test
    void executionLevelDestFileWinsOverPluginLevel() {
        MavenProject module = module("module-a");
        Plugin jacoco = jacocoWithExecutionDestFile(analyzedRoot.resolve("target/jacoco.exec").toString());
        jacoco.setConfiguration(destFileConfiguration("/plugin/level/jacoco.exec"));
        module.getBuild().addPlugin(jacoco);

        assertEquals(analyzedRoot.resolve("target/jacoco.exec").toFile(), Maven.autoDetectJacocoDestFile(module));
    }
    @Test
    void defaultsToModuleBuildDirectoryWithoutDestFileConfiguration() {
        MavenProject module = module("module-a");
        module.getBuild().addPlugin(jacocoPlugin());

        assertEquals(new File(module.getBuild().getDirectory(), "jacoco.exec"), Maven.autoDetectJacocoDestFile(module));
    }
    @Test
    void defaultsToModuleBuildDirectoryWithoutJacocoPlugin() {
        MavenProject module = module("module-a");

        assertEquals(new File(module.getBuild().getDirectory(), "jacoco.exec"), Maven.autoDetectJacocoDestFile(module));
    }
    @Test
    void pinReplacesMultiModuleProjectDirectoryAndKeepsOtherProperties() {
        Properties systemProperties = new Properties();
        systemProperties.setProperty("maven.multiModuleProjectDirectory", "/host/root");
        systemProperties.setProperty("os.detected.name", "osx");

        ProjectBuildingRequest request = new DefaultProjectBuildingRequest();
        request.setSystemProperties(systemProperties);

        Maven.pinMultiModuleProjectDirectory(request, analyzedRoot.toFile());
        assertEquals(analyzedRoot.toFile().getAbsolutePath(), request.getSystemProperties().getProperty("maven.multiModuleProjectDirectory"));
        assertEquals("osx", request.getSystemProperties().getProperty("os.detected.name"));
        assertEquals("/host/root", systemProperties.getProperty("maven.multiModuleProjectDirectory"));
    }
    private MavenProject module(String artifactId) {
        MavenProject toReturn = new MavenProject();
        toReturn.setArtifactId(artifactId);

        Build build = new Build();
        build.setDirectory(analyzedRoot.resolve(artifactId).resolve("target").toString());
        toReturn.getModel().setBuild(build);

        return toReturn;
    }
    private static Plugin jacocoWithExecutionDestFile(String destFile) {
        PluginExecution execution = new PluginExecution();
        execution.setId("agent-for-ut");
        execution.addGoal("prepare-agent");
        execution.setConfiguration(destFileConfiguration(destFile));

        Plugin toReturn = jacocoPlugin();
        toReturn.addExecution(execution);
        return toReturn;
    }
    private static Plugin jacocoPlugin() {
        Plugin toReturn = new Plugin();
        toReturn.setGroupId("org.jacoco");
        toReturn.setArtifactId("jacoco-maven-plugin");
        return toReturn;
    }
    private static Xpp3Dom destFileConfiguration(String destFile) {
        Xpp3Dom destFileNode = new Xpp3Dom("destFile");
        destFileNode.setValue(destFile);

        Xpp3Dom toReturn = new Xpp3Dom("configuration");
        toReturn.addChild(destFileNode);
        return toReturn;
    }
}
