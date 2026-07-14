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
    private final Map<CacheKey, SnapshotWithMetadata> cache = new ConcurrentHashMap<>();

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
        Instant commitTimestamp = targetOpt.get();
        Duration offset = TimeMachineConfig.targetOffset();
        Instant target = commitTimestamp.minus(offset);

        /**
         * keyed by artifact AND effective target: in the analyzing (host) JVM the resolver stays registered across
         * commits and back-off rungs, so the same artifact may legitimately pin to different deploys per target
         */
        SnapshotWithMetadata pick = cache.computeIfAbsent(new CacheKey(artifact, target), k -> resolvePick(session, artifact, request.getRepositories(), target));
        if (Objects.isNull(pick)) {
            log.warn("no snapshot of {}:{}:{} found before or after {} — falling back to default resolver",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getBaseVersion(),
                    target);
            return delegate.resolveVersion(session, request);
        }

        /**
         * hardening: if the closest snapshot we can pin is deployed well AFTER the commit (beyond the forward
         * window), we cannot reproduce the commit's dependencies — this degenerates into "use the latest snapshot".
         * fail loudly rather than silently building against a far-forward snapshot, so a resolution gap (e.g. the
         * GAR REST connector not engaging and the metadata fallback exposing only the latest deploy) surfaces as a
         * clear time-machine error instead of a misleading downstream compilation failure.
         */
        Duration forwardGap = Duration.between(target, pick.getDeployedAt());
        if (forwardGap.compareTo(TimeMachineConfig.forwardWindow()) > 0) {
            throw new VersionResolutionException(new VersionResult(request), String.format(
                    "time-machine: no snapshot of %s:%s:%s deployed at or before %s%s; nearest is %s (deployed %s, %s after the target — beyond the forward window %s). "
                            + "refusing to build against a far-forward snapshot: verify the artifact registry exposes historical snapshot deploys "
                            + "(a *-maven.pkg.dev registry uses the GAR REST connector; other repositories expose only the latest snapshot in maven-metadata).",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getBaseVersion(),
                    target,
                    offsetSuffix(offset),
                    pick.getVersion(),
                    pick.getDeployedAt(),
                    forwardGap,
                    TimeMachineConfig.forwardWindow()));
        }

        logResult(artifact, target, offset, pick);
        if (isExternalArtifact(session, artifact)) {
            TimeMachineConfig.metaDir().ifPresent(metaDir -> writeMetadata(artifact, commitTimestamp, offset, pick, metaDir));
        }

        VersionResult result = new VersionResult(request);
        result.setVersion(pick.getVersion());
        result.setRepository(pick.getRepository());
        return result;
    }
    private SnapshotWithMetadata resolvePick(RepositorySystemSession session, Artifact artifact, List<RemoteRepository> repositories, Instant target) {
        SnapshotWithMetadata backward = pickClosest(session, artifact, repositories, target, true);
        if (Objects.nonNull(backward)) {
            return backward;
        }
        return pickClosest(session, artifact, repositories, target, false);
    }
    private SnapshotWithMetadata pickClosest(RepositorySystemSession session, Artifact artifact, List<RemoteRepository> repositories, Instant target, boolean before) {
        SnapshotWithMetadata best = null;
        for (RemoteRepository repo : repositories) {
            if (isSnapshotEnabled(repo)) {
                Optional<SnapshotWithMetadata> found = before
                        ? repoClient.closestSnapshotBefore(session, artifact, repo, target)
                        : repoClient.closestSnapshotAfter(session, artifact, repo, target);
                SnapshotWithMetadata candidate = found.orElse(null);
                if (Objects.nonNull(candidate) && isCloser(candidate, best, target)) {
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
    private static boolean isCloser(SnapshotWithMetadata candidate, SnapshotWithMetadata incumbent, Instant target) {
        if (Objects.isNull(incumbent)) {
            return true;
        }
        Duration candidateGap = Duration.between(candidate.getDeployedAt(), target).abs();
        Duration incumbentGap = Duration.between(incumbent.getDeployedAt(), target).abs();
        return candidateGap.compareTo(incumbentGap) < 0;
    }
    private static void logResult(Artifact artifact, Instant target, Duration offset, SnapshotWithMetadata pick) {
        boolean forward = pick.getDeployedAt().isAfter(target);
        Duration gap = Duration.between(target, pick.getDeployedAt()).abs();
        Duration threshold = forward ? TimeMachineConfig.forwardWindow() : TimeMachineConfig.maxStaleness();
        if (gap.compareTo(threshold) > 0) {
            log.warn("{}:{}:{} pinned {} to {} ({} {}; threshold {}){}",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getBaseVersion(),
                    forward ? "forward" : "backward",
                    pick.getVersion(),
                    gap,
                    forward ? "after target" : "stale",
                    threshold,
                    offsetSuffix(offset));
        } else {
            log.info("{}:{}:{} -> {} ({} {} from {}){}",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getBaseVersion(),
                    pick.getVersion(),
                    forward ? "deployed after target" : "deployed",
                    pick.getDeployedAt(),
                    pick.getRepository().getId(),
                    offsetSuffix(offset));
        }
    }
    private static String offsetSuffix(Duration offset) {
        return offset.isZero() ? "" : String.format(" (target offset %s)", offset);
    }
    private static void writeMetadata(Artifact artifact, Instant commitTimestamp, Duration offset, SnapshotWithMetadata pick, File metaDir) {
        RemoteRepository repo = pick.getRepository();
        var resolution = SnapshotMetadataStore.SnapshotResolution
                .builder()
                .resolvedVersion(pick.getVersion())
                .deployedAt(pick.getDeployedAt())
                .buildNumber(parseBuildNumber(pick.getVersion()))
                .repositoryId(repo.getId())
                .repositoryUrl(repo.getUrl())
                .targetTimestamp(commitTimestamp)
                .staleSeconds(Duration.between(pick.getDeployedAt(), commitTimestamp).getSeconds())
                .targetOffsetSeconds(offset.getSeconds())
                .build();

        SnapshotMetadataStore.write(metaDir, artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion(), resolution);
    }
    private static Integer parseBuildNumber(String version) {
        Matcher matcher = BUILD_NUMBER_PATTERN.matcher(version);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private record CacheKey(Artifact artifact, Instant target) {}
}
