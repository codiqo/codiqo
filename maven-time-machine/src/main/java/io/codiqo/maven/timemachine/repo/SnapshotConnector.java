package io.codiqo.maven.timemachine.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;

public interface SnapshotConnector {
    DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    boolean supports(RemoteRepository repo);
    List<SnapshotVersion> listDeploys(RepositorySystemSession session, Artifact artifact, RemoteRepository repo);

    static Instant parse(String value) {
        return LocalDateTime.parse(value, FORMAT).toInstant(ZoneOffset.UTC);
    }
    static String format(Instant value) {
        return FORMAT.format(value);
    }
}
