package io.codiqo.maven.timemachine;

import java.time.Instant;

import org.eclipse.aether.repository.RemoteRepository;

import lombok.Value;

@Value
public class SnapshotWithMetadata {
    String version;
    Instant deployedAt;
    RemoteRepository repository;
}
