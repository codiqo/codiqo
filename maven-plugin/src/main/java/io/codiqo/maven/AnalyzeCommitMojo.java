package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedList;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;


import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.util.JGit;
import io.codiqo.util.MemoryReport;

@Mojo(name = "analyze-commit",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class AnalyzeCommitMojo extends AbstractAnalyzeMojo {
    /**
     * back-off ladder mirroring how stale a developer's local snapshot copy may have been (Maven's default
     * updatePolicy is daily): each rung steps the resolution target further back from the commit instant
     */
    private static final List<Duration> TIME_MACHINE_BACKOFF_LADDER = List.of(
            Duration.ZERO, Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(4), Duration.ofDays(1));
    /**
     * per-attempt excerpt of the structured failure detail kept in the exclusion history — enough to carry the
     * actual compiler/model errors (not just the first line) without ballooning the persisted detail
     */
    private static final int ATTEMPT_DETAIL_LIMIT = 4096;
    private static final String ATTEMPT_SEPARATOR = StringUtils.repeat(CharUtils.LF, 2);

    @Parameter(property = "codiqo.commitId", required = true)
    private String commitId;

    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        super.doPrepare(args);

        args.setCommitId(commitId);
        resolveCommit(args, commitId);
    }
    @Override
    protected void doExecute(RunArgs args) throws Exception {
        if (JGit.isMerge(args.getGit(), commitId)) {
            String reason = "merge commit (multiple parents)";
            getLog().warn(String.format("commit %s skipped: %s", commitId, reason));
            doExcludeAnalysis(commitId, reason, AnalysisExcludeCategory.MERGE_COMMIT);
            return;
        }

        if (args.isExcludedAuthor(resolveAuthorEmail(args))) {
            String reason = "author excluded by codiqo.excludeAuthorEmails";
            getLog().warn(String.format("commit %s skipped: %s", commitId, reason));
            doExcludeAnalysis(commitId, reason, AnalysisExcludeCategory.FILTERED_BY_RULES);
            return;
        }

        File temp = Files.createTempDirectory("codiqo").toFile();
        temp.deleteOnExit();

        /**
         * clone the repository to a temporary location to avoid modifying the user's working directory and excluding uncommitted files.
         */
        StopWatch stopWatch = StopWatch.createStarted();
        StoredConfig originalConfig = args.getGit().getConfig();
        args.setDefaultBranch(args.getGit().getBranch());

        String sourceUri = args.getGit().getDirectory().toURI().toString();
        ObjectId sourceHead = args.getGit().resolve(org.eclipse.jgit.lib.Constants.HEAD);

        Repository clone = new FileRepositoryBuilder()
                .setGitDir(new File(temp, ".git"))
                .build();
        clone.create(false);

        StoredConfig initialConfig = clone.getConfig();
        initialConfig.setString("remote", "origin", "url", sourceUri);
        initialConfig.save();

        try (Git tmpGit = Git.wrap(clone)) {
            tmpGit.fetch()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("+refs/*:refs/*"))
                    .call();
        }

        RefUpdate headUpdate = clone.updateRef(org.eclipse.jgit.lib.Constants.HEAD, true);
        headUpdate.setNewObjectId(sourceHead);
        headUpdate.forceUpdate();

        stopWatch.stop();
        getLog().info(String.format("cloned directory: %s for analysis in %s", temp.getAbsolutePath(), stopWatch.toString()));

        /**
         * copy over the remote URLs to the cloned repository to allow for proper resolution of relative URLs during build.
         */
        StoredConfig cloneConfig = clone.getConfig();
        try {
            for (String remote : originalConfig.getSubsections("remote")) {
                String url = originalConfig.getString("remote", remote, "url");
                String fetch = originalConfig.getString("remote", remote, "fetch");
                if (StringUtils.isNotEmpty(url)) {
                    cloneConfig.setString("remote", remote, "url", url);
                    args.getRemoteUrls().add(url);
                }
                if (StringUtils.isNotEmpty(fetch)) {
                    cloneConfig.setString("remote", remote, "fetch", fetch);
                }
            }
        } finally {
            cloneConfig.save();
        }

        try {
            /**
             * checkout the specified commit ID for analysis exactly as it was at that point in time (it is cleaned cloned repository w/o anything untracked).
             */
            args.setGit(clone);
            try (Git git = Git.wrap(clone)) {
                git.clean().setCleanDirectories(true).setForce(true).call();
                git.reset().setMode(ResetCommand.ResetType.HARD).call();

                git.checkout().setForced(true).setName(commitId).call();
                getLog().info(String.format("checked out commit ID: %s", commitId));
            }

            /**
             * attempt to build the project at the specified commit ID (potentially completely different multiple modules structure).
             * this may require different JDK/MVN home since the project's build requirements may have changed since then.
             */
            /**
             * host-side ProjectBuilder calls (pre-flight, root/module model building) resolve snapshot parents and
             * BOM imports in THIS JVM, where the time-machine only applies when the extension is loaded via the host
             * maven.ext.class.path. pin those calls to the commit instant (shifted by the successful attempt's
             * back-off offset) so a later snapshot deploy that breaks historical POM interpolation — e.g. a
             * dependencyManagement removal — cannot fail the analysis of an older commit.
             */
            if (args.isTimeMachineEnabled()) {
                args.setTimeMachineTargetOffset(Duration.ZERO);
                warnIfHostTimeMachineMissing();
            }

            ProjectBuildingRequest buildingReq = Maven.buildingRequest(mavenSession);
            Maven.pinMultiModuleProjectDirectory(buildingReq, clone.getWorkTree());
            if (Objects.nonNull(args.getTimeMachineTargetOffset())) {
                Maven.isolateRepositorySession(buildingReq);
            }
            if (resolveDependenciesOffline(args) instanceof BuildOutcome.Skipped skipped) {
                doDegradedAnalysis(args, skipped.reason(), skipped.category(), skipped.detail());
                return;
            }

            /**
             * build with the time-machine snapshot resolver first, pinning each dependency's snapshot deploy closest
             * to the commit date, so a reproducible commit is analyzed against its commit-date dependencies and emits
             * snapshot metadata. if that build fails — the commit-date snapshot is unavailable, or the source no longer
             * compiles against it — step the resolution target back through the back-off ladder (the developer's
             * daily-updating local repository may never have seen a snapshot deployed shortly before the commit),
             * then fall back to latest snapshots and analyze best-effort. when time-machine is disabled, only the
             * latest build runs.
             */
            BuildOutcome buildOutcome = args.isTimeMachineEnabled()
                    ? buildWithBackoff(args, buildingReq)
                    : buildProject(args, invocationRequest(args, false, Duration.ZERO), buildingReq);
            if (buildOutcome instanceof BuildOutcome.Skipped skipped) {
                doDegradedAnalysis(args, skipped.reason(), skipped.category(), skipped.detail());
                return;
            }
            ProjectBuildingResult result = ((BuildOutcome.Proceeded) buildOutcome).result();

            Collection<MavenProject> reactors = new LinkedList<>();
            Optional<BuildOutcome.Skipped> moduleOutcome = buildAndCollectModules(
                    result.getProject(), clone.getWorkTree(), buildingReq, args, reactors);
            if (moduleOutcome.isPresent()) {
                BuildOutcome.Skipped skipped = moduleOutcome.get();
                doDegradedAnalysis(args, skipped.reason(), skipped.category(), skipped.detail());
                return;
            }
            try (ClassGraphSpec scan = scanProjects(args, reactors)) {
                getLog().info(MemoryReport.snapshot("after classgraph scan"));
                args.setClassGraph(scan);
                super.doExecute(args);
            }
        } finally {
            clone.close();
            FileUtils.deleteDirectory(temp);
        }
    }
    private BuildOutcome buildWithBackoff(RunArgs args, ProjectBuildingRequest buildingReq) throws Exception {
        Instant commitTimestamp = TimeMachineSupport.resolveCommitTimestamp(args);
        List<String> attemptFailures = new ArrayList<>();
        BuildOutcome.Skipped firstSkipped = null;
        Duration offset = Duration.ZERO;
        for (;;) {
            args.setTimeMachineTargetOffset(offset);
            BuildOutcome outcome = buildProject(args, invocationRequest(args, true, offset), buildingReq);
            if (outcome instanceof BuildOutcome.Proceeded) {
                return outcome;
            }

            /**
             * sidecars must be read before the next invocationRequest call — it deletes the superseded meta dir.
             * a dependency-resolution failure (including the resolver's far-forward refusal) cannot be fixed by
             * stepping further back, so the ladder short-circuits to the latest-snapshots attempt.
             */
            BuildOutcome.Skipped skipped = (BuildOutcome.Skipped) outcome;
            if (Objects.isNull(firstSkipped)) {
                firstSkipped = skipped;
            }
            attemptFailures.add(attemptEntry("offset " + offset, skipped));
            Optional<Duration> next;
            if (AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE == skipped.category()) {
                next = Optional.empty();
            } else {
                List<Instant> pickedDeploys = TimeMachineBackoff.readPickedDeploys(args.getTimeMachineMetaDir());
                next = TimeMachineBackoff.nextOffset(TIME_MACHINE_BACKOFF_LADDER, offset, commitTimestamp, pickedDeploys);
            }
            if (next.isEmpty()) {
                getLog().warn(String.format("commit %s: time-machine build failed at offset %s (%s), retrying with latest snapshots", commitId, offset, skipped.reason()));
                args.setTimeMachineTargetOffset(null);
                BuildOutcome latest = buildProject(args, invocationRequest(args, false, Duration.ZERO), buildingReq);
                if (latest instanceof BuildOutcome.Skipped lastSkipped) {
                    attemptFailures.add(attemptEntry("latest snapshots", lastSkipped));
                    /**
                     * the first attempt resolves commit-date snapshots and is the commit-faithful build — its failure
                     * describes the commit's true state, while later attempts fail against increasingly anachronistic
                     * dependency picks. headline the first failure; the full ladder lives in the detail.
                     */
                    return new BuildOutcome.Skipped(firstSkipped.reason(), firstSkipped.category(), attemptHistoryDetail(attemptFailures));
                }
                return latest;
            }

            offset = next.get();
            getLog().warn(String.format("commit %s: time-machine build failed (%s), retrying with target offset %s", commitId, skipped.reason(), offset));
        }
    }
    private static String attemptEntry(String label, BuildOutcome.Skipped skipped) {
        String toReturn = label + ": " + skipped.reason();
        if (StringUtils.isNotBlank(skipped.detail())) {
            toReturn += CharUtils.LF + StringUtils.abbreviate(skipped.detail(), ATTEMPT_DETAIL_LIMIT);
        }
        return toReturn;
    }
    private static String attemptHistoryDetail(List<String> attemptFailures) {
        return "time-machine attempts:" + ATTEMPT_SEPARATOR + StringUtils.join(attemptFailures, ATTEMPT_SEPARATOR);
    }
    private static String resolveAuthorEmail(RunArgs args) throws IOException {
        ObjectId objectId = args.getGit().resolve(args.getCommitId());
        try (RevWalk walk = new RevWalk(args.getGit())) {
            return walk.parseCommit(objectId).getAuthorIdent().getEmailAddress();
        }
    }
}
