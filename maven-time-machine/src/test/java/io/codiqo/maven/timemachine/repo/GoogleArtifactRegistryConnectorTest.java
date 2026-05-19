package io.codiqo.maven.timemachine.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.artifactregistry.v1.ArtifactRegistry;

class GoogleArtifactRegistryConnectorTest {
    private RecordingHttpTransport transport;
    private GoogleArtifactRegistryConnector connector;
    private RemoteRepository garRepo;

    @BeforeEach
    void setUp() {
        transport = new RecordingHttpTransport();
        ArtifactRegistry client = new ArtifactRegistry.Builder(transport, GsonFactory.getDefaultInstance(), request -> {})
                .setApplicationName("test")
                .build();
        connector = new GoogleArtifactRegistryConnector(client);
        garRepo = new RemoteRepository.Builder("artifact-registry", "default", "artifactregistry://europe-maven.pkg.dev/patrianna-dev/nexus").build();
    }

    @Test
    void supportsOnlyArtifactRegistryScheme() {
        assertTrue(connector.supports(garRepo));
        RemoteRepository https = new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build();
        assertFalse(connector.supports(https));
    }

    @Test
    void enumeratesEveryHistoricalBuildFromRealGarPayload() throws Exception {
        transport.respondWith(readFixture("gar-files-bootstrap-core.json"));

        Artifact artifact = new DefaultArtifact("com.turbospaces.boot", "bootstrap-core", StringUtils.EMPTY, "jar", "2.0.97-SNAPSHOT");
        List<SnapshotVersion> deploys = connector.listDeploys(mock(RepositorySystemSession.class), artifact, garRepo);

        Map<String, SnapshotVersion> mainJars = deploys.stream()
                .filter(d -> d.getClassifier().isEmpty() && d.getExtension().equals("jar"))
                .collect(Collectors.toMap(SnapshotVersion::getVersion, Function.identity()));

        assertTrue(mainJars.containsKey("2.0.97-20260508.094343-1"), "first build missing: " + mainJars.keySet());
        assertTrue(mainJars.containsKey("2.0.97-20260514.201829-16"), "build -16 missing: " + mainJars.keySet());
        assertEquals(SnapshotConnector.format(Instant.parse("2026-05-14T20:19:42.162777Z")), mainJars.get("2.0.97-20260514.201829-16").getUpdated());

        boolean checksumsRejected = deploys.stream().noneMatch(d -> d.getExtension().equals("md5") || d.getExtension().equals("sha1"));
        assertTrue(checksumsRejected);

        Set<String> classifiers = deploys.stream().map(SnapshotVersion::getClassifier).collect(Collectors.toSet());
        assertTrue(classifiers.contains("sources"));
        assertTrue(classifiers.contains("javadoc"));
        assertTrue(classifiers.contains("tests"));
    }

    @Test
    void propagatesIOExceptionFromTransport() {
        transport.failWith(new IOException("simulated auth failure"));

        Artifact artifact = new DefaultArtifact("g", "a", StringUtils.EMPTY, "jar", "1.0-SNAPSHOT");
        RepositorySystemSession session = mock(RepositorySystemSession.class);

        IOException err = org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class,
                () -> connector.listDeploys(session, artifact, garRepo));
        org.junit.jupiter.api.Assertions.assertEquals("simulated auth failure", err.getMessage());
    }

    @Test
    void cachesPerSnapshotFolder() {
        transport.respondWith("{}");

        Artifact jar = new DefaultArtifact("g", "a", StringUtils.EMPTY, "jar", "1.0-SNAPSHOT");
        Artifact sourcesJar = new DefaultArtifact("g", "a", "sources", "jar", "1.0-SNAPSHOT");
        Artifact pom = new DefaultArtifact("g", "a", StringUtils.EMPTY, "pom", "1.0-SNAPSHOT");

        RepositorySystemSession session = mock(RepositorySystemSession.class);
        connector.listDeploys(session, jar, garRepo);
        connector.listDeploys(session, sourcesJar, garRepo);
        connector.listDeploys(session, pom, garRepo);

        assertEquals(1, transport.requestCount());
    }

    @Test
    void sendsCorrectFilterAndUrl() {
        transport.respondWith("{}");

        Artifact artifact = new DefaultArtifact("com.turbospaces.boot", "bootstrap-core", StringUtils.EMPTY, "jar", "2.0.97-SNAPSHOT");
        connector.listDeploys(mock(RepositorySystemSession.class), artifact, garRepo);

        URI uri = URI.create(transport.lastUrl());
        assertEquals("artifactregistry.googleapis.com", uri.getHost());
        assertTrue(uri.getPath().contains("/projects/patrianna-dev/locations/europe/repositories/nexus/files"),
                "expected file list path, got: " + uri.getPath());
        String decodedQuery = URLDecoder.decode(uri.getQuery(), StandardCharsets.UTF_8);
        assertTrue(decodedQuery.contains("com/turbospaces/boot/bootstrap-core/2.0.97-SNAPSHOT/*"),
                "filter should target the snapshot folder, got: " + decodedQuery);
    }

    private static String readFixture(String name) throws Exception {
        Path path = Paths.get("src/test/resources/fixtures", name);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static final class RecordingHttpTransport extends MockHttpTransport {
        private final List<String> urls = new ArrayList<>();
        private final AtomicInteger callCount = new AtomicInteger();
        private String body = "{}";
        private IOException failure;

        void respondWith(String raw) {
            this.body = raw;
            this.failure = null;
        }

        void failWith(IOException err) {
            this.failure = err;
        }

        int requestCount() {
            return callCount.get();
        }

        String lastUrl() {
            return urls.get(urls.size() - 1);
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            urls.add(url);
            callCount.incrementAndGet();
            return new MockLowLevelHttpRequest(url) {
                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    if (Objects.nonNull(failure)) {
                        throw failure;
                    }
                    return new MockLowLevelHttpResponse()
                            .setStatusCode(200)
                            .setContentType("application/json")
                            .setContent(body);
                }
            };
        }
    }
}
