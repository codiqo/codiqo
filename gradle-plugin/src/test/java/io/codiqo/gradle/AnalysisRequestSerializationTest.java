package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.gradle.model.DependencyData;
import io.codiqo.gradle.model.ModuleData;

/**
 * The dump task pipes the collected model to the forked analysis JVM as JSON, so a field Jackson cannot map breaks
 * every Gradle analysis at fork time rather than at compile time. Round-tripping a fully populated request keeps that
 * failure in the build.
 */
class AnalysisRequestSerializationTest {
    @Test
    void populatedRequestSurvivesTheRoundTripToTheWorker() throws Exception {
        AnalysisRequest request = populatedRequest();

        ObjectMapper mapper = new ObjectMapper();
        AnalysisRequest restored = mapper.readValue(mapper.writeValueAsBytes(request), AnalysisRequest.class);

        assertEquals(request, restored);
        assertEquals("8g", restored.getAnalysisMaxHeap());
        assertEquals(1, restored.getModules().size());
        assertEquals(List.of("/p/src/main/java"), restored.getModules().iterator().next().getCompileSourceRoots());
    }
    private static AnalysisRequest populatedRequest() {
        DependencyData dependency = new DependencyData();
        dependency.setGroupId("org.apache.commons");
        dependency.setArtifactId("commons-lang3");
        dependency.setVersion("3.20.0");
        dependency.setType("jar");
        dependency.setClassifier("sources");
        dependency.setFilePath("/repo/commons-lang3-3.20.0.jar");
        dependency.setCoordinate("org.apache.commons:commons-lang3:jar:3.20.0");

        ModuleData module = new ModuleData();
        module.setId(":app");
        module.setGroupId("io.codiqo");
        module.setArtifactId("app");
        module.setVersion("1.0");
        module.setPackaging("jar");
        module.setDescription("a module");
        module.setBaseDirectory("/p");
        module.setOutputDirectory("/p/build/classes/java/main");
        module.setCoveragePath("/p/build/jacoco/codiqo.exec");
        module.getCompileSourceRoots().add("/p/src/main/java");
        module.getTestCompileSourceRoots().add("/p/src/test/java");
        module.getTestReportDirectories().add("/p/build/test-results/test");
        module.getCompileClasspathElements().add("/repo/commons-lang3-3.20.0.jar");
        module.getTestClasspathElements().add("/repo/junit-jupiter.jar");
        module.getDependencies().add(dependency);

        AnalysisRequest toReturn = new AnalysisRequest();
        toReturn.setRootDir("/p");
        toReturn.setRootCode("io.codiqo:app");
        toReturn.setRootName("app");
        toReturn.setGradleVersion("9.4.1");
        toReturn.setCommitId("abc123");
        toReturn.setOutputDirectory("/p/build/codiqo");
        toReturn.setJavaHome("/jdk");
        toReturn.setAnalysisMaxHeap("8g");
        toReturn.setExcludeProjects(":docs");
        toReturn.setExcludePaths("**/generated/**");
        toReturn.setJdtlsVersion("1.60.0");
        toReturn.setImportTimeoutMinutes(15);
        toReturn.setLspQueryTimeoutSeconds(30);
        toReturn.getModules().add(module);
        return toReturn;
    }
}
