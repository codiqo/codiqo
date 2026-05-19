package io.codiqo.maven.timemachine.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.codiqo.maven.timemachine.SnapshotWithMetadata;

class DefaultRepoClientTest {
    private SnapshotConnector connector;
    private DefaultRepoClient client;
    private RemoteRepository repo;
    private RepositorySystemSession session;

    @BeforeEach
    void setUp() {
        connector = mock(SnapshotConnector.class);
        when(connector.supports(any())).thenReturn(true);
        client = new DefaultRepoClient(List.of(connector));
        session = mock(RepositorySystemSession.class);
        repo = new RemoteRepository.Builder("test", "default", "artifactregistry://europe-maven.pkg.dev/p/r").build();
    }

    @Test
    void emptyWhenNoConnectorSupportsRepo() {
        when(connector.supports(any())).thenReturn(false);

        Optional<SnapshotWithMetadata> result = client.closestSnapshotBefore(
                session, jar("1.0-SNAPSHOT"), repo, Instant.parse("2024-01-10T00:00:00Z"));

        assertTrue(result.isEmpty());
        verify(connector, never()).listDeploys(any(), any(), any());
    }

    @Test
    void picksLatestSnapshotDeployedAtOrBeforeTarget() {
        when(connector.listDeploys(any(), any(), any())).thenReturn(List.of(
                deploy("1.0-20240101.120000-1", StringUtils.EMPTY, "jar", "2024-01-01T12:00:00Z"),
                deploy("1.0-20240105.143000-6", StringUtils.EMPTY, "jar", "2024-01-05T14:30:00Z"),
                deploy("1.0-20240115.103045-7", StringUtils.EMPTY, "jar", "2024-01-15T10:30:45Z")));

        Optional<SnapshotWithMetadata> result = client.closestSnapshotBefore(
                session, jar("1.0-SNAPSHOT"), repo, Instant.parse("2024-01-10T00:00:00Z"));

        assertTrue(result.isPresent());
        assertEquals("1.0-20240105.143000-6", result.get().getVersion());
        assertEquals(Instant.parse("2024-01-05T14:30:00Z"), result.get().getDeployedAt());
    }

    @Test
    void filtersByClassifierAndExtension() {
        when(connector.listDeploys(any(), any(), any())).thenReturn(List.of(
                deploy("1.0-20240105.143000-6", StringUtils.EMPTY, "jar", "2024-01-05T14:30:00Z"),
                deploy("1.0-20240103.103000-5", "sources", "jar", "2024-01-03T10:30:00Z"),
                deploy("1.0-20240108.150000-7", "javadoc", "jar", "2024-01-08T15:00:00Z"),
                deploy("1.0-20240108.150000-7", StringUtils.EMPTY, "pom", "2024-01-08T15:00:00Z")));

        Optional<SnapshotWithMetadata> result = client.closestSnapshotBefore(
                session,
                new DefaultArtifact("a", "b", "sources", "jar", "1.0-SNAPSHOT"),
                repo,
                Instant.parse("2024-01-10T00:00:00Z"));

        assertTrue(result.isPresent());
        assertEquals("1.0-20240103.103000-5", result.get().getVersion());
    }

    @Test
    void emptyWhenNothingDeployedBeforeTarget() {
        when(connector.listDeploys(any(), any(), any())).thenReturn(List.of(
                deploy("1.0-20240115.103045-7", StringUtils.EMPTY, "jar", "2024-01-15T10:30:45Z")));

        Optional<SnapshotWithMetadata> result = client.closestSnapshotBefore(
                session, jar("1.0-SNAPSHOT"), repo, Instant.parse("2024-01-10T00:00:00Z"));

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyOnConnectorException() {
        when(connector.listDeploys(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        Optional<SnapshotWithMetadata> result = client.closestSnapshotBefore(
                session, jar("1.0-SNAPSHOT"), repo, Instant.parse("2024-01-10T00:00:00Z"));

        assertTrue(result.isEmpty());
    }

    @Test
    void firstSupportingConnectorWins() {
        SnapshotConnector second = mock(SnapshotConnector.class);
        when(connector.listDeploys(any(), any(), any())).thenReturn(List.of(
                deploy("1.0-20240105.143000-6", StringUtils.EMPTY, "jar", "2024-01-05T14:30:00Z")));
        DefaultRepoClient twoConnectors = new DefaultRepoClient(List.of(connector, second));

        Optional<SnapshotWithMetadata> result = twoConnectors.closestSnapshotBefore(
                session, jar("1.0-SNAPSHOT"), repo, Instant.parse("2024-01-10T00:00:00Z"));

        assertTrue(result.isPresent());
        assertFalse(result.isEmpty());
        verify(second, never()).listDeploys(any(), any(), any());
    }

    private static Artifact jar(String version) {
        return new DefaultArtifact("a", "b", StringUtils.EMPTY, "jar", version);
    }

    private static SnapshotVersion deploy(String version, String classifier, String extension, String instant) {
        SnapshotVersion sv = new SnapshotVersion();
        sv.setVersion(version);
        sv.setClassifier(classifier);
        sv.setExtension(extension);
        sv.setUpdated(SnapshotConnector.format(Instant.parse(instant)));
        return sv;
    }
}
