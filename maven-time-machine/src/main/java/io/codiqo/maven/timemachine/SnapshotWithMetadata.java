package io.codiqo.maven.timemachine;

import java.time.Instant;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.eclipse.aether.repository.RemoteRepository;


public final class SnapshotWithMetadata extends ImmutableTriple<String, Instant, RemoteRepository> {
    public SnapshotWithMetadata(String version, Instant deployedAt, RemoteRepository repository) {
        super(version, deployedAt, repository);
    }
    public String getVersion() {
        return getLeft();
    }
    public Instant getDeployedAt() {
        return getMiddle();
    }
    public RemoteRepository getRepository() {
        return getRight();
    }
}
