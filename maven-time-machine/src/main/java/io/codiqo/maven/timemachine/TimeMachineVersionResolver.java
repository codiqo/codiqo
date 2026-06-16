package io.codiqo.maven.timemachine;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

@Slf4j
@Singleton
@Named("codiqo-time-machine")
@Priority(TimeMachineVersionResolver.RESOLVER_PRIORITY)
public class TimeMachineVersionResolver implements VersionResolver {
    public static final int RESOLVER_PRIORITY = Byte.MAX_VALUE;

    /**
     * trailing build number of a unique snapshot version, e.g. "1.0-20240105.143000-6"
     */
    private static final Pattern BUILD_NUMBER_PATTERN = Pattern.compile("-(\\d+)$");

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
        if (isExternalArtifact(session, artifact)) {
            TimeMachineConfig.metaDir().ifPresent(metaDir -> writeMetadata(artifact, target, pick, metaDir));
        }

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
    private static boolean isExternalArtifact(RepositorySystemSession session, Artifact artifact) {
        WorkspaceReader workspace = session.getWorkspaceReader();
        return Objects.isNull(workspace) || workspace.findVersions(artifact).isEmpty();
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
    private static void writeMetadata(Artifact artifact, Instant target, SnapshotWithMetadata pick, File metaDir) {
        RemoteRepository repo = pick.getRepository();
        var resolution = SnapshotMetadataStore.SnapshotResolution
                .builder()
                .resolvedVersion(pick.getVersion())
                .deployedAt(pick.getDeployedAt())
                .buildNumber(parseBuildNumber(pick.getVersion()))
                .repositoryId(repo.getId())
                .repositoryUrl(repo.getUrl())
                .targetTimestamp(target)
                .staleSeconds(Duration.between(pick.getDeployedAt(), target).getSeconds())
                .build();

        SnapshotMetadataStore.write(metaDir, artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion(), resolution);
    }
    private static Integer parseBuildNumber(String version) {
        Matcher matcher = BUILD_NUMBER_PATTERN.matcher(version);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
