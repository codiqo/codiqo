package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Properties;

import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenBuildingRequestTest {
    @TempDir
    Path analyzedRoot;

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
}
