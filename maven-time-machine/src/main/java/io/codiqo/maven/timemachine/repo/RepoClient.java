package io.codiqo.maven.timemachine.repo;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;

import io.codiqo.maven.timemachine.SnapshotWithMetadata;

public interface RepoClient {
    Optional<SnapshotWithMetadata> closestSnapshotBefore(RepositorySystemSession session, Artifact artifact, RemoteRepository repo, Instant target);
}
