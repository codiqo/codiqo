package io.codiqo.maven.timemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.maven.timemachine.SnapshotMetadataStore.SnapshotResolution;

class SnapshotMetadataStoreTest {
    @TempDir
    private File metaDir;

    @Test
    void roundTripsAllFields() {
        SnapshotResolution written = SnapshotResolution.builder()
                .resolvedVersion("1.0-20240105.143000-6")
                .deployedAt(Instant.parse("2024-01-05T14:30:00Z"))
                .buildNumber(6)
                .repositoryId("gar-snapshots")
                .repositoryUrl("https://europe-maven.pkg.dev/proj/snapshots")
                .targetTimestamp(Instant.parse("2024-01-05T15:00:00Z"))
                .staleSeconds(1800L)
                .build();
        SnapshotMetadataStore.write(metaDir, "io.codiqo", "codiqo-api", "1.0-SNAPSHOT", written);

        Optional<SnapshotResolution> read = SnapshotMetadataStore.read(metaDir, "io.codiqo", "codiqo-api", "1.0-SNAPSHOT");
        assertTrue(read.isPresent());

        SnapshotResolution actual = read.get();
        assertEquals("1.0-20240105.143000-6", actual.getResolvedVersion());
        assertEquals(Instant.parse("2024-01-05T14:30:00Z"), actual.getDeployedAt());
        assertEquals(6, actual.getBuildNumber());
        assertEquals("gar-snapshots", actual.getRepositoryId());
        assertEquals("https://europe-maven.pkg.dev/proj/snapshots", actual.getRepositoryUrl());
        assertEquals(Instant.parse("2024-01-05T15:00:00Z"), actual.getTargetTimestamp());
        assertEquals(1800L, actual.getStaleSeconds());
    }
    @Test
    void readsEmptyForMissingCoordinate() {
        assertTrue(SnapshotMetadataStore.read(metaDir, "io.codiqo", "missing", "9.9-SNAPSHOT").isEmpty());
    }
    @Test
    void roundTripsWithoutOptionalFields() {
        SnapshotResolution written = SnapshotResolution.builder()
                .resolvedVersion("2.0-20240202.020202-1")
                .deployedAt(Instant.parse("2024-02-02T02:02:02Z"))
                .targetTimestamp(Instant.parse("2024-02-02T03:00:00Z"))
                .staleSeconds(3478L)
                .build();
        SnapshotMetadataStore.write(metaDir, "com.example", "lib", "2.0-SNAPSHOT", written);

        SnapshotResolution actual = SnapshotMetadataStore.read(metaDir, "com.example", "lib", "2.0-SNAPSHOT").orElseThrow();
        assertEquals("2.0-20240202.020202-1", actual.getResolvedVersion());
        assertNull(actual.getBuildNumber());
        assertNull(actual.getRepositoryId());
        assertNull(actual.getRepositoryUrl());
        assertEquals(3478L, actual.getStaleSeconds());
    }
}
