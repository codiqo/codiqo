package io.codiqo.maven.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.jupiter.api.Test;

import io.codiqo.client.model.ModuleDependencyUsageModel;

class ProjectModelPopulatorUsageTest {
    private static final String MODULE = "com.example:ledger:jar:1.0";

    @Test
    void aDependencyTheModuleDeclaresIsDirect() {
        Artifact guava = artifact("com.google.guava", "guava", "33.0", Artifact.SCOPE_COMPILE);
        guava.setDependencyTrail(List.of(MODULE, guava.getId()));

        ModuleDependencyUsageModel usage = ProjectModelPopulator.usage(guava);

        assertEquals(guava.getId(), usage.getId());
        assertEquals(Artifact.SCOPE_COMPILE, usage.getScope());
        assertFalse(usage.getTransitive());
        assertFalse(usage.getOptional());
    }
    @Test
    void aDependencyAnotherOneBroughtInIsTransitive() {
        Artifact failureAccess = artifact("com.google.guava", "failureaccess", "1.0.2", Artifact.SCOPE_TEST);
        failureAccess.setDependencyTrail(List.of(MODULE, "com.google.guava:guava:jar:33.0", failureAccess.getId()));
        failureAccess.setOptional(true);

        ModuleDependencyUsageModel usage = ProjectModelPopulator.usage(failureAccess);

        assertTrue(usage.getTransitive());
        assertEquals(Artifact.SCOPE_TEST, usage.getScope());
        assertTrue(usage.getOptional());
    }
    @Test
    void anArtifactWithoutATrailLeavesTransitiveUnsaid() {
        ModuleDependencyUsageModel usage = ProjectModelPopulator.usage(artifact("com.example", "billing-api", "1.1", null));

        assertNull(usage.getTransitive());
        assertNull(usage.getScope());
    }
    private static Artifact artifact(String groupId, String artifactId, String version, String scope) {
        return new DefaultArtifact(groupId, artifactId, version, scope, "jar", null, new DefaultArtifactHandler("jar"));
    }
}
