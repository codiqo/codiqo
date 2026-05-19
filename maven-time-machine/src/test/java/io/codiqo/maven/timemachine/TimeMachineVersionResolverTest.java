package io.codiqo.maven.timemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
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

import io.codiqo.maven.timemachine.repo.RepoClient;

class TimeMachineVersionResolverTest {
    private VersionResolver delegate;
    private RepoClient client;
    private RemoteRepository repo;
    private TimeMachineVersionResolver resolver;
    private RepositorySystemSession session;

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
    void fallsBackToDelegateWhenNoMatch() throws VersionResolutionException {
        System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, "2024-01-10T00:00:00Z");
        VersionRequest request = snapshotRequest("1.0-SNAPSHOT");
        when(client.closestSnapshotBefore(any(), any(), any(), any())).thenReturn(Optional.empty());
        VersionResult expected = new VersionResult(request);
        when(delegate.resolveVersion(session, request)).thenReturn(expected);

        VersionResult result = resolver.resolveVersion(session, request);

        assertSame(expected, result);
        verify(delegate, times(1)).resolveVersion(session, request);
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
}
