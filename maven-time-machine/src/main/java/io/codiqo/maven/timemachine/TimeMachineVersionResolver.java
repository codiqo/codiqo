package io.codiqo.maven.timemachine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.VersionResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.resolution.VersionRequest;
import org.eclipse.aether.resolution.VersionResolutionException;
import org.eclipse.aether.resolution.VersionResult;
import org.eclipse.sisu.Priority;

import io.codiqo.maven.timemachine.repo.RepoClient;
import lombok.extern.slf4j.Slf4j;

@Named("codiqo-time-machine")
@Singleton
@Priority(TimeMachineVersionResolver.RESOLVER_PRIORITY)
@Slf4j
public class TimeMachineVersionResolver implements VersionResolver {
    // must outrank Maven's default VersionResolver (priority 0) so Sisu picks us up first
    static final int RESOLVER_PRIORITY = 20;

    private final VersionResolver delegate;
    private final RepoClient repoClient;
    private final Map<Artifact, SnapshotWithMetadata> cache = new ConcurrentHashMap<>();

    @Inject
    public TimeMachineVersionResolver(@Named("default") VersionResolver delegate, RepoClient repoClient) {
        this.delegate = Objects.requireNonNull(delegate);
        this.repoClient = Objects.requireNonNull(repoClient);
    }
    @Override
    public VersionResult resolveVersion(RepositorySystemSession session, VersionRequest request) throws VersionResolutionException {
        Optional<Instant> targetOpt = TimeMachineConfig.targetTimestamp();
        Artifact artifact = request.getArtifact();
        if (targetOpt.isEmpty() || !artifact.isSnapshot() || resolvedByWorkspace(session, artifact)) {
            return delegate.resolveVersion(session, request);
        }
        Instant target = targetOpt.get();
        SnapshotWithMetadata pick = cache.computeIfAbsent(artifact, a -> lookup(session, a, request.getRepositories(), target));
        if (Objects.isNull(pick)) {
            log.warn("no snapshot of {}:{}:{} deployed before {} — falling back to default resolver",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getBaseVersion(),
                    target);
            return delegate.resolveVersion(session, request);
        }
        logResult(artifact, target, pick);

        VersionResult result = new VersionResult(request);
        result.setVersion(pick.getVersion());
        result.setRepository(pick.getRepository());
        return result;
    }
    private SnapshotWithMetadata lookup(RepositorySystemSession session, Artifact artifact, List<RemoteRepository> repositories, Instant target) {
        SnapshotWithMetadata best = null;
        for (RemoteRepository repo : repositories) {
            if (isSnapshotEnabled(repo)) {
                SnapshotWithMetadata candidate = repoClient.closestSnapshotBefore(session, artifact, repo, target).orElse(null);
                if (Objects.nonNull(candidate) && isBetter(candidate, best)) {
                    best = candidate;
                }
            }
        }
        return best;
    }
    private static boolean resolvedByWorkspace(RepositorySystemSession session, Artifact artifact) {
        WorkspaceReader workspace = session.getWorkspaceReader();
        return Objects.nonNull(workspace) && workspace.findVersions(artifact).contains(artifact.getVersion());
    }
    private static boolean isSnapshotEnabled(RemoteRepository repo) {
        RepositoryPolicy policy = repo.getPolicy(true);
        return Objects.isNull(policy) || policy.isEnabled();
    }
    private static boolean isBetter(SnapshotWithMetadata candidate, SnapshotWithMetadata incumbent) {
        return Objects.isNull(incumbent) || candidate.getDeployedAt().isAfter(incumbent.getDeployedAt());
    }
    private static void logResult(Artifact artifact, Instant target, SnapshotWithMetadata pick) {
        Duration gap = Duration.between(pick.getDeployedAt(), target);
        Duration max = TimeMachineConfig.maxStaleness();
        if (gap.compareTo(max) > 0) {
            log.warn("{}:{}:{} pinned to {} ({} stale; threshold {})",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getBaseVersion(),
                    pick.getVersion(),
                    gap,
                    max);
        } else {
            log.info("{}:{}:{} -> {} (deployed {} from {})",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getBaseVersion(),
                    pick.getVersion(),
                    pick.getDeployedAt(),
                    pick.getRepository().getId());
        }
    }
}
