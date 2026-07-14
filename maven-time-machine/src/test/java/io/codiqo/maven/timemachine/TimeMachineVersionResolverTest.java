package io.codiqo.maven.timemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.VersionResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.resolution.VersionRequest;
import org.eclipse.aether.resolution.VersionResolutionException;
import org.eclipse.aether.resolution.VersionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.codiqo.maven.timemachine.repo.RepoClient;

class TimeMachineVersionResolverTest {
    private VersionResolver delegate;
    private RepoClient client;
    private RemoteRepository repo;
    private TimeMachineVersionResolver resolver;
    private RepositorySystemSession session;

    @TempDir
    Path metaDir;

    @BeforeEach
    void setUp() {
        delegate = mock(VersionResolver.class);
        client = mock(RepoClient.class);
        session = mock(RepositorySystemSession.class);
        repo = new RemoteRepository.Builder("test", "default", "http://example.com/repo").build();
        resolver = new TimeMachineVersionResolver(delegate, client);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP);
        System.clearProperty(TimeMachineConfig.PROP_META_DIR);
        System.clearProperty(TimeMachineConfig.PROP_TARGET_OFFSET);
    }

    @Test
    void delegatesWhenNoTimestampConfigured() throws VersionResolutionException {
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        VersionResult expected = new VersionResult(request);
        when(delegate.resolveVersion(session, request)).thenReturn(expected);

        VersionResult result = resolver.resolveVersion(session, request);

        assertSame(expected, result);
        verify(client, never()).closestSnapshotBefore(any(), any(), any(), any());
    }

    @Test
    void delegatesForNonSnapshotArtifact() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        VersionRequest request = requestFor(new DefaultArtifact("com.example:dep:1.0"));
        VersionResult expected = new VersionResult(request);
        when(delegate.resolveVersion(session, request)).thenReturn(expected);

        VersionResult result = resolver.resolveVersion(session, request);

        assertSame(expected, result);
        verify(client, never()).closestSnapshotBefore(any(), any(), any(), any());
    }

    @Test
    void delegatesWhenWorkspaceHasArtifact() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        WorkspaceReader workspace = mock(WorkspaceReader.class);
        when(session.getWorkspaceReader()).thenReturn(workspace);
        when(workspace.findVersions(any())).thenReturn(Collections.singletonList("1.0-SNAPSHOT"));
        VersionResult expected = new VersionResult(request);
        when(delegate.resolveVersion(session, request)).thenReturn(expected);

        VersionResult result = resolver.resolveVersion(session, request);

        assertSame(expected, result);
        verify(client, never()).closestSnapshotBefore(any(), any(), any(), any());
    }

    @Test
    void interceptsAndReturnsTimestampedVersion() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        Instant deployedAt = Instant.parse("2024-01-05T14:30:00Z");
        when(client.closestSnapshotBefore(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240105.143000-6", deployedAt, repo)));

        VersionResult result = resolver.resolveVersion(session, request);

        assertEquals("1.0-20240105.143000-6", result.getVersion());
        assertSame(repo, result.getRepository());
        verify(delegate, never()).resolveVersion(any(), any());
    }

    @Test
    void fallsBackToDelegateWhenNoMatchBeforeOrAfter() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        when(client.closestSnapshotBefore(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(client.closestSnapshotAfter(any(), any(), any(), any())).thenReturn(Optional.empty());
        VersionResult expected = new VersionResult(request);
        when(delegate.resolveVersion(session, request)).thenReturn(expected);

        VersionResult result = resolver.resolveVersion(session, request);

        assertSame(expected, result);
        verify(delegate, times(1)).resolveVersion(session, request);
    }

    @Test
    void usesForwardPickWhenNothingBeforeTarget() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        when(client.closestSnapshotBefore(any(), any(), any(), any())).thenReturn(Optional.empty());
        Instant deployedAt = Instant.parse("2024-01-10T06:00:00Z");
        when(client.closestSnapshotAfter(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240110.060000-1", deployedAt, repo)));

        VersionResult result = resolver.resolveVersion(session, request);

        assertEquals("1.0-20240110.060000-1", result.getVersion());
        assertSame(repo, result.getRepository());
        verify(delegate, never()).resolveVersion(any(), any());
    }

    @Test
    void failsWhenNearestSnapshotIsFarForwardBeyondWindow() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        when(client.closestSnapshotBefore(any(), any(), any(), any())).thenReturn(Optional.empty());
        // nearest snapshot is deployed ~8 days after the commit — far beyond the P1D forward window; must fail loudly
        Instant deployedAt = Instant.parse("2024-01-18T00:00:00Z");
        when(client.closestSnapshotAfter(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240118.000000-99", deployedAt, repo)));

        assertThrows(VersionResolutionException.class, () -> resolver.resolveVersion(session, request));
        verify(delegate, never()).resolveVersion(any(), any());
    }

    @Test
    void writesMetadataForForwardPick() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        System.setProperty(TimeMachineConfig.PROP_META_DIR, metaDir.toString());
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        when(client.closestSnapshotBefore(any(), any(), any(), any())).thenReturn(Optional.empty());
        Instant deployedAt = Instant.parse("2024-01-10T06:00:00Z");
        when(client.closestSnapshotAfter(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240110.060000-1", deployedAt, repo)));

        VersionResult result = resolver.resolveVersion(session, request);

        assertEquals("1.0-20240110.060000-1", result.getVersion());
        assertEquals(1, metadataFileCount(metaDir));
    }

    @Test
    void skipsMetadataForReactorArtifactAtDifferentVersion() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        System.setProperty(TimeMachineConfig.PROP_META_DIR, metaDir.toString());
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        WorkspaceReader workspace = mock(WorkspaceReader.class);
        when(session.getWorkspaceReader()).thenReturn(workspace);
        when(workspace.findVersions(any())).thenReturn(Collections.singletonList("2.0-SNAPSHOT"));
        Instant deployedAt = Instant.parse("2024-01-05T14:30:00Z");
        when(client.closestSnapshotBefore(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240105.143000-6", deployedAt, repo)));

        VersionResult result = resolver.resolveVersion(session, request);

        assertEquals("1.0-20240105.143000-6", result.getVersion());
        verify(delegate, never()).resolveVersion(any(), any());
        assertEquals(0, metadataFileCount(metaDir));
    }

    @Test
    void writesMetadataForExternalArtifact() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        System.setProperty(TimeMachineConfig.PROP_META_DIR, metaDir.toString());
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        Instant deployedAt = Instant.parse("2024-01-05T14:30:00Z");
        when(client.closestSnapshotBefore(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240105.143000-6", deployedAt, repo)));

        VersionResult result = resolver.resolveVersion(session, request);

        assertEquals("1.0-20240105.143000-6", result.getVersion());
        assertEquals(1, metadataFileCount(metaDir));
        assertEquals(0L, SnapshotMetadataStore.read(metaDir.toFile(), "com.example", "dep", "1.0-SNAPSHOT").orElseThrow().getTargetOffsetSeconds());
    }

    @Test
    void offsetShiftsSelectionTarget() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        System.setProperty(TimeMachineConfig.PROP_TARGET_OFFSET, "PT1H");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        Instant deployedAt = Instant.parse("2024-01-05T14:30:00Z");
        when(client.closestSnapshotBefore(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240105.143000-6", deployedAt, repo)));

        resolver.resolveVersion(session, request);

        ArgumentCaptor<Instant> target = ArgumentCaptor.forClass(Instant.class);
        verify(client).closestSnapshotBefore(any(), any(), any(), target.capture());
        assertEquals(Instant.parse("2024-01-09T23:00:00Z"), target.getValue());
    }

    @Test
    void metadataKeepsCommitTimestampUnderOffset() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        System.setProperty(TimeMachineConfig.PROP_TARGET_OFFSET, "PT1H");
        System.setProperty(TimeMachineConfig.PROP_META_DIR, metaDir.toString());
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        Instant deployedAt = Instant.parse("2024-01-05T14:30:00Z");
        when(client.closestSnapshotBefore(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240105.143000-6", deployedAt, repo)));

        resolver.resolveVersion(session, request);

        SnapshotMetadataStore.SnapshotResolution resolution =
                SnapshotMetadataStore.read(metaDir.toFile(), "com.example", "dep", "1.0-SNAPSHOT").orElseThrow();
        assertEquals(Instant.parse("2024-01-10T00:00:00Z"), resolution.getTargetTimestamp());
        assertEquals(379800L, resolution.getStaleSeconds());
        assertEquals(3600L, resolution.getTargetOffsetSeconds());
    }

    @Test
    void repinsWhenTargetOffsetChanges() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        when(client.closestSnapshotBefore(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240109.235000-7", Instant.parse("2024-01-09T23:50:00Z"), repo)))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240105.143000-6", Instant.parse("2024-01-05T14:30:00Z"), repo)));

        assertEquals("1.0-20240109.235000-7", resolver.resolveVersion(session, snapshotRequest("1.0-SNAPSHOT")).getVersion());

        // same resolver instance, shifted target — the host JVM pins per commit/rung, so the cache must not reuse the offset-0 pick
        System.setProperty(TimeMachineConfig.PROP_TARGET_OFFSET, "PT4H");
        assertEquals("1.0-20240105.143000-6", resolver.resolveVersion(session, snapshotRequest("1.0-SNAPSHOT")).getVersion());
        verify(client, times(2)).closestSnapshotBefore(any(), any(), any(), any());
    }

    @Test
    void forwardWindowMeasuredFromShiftedTargetRejectsFarForward() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        System.setProperty(TimeMachineConfig.PROP_TARGET_OFFSET, "PT4H");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        when(client.closestSnapshotBefore(any(), any(), any(), any())).thenReturn(Optional.empty());
        // deployed 28h after the shifted target (2024-01-09T20:00) — beyond the P1D forward window
        Instant deployedAt = Instant.parse("2024-01-11T00:00:00Z");
        when(client.closestSnapshotAfter(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240111.000000-9", deployedAt, repo)));

        assertThrows(VersionResolutionException.class, () -> resolver.resolveVersion(session, request));
        verify(delegate, never()).resolveVersion(any(), any());
    }

    @Test
    void forwardWindowMeasuredFromShiftedTargetAcceptsNearPick() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        System.setProperty(TimeMachineConfig.PROP_TARGET_OFFSET, "PT4H");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        when(client.closestSnapshotBefore(any(), any(), any(), any())).thenReturn(Optional.empty());
        // deployed 18h after the shifted target (2024-01-09T20:00) — within the P1D forward window
        Instant deployedAt = Instant.parse("2024-01-10T14:00:00Z");
        when(client.closestSnapshotAfter(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SnapshotWithMetadata("1.0-20240110.140000-2", deployedAt, repo)));

        VersionResult result = resolver.resolveVersion(session, request);

        assertEquals("1.0-20240110.140000-2", result.getVersion());
        verify(delegate, never()).resolveVersion(any(), any());
    }

    private VersionRequest snapshotRequest(String version) {
        return requestFor(new DefaultArtifact("com.example:dep:" + version));
    }

    private VersionRequest requestFor(Artifact artifact) {
        VersionRequest request = new VersionRequest();
        request.setArtifact(artifact);
        request.setRepositories(List.of(repo));
        return request;
    }

    private static int metadataFileCount(Path metaDir) {
        File[] files = metaDir.toFile().listFiles((dir, name) -> name.endsWith(".properties"));
        return Objects.isNull(files) ? 0 : files.length;
    }
}
