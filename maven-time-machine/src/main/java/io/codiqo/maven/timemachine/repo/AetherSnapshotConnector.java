package io.codiqo.maven.timemachine.repo;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.MetadataResolver;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Fallback connector that fetches {@code maven-metadata.xml} from any standard Maven repository
 * (http/https/file/wagon-backed). Specialized connectors (e.g. {@link GoogleArtifactRegistryConnector})
 * are tried first via {@code @Priority} ordering; this one matches anything they don't.
 *
 * <p>Most public repositories publish only the latest snapshot in {@code <snapshotVersions>}; this
 * connector returns whatever the repo exposes, and the orchestrator picks the closest-before-target.
 */
@Named
@Singleton
@Slf4j
public class AetherSnapshotConnector implements SnapshotConnector {
    private static final String MAVEN_METADATA_XML = "maven-metadata.xml";

    private final MetadataResolver metadataResolver;
    private final Map<String, List<SnapshotVersion>> deploysByFolder = new ConcurrentHashMap<>();

    @Inject
    public AetherSnapshotConnector(MetadataResolver metadataResolver) {
        this.metadataResolver = Objects.requireNonNull(metadataResolver);
    }
    @Override
    public boolean supports(RemoteRepository repo) {
        return true;
    }
    @Override
    public List<SnapshotVersion> listDeploys(RepositorySystemSession session, Artifact artifact, RemoteRepository repo) {
        String cacheKey = repo.getId() + ":" + artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion();
        return deploysByFolder.computeIfAbsent(cacheKey, k -> fetchDeploys(session, artifact, repo));
    }
    private List<SnapshotVersion> fetchDeploys(RepositorySystemSession session, Artifact artifact, RemoteRepository repo) {
        for (;;) {
            try {
                Optional<File> metadataFile = fetchMetadataFile(session, artifact, repo);
                if (metadataFile.isEmpty()) {
                    return List.of();
                }
                return readSnapshotVersions(metadataFile.get());
            } catch (Exception err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    @SuppressWarnings("deprecation")
    private Optional<File> fetchMetadataFile(RepositorySystemSession session, Artifact artifact, RemoteRepository repo) {
        DefaultMetadata metadata = new DefaultMetadata(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getBaseVersion(),
                MAVEN_METADATA_XML,
                org.eclipse.aether.metadata.Metadata.Nature.SNAPSHOT);
        MetadataRequest request = new MetadataRequest(metadata, repo, null);
        request.setFavorLocalRepository(false);
        request.setDeleteLocalCopyIfMissing(true);

        List<MetadataResult> results = metadataResolver.resolveMetadata(session, Collections.singletonList(request));
        MetadataResult result = results.iterator().next();
        if (Objects.nonNull(result.getException())) {
            log.debug("metadata fetch failed for {}:{}:{} from {}: {}",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getBaseVersion(),
                    repo.getId(),
                    result.getException().getMessage());
            return Optional.empty();
        }
        File file = result.getMetadata().getFile();
        if (Objects.isNull(file) || !file.isFile()) {
            return Optional.empty();
        }
        return Optional.of(file);
    }
    private static List<SnapshotVersion> readSnapshotVersions(File metadataFile) throws Exception {
        try (InputStream is = Files.newInputStream(metadataFile.toPath())) {
            Metadata metadata = new MetadataXpp3Reader().read(is, false);
            Versioning versioning = metadata.getVersioning();
            if (Objects.isNull(versioning)) {
                return Collections.emptyList();
            }
            return versioning.getSnapshotVersions();
        }
    }
}
