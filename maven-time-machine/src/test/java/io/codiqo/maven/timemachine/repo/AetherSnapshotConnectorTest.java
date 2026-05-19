package io.codiqo.maven.timemachine.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.MetadataResolver;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AetherSnapshotConnectorTest {
    private MetadataResolver metadataResolver;
    private AetherSnapshotConnector connector;
    private RepositorySystemSession session;
    private RemoteRepository repo;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        metadataResolver = mock(MetadataResolver.class);
        session = mock(RepositorySystemSession.class);
        connector = new AetherSnapshotConnector(metadataResolver);
        repo = new RemoteRepository.Builder("test", "default", "https://example.com/repo").build();
    }

    @Test
    void supportsAnyRepository() {
        assertTrue(connector.supports(repo));
        assertTrue(connector.supports(new RemoteRepository.Builder("gar", "default", "artifactregistry://x/y/z").build()));
        assertTrue(connector.supports(new RemoteRepository.Builder("file", "default", "file:///tmp/repo").build()));
    }

    @Test
    void returnsAllSnapshotVersionsFromFixture() throws Exception {
        stubMetadataFile(copyFixture("/fixtures/maven-metadata.xml"));

        Artifact artifact = new DefaultArtifact("com.example:dep:1.0-SNAPSHOT");
        List<SnapshotVersion> deploys = connector.listDeploys(session, artifact, repo);

        assertEquals(5, deploys.size(), "fixture has 5 snapshotVersion entries");
        assertTrue(deploys.stream().anyMatch(sv -> "1.0-20231215.091122-5".equals(sv.getVersion())));
        assertTrue(deploys.stream().anyMatch(sv -> "1.0-20240105.143000-6".equals(sv.getVersion())));
        assertTrue(deploys.stream().anyMatch(sv -> "1.0-20240115.103045-7".equals(sv.getVersion())));
    }

    @Test
    void returnsEmptyWhenMetadataFetchFails() {
        stubMetadataException(new MetadataNotFoundException(null, repo, "not found"));

        Artifact artifact = new DefaultArtifact("com.example:dep:1.0-SNAPSHOT");
        List<SnapshotVersion> deploys = connector.listDeploys(session, artifact, repo);

        assertTrue(deploys.isEmpty());
    }

    @Test
    void cachesPerSnapshotFolder() throws Exception {
        stubMetadataFile(copyFixture("/fixtures/maven-metadata.xml"));

        Artifact jar = new DefaultArtifact("com.example", "dep", "", "jar", "1.0-SNAPSHOT");
        Artifact sources = new DefaultArtifact("com.example", "dep", "sources", "jar", "1.0-SNAPSHOT");
        connector.listDeploys(session, jar, repo);
        connector.listDeploys(session, sources, repo);

        verify(metadataResolver, times(1)).resolveMetadata(eq(session), any());
    }

    private Path copyFixture(String resource) throws IOException {
        Path target = tempDir.resolve("maven-metadata.xml");
        Files.copy(NonNull.requireNonNull(getClass().getResourceAsStream(resource)), target);
        return target;
    }

    @SuppressWarnings("deprecation")
    private void stubMetadataFile(Path file) {
        when(metadataResolver.resolveMetadata(eq(session), any())).thenAnswer(invocation -> {
            List<MetadataRequest> requests = invocation.getArgument(1);
            MetadataRequest req = requests.get(0);
            MetadataResult result = new MetadataResult(req);
            Metadata stamped = req.getMetadata().setFile(file.toFile());
            result.setMetadata(stamped);
            return Collections.singletonList(result);
        });
    }

    private void stubMetadataException(Exception exception) {
        when(metadataResolver.resolveMetadata(eq(session), any())).thenAnswer(invocation -> {
            List<MetadataRequest> requests = invocation.getArgument(1);
            MetadataResult result = new MetadataResult(requests.get(0));
            result.setException(exception);
            return Collections.singletonList(result);
        });
    }

    private static final class NonNull {
        static <T> T requireNonNull(T value) {
            if (value == null) {
                throw new IllegalStateException("test resource missing");
            }
            return value;
        }
    }
}
