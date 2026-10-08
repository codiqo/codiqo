package io.codiqo.maven.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.Map;

import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.client.model.SnapshotMetadataModel;
import io.codiqo.maven.timemachine.SnapshotMetadataStore;
import io.codiqo.maven.timemachine.SnapshotMetadataStore.SnapshotResolution;

/** the time machine writes the sidecar inside the forked build and the plugin reads it back: both sides of one file format */
class ProjectModelPopulatorSnapshotMetadataTest {
    @TempDir
    private File metaDir;

    @Test
    void aSidecarTheTimeMachineWroteIsFoundByItsCoordinate() {
        SnapshotMetadataStore.write(metaDir, "com.example", "billing", "1.0-SNAPSHOT", SnapshotResolution.builder()
                .resolvedVersion("1.0-20240105.143000-6")
                .deployedAt(Instant.parse("2024-01-05T14:30:00Z"))
                .targetTimestamp(Instant.parse("2024-01-05T15:00:00Z"))
                .build());

        Map<Triple<String, String, String>, SnapshotMetadataModel> loaded = ProjectModelPopulator.loadSnapshotMetadata(metaDir);

        assertEquals(1, loaded.size());
        SnapshotMetadataModel model = loaded.get(Triple.of("com.example", "billing", "1.0-SNAPSHOT"));
        assertEquals("1.0-20240105.143000-6", model.getResolvedVersion());
    }
    /** a coordinate part that holds the old separator is still its own part */
    @Test
    void aColonInsideACoordinatePartDoesNotCollide() {
        SnapshotResolution resolution = SnapshotResolution.builder()
                .resolvedVersion("1.0-20240105.143000-6")
                .deployedAt(Instant.parse("2024-01-05T14:30:00Z"))
                .targetTimestamp(Instant.parse("2024-01-05T15:00:00Z"))
                .build();
        SnapshotMetadataStore.write(metaDir, "com.example", "a:b", "1.0-SNAPSHOT", resolution);
        SnapshotMetadataStore.write(metaDir, "com.example:a", "b", "1.0-SNAPSHOT", resolution);

        Map<Triple<String, String, String>, SnapshotMetadataModel> loaded = ProjectModelPopulator.loadSnapshotMetadata(metaDir);

        assertTrue(loaded.containsKey(Triple.of("com.example", "a:b", "1.0-SNAPSHOT")));
        assertTrue(loaded.containsKey(Triple.of("com.example:a", "b", "1.0-SNAPSHOT")));
    }
}
