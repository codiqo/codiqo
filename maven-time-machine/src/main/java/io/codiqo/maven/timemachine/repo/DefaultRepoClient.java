package io.codiqo.maven.timemachine.repo;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;

import io.codiqo.maven.timemachine.SnapshotWithMetadata;
import lombok.extern.slf4j.Slf4j;

@Named
@Singleton
@Slf4j
public class DefaultRepoClient implements RepoClient {
    private final List<SnapshotConnector> connectors;

    @Inject
    public DefaultRepoClient(List<SnapshotConnector> connectors) {
        this.connectors = Objects.requireNonNull(connectors);
    }
    @Override
    public Optional<SnapshotWithMetadata> closestSnapshotBefore(RepositorySystemSession session, Artifact artifact, RemoteRepository repo, Instant target) {
        SnapshotConnector connector = connectors.stream().filter(c -> c.supports(repo)).findFirst().orElse(null);
        if (Objects.isNull(connector)) {
            log.debug("no connector supports {} ({}); skipping", repo.getId(), repo.getUrl());
            return Optional.empty();
        }
        List<SnapshotVersion> deploys;
        try {
            deploys = connector.listDeploys(session, artifact, repo);
        } catch (Exception err) {
            log.warn("failed to enumerate snapshots in {} for {}: {}", repo.getId(), artifact, err.getMessage());
            return Optional.empty();
        }
        return deploys.stream()
                .filter(sv -> matches(sv, artifact))
                .map(sv -> Map.entry(sv, SnapshotConnector.parse(sv.getUpdated())))
                .filter(entry -> !entry.getValue().isAfter(target))
                .max(Comparator.comparing(Map.Entry::getValue))
                .map(entry -> new SnapshotWithMetadata(entry.getKey().getVersion(), entry.getValue(), repo));
    }
    private static boolean matches(SnapshotVersion sv, Artifact artifact) {
        String wantClassifier = Optional.ofNullable(artifact.getClassifier()).orElse(StringUtils.EMPTY);
        String wantExtension = Optional.ofNullable(artifact.getExtension()).orElse("jar");
        return wantClassifier.equals(StringUtils.defaultString(sv.getClassifier())) && wantExtension.equals(StringUtils.defaultIfBlank(sv.getExtension(), "jar"));
    }
}
