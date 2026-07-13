package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.maven.timemachine.SnapshotMetadataStore;

class TimeMachineBackoffTest {
    private static final List<Duration> LADDER = List.of(
            Duration.ZERO, Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(4), Duration.ofDays(1));
    private static final Instant COMMIT = Instant.parse("2024-01-10T00:00:00Z");

    @TempDir
    private File metaDir;

    @Test
    void picksFirstRungWhoseWindowContainsAPick() {
        Optional<Duration> next = TimeMachineBackoff.nextOffset(
                LADDER, Duration.ZERO, COMMIT, List.of(COMMIT.minus(Duration.ofMinutes(10))));
        assertEquals(Optional.of(Duration.ofMinutes(15)), next);
    }
    @Test
    void skipsRungsThatChangeNothing() {
        // deploy 3h before the commit: the 15m and 1h rungs still resolve it, 4h is the first that excludes it
        Optional<Duration> next = TimeMachineBackoff.nextOffset(
                LADDER, Duration.ZERO, COMMIT, List.of(COMMIT.minus(Duration.ofHours(3))));
        assertEquals(Optional.of(Duration.ofHours(4)), next);
    }
    @Test
    void considersOnlyRungsAboveThePreviousOffset() {
        Optional<Duration> next = TimeMachineBackoff.nextOffset(
                LADDER, Duration.ofMinutes(15), COMMIT, List.of(COMMIT.minus(Duration.ofMinutes(10))));
        assertEquals(Optional.of(Duration.ofHours(1)), next);
    }
    @Test
    void deployExactlyAtRungBoundaryDoesNotQualifyForThatRung() {
        // the resolver's closest-before is inclusive at the shifted target, so a deploy exactly at
        // commit-15m is still picked by the 15m rung — only the 1h rung excludes it
        Optional<Duration> next = TimeMachineBackoff.nextOffset(
                LADDER, Duration.ZERO, COMMIT, List.of(COMMIT.minus(Duration.ofMinutes(15))));
        assertEquals(Optional.of(Duration.ofHours(1)), next);
    }
    @Test
    void ignoresForwardPicks() {
        Optional<Duration> next = TimeMachineBackoff.nextOffset(
                LADDER, Duration.ZERO, COMMIT, List.of(COMMIT.plus(Duration.ofHours(2))));
        assertTrue(next.isEmpty());
    }
    @Test
    void emptyDeploysYieldNoRung() {
        assertTrue(TimeMachineBackoff.nextOffset(LADDER, Duration.ZERO, COMMIT, List.of()).isEmpty());
    }
    @Test
    void deploysOlderThanTheLargestRungYieldNoRung() {
        Optional<Duration> next = TimeMachineBackoff.nextOffset(
                LADDER, Duration.ZERO, COMMIT, List.of(COMMIT.minus(Duration.ofDays(3))));
        assertTrue(next.isEmpty());
    }
    @Test
    void readsPickedDeploysFromSidecars() {
        Instant first = Instant.parse("2024-01-09T23:45:00Z");
        Instant second = Instant.parse("2024-01-08T12:00:00Z");
        SnapshotMetadataStore.write(metaDir, "io.codiqo", "a", "1.0-SNAPSHOT", resolution(first));
        SnapshotMetadataStore.write(metaDir, "io.codiqo", "b", "2.0-SNAPSHOT", resolution(second));

        List<Instant> deploys = TimeMachineBackoff.readPickedDeploys(metaDir);

        assertEquals(2, deploys.size());
        assertTrue(deploys.containsAll(List.of(first, second)));
    }
    @Test
    void readsEmptyForMissingOrNullDir() {
        assertTrue(TimeMachineBackoff.readPickedDeploys(null).isEmpty());
        assertTrue(TimeMachineBackoff.readPickedDeploys(new File(metaDir, "absent")).isEmpty());
    }
    @Test
    void ignoresNonPropertiesFiles() throws IOException {
        Files.writeString(new File(metaDir, "notes.txt").toPath(), "snapshot.deployedAt=2024-01-01T00:00:00Z");
        assertTrue(TimeMachineBackoff.readPickedDeploys(metaDir).isEmpty());
    }
    private static SnapshotMetadataStore.SnapshotResolution resolution(Instant deployedAt) {
        return SnapshotMetadataStore.SnapshotResolution.builder()
                .resolvedVersion("1.0-20240105.143000-6")
                .deployedAt(deployedAt)
                .targetTimestamp(COMMIT)
                .staleSeconds(Duration.between(deployedAt, COMMIT).getSeconds())
                .build();
    }
}
