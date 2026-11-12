package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.testing.TestRuns;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.maven.AbstractAnalyzeMojo.BuildOutcome;
import io.codiqo.maven.AbstractAnalyzeMojo.BuildPhase;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.maven.populator.ProjectModelPopulator;
import io.codiqo.maven.populator.SubmissionSummaryPrinter;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.outcome.BuildVerdict;
import io.codiqo.submit.outcome.CommitBuild;
import io.codiqo.util.JGit;
import io.codiqo.util.MemoryReport;

/**
 * The commit's build as Maven runs it: the commit is checked out in a clone of the developer's repository, then built in
 * a forked Maven twice, its production code and then its tests, with the time machine pinning each dependency's snapshot
 * to the commit's date.
 */
public final class MavenCommitBuild implements CommitBuild {
    /**
     * Back-off ladder mirroring how stale a developer's local snapshot copy may have been when the commit was built:
     * Maven's default updatePolicy is daily, so the snapshot the author compiled against can be up to a day older than
     * the commit. Each rung steps the snapshot resolution target further back from the commit instant, so a commit
     * that only compiled against such a stale copy still finds the snapshot it was built with.
     */
    private static final List<Duration> TIME_MACHINE_BACKOFF_LADDER = List.of(
            Duration.ZERO,
            Duration.ofMinutes(15),
            Duration.ofHours(1),
            Duration.ofHours(4),
            Duration.ofDays(1));
    /**
     * per-attempt excerpt of the structured failure detail kept in the exclusion history — enough to carry the
     * actual compiler/model errors (not just the first line) without ballooning the persisted detail
     */
    private static final int ATTEMPT_DETAIL_LIMIT = 4096;
    private static final String ATTEMPT_SEPARATOR = StringUtils.repeat(CharUtils.LF, 2);

    private final AbstractAnalyzeMojo mojo;
    private final RunArgs args;
    private final Log log;
    private final Collection<MavenProject> reactors = Lists.newLinkedList();
    private ProjectBuildingRequest buildingRequest;

    public MavenCommitBuild(AbstractAnalyzeMojo mojo, RunArgs args) {
        this.mojo = Objects.requireNonNull(mojo);
        this.args = Objects.requireNonNull(args);
        this.log = mojo.getLog();
    }
    /**
     * A clone keeps the developer's working directory untouched and keeps uncommitted files out of the analysis; it is
     * cleaned and hard reset before the commit is checked out, so the commit is analysed exactly as it was. Closing it
     * deletes the clone: by then the commit's outcome is decided and reported, so a clone that will not delete is left
     * behind rather than failing the run. The private local repository deliberately outlives it, since every commit of
     * the project shares it and its seeded releases are what make the next commit cheap.
     */
    @Override
    public AutoCloseable checkout() throws Exception {
        File temp = Files.createTempDirectory("codiqo").toFile();
        temp.deleteOnExit();

        StopWatch stopWatch = StopWatch.createStarted();
        StoredConfig originalConfig = args.getGit().getConfig();
        JGit.currentBranchOrDefault(args.getGit()).ifPresent(args::setDefaultBranch);

        String sourceUri = args.getGit().getDirectory().toURI().toString();
        ObjectId sourceHead = args.getGit().resolve(Constants.HEAD);

        Repository clone = new FileRepositoryBuilder().setGitDir(new File(temp, ".git")).build();
        AutoCloseable toReturn = () -> {
            clone.close();
            try {
                FileUtils.deleteDirectory(temp);
            } catch (IOException err) {
                log.warn("could not delete the analysis checkout " + temp + ", leaving it behind: " + err);
            }
        };
        try {
            clone.create(false);

            StoredConfig initialConfig = clone.getConfig();
            initialConfig.setString("remote", "origin", "url", sourceUri);
            initialConfig.save();

            try (Git git = Git.wrap(clone)) {
                git.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("+refs/*:refs/*"))
                        .call();
            }

            JGit.copyShallowBoundary(args.getGit(), clone);

            RefUpdate headUpdate = clone.updateRef(Constants.HEAD, true);
            headUpdate.setNewObjectId(sourceHead);
            headUpdate.forceUpdate();

            stopWatch.stop();
            log.info(String.format("cloned directory: %s for analysis in %s", temp.getAbsolutePath(), stopWatch.toString()));

            /** The clone needs the original remote URLs, or relative URLs in the build no longer resolve. */
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

            args.setGit(clone);
            try (Git git = Git.wrap(clone)) {
                git.clean().setCleanDirectories(true).setForce(true).call();
                git.reset().setMode(ResetCommand.ResetType.HARD).call();

                git.checkout().setForced(true).setName(args.getCommitId()).call();
                log.info(String.format("checked out commit ID: %s", args.getCommitId()));
            }
            return toReturn;
        } catch (Exception err) {
            toReturn.close();
            throw err;
        }
    }
    /**
     * The compile run, with the time-machine snapshot resolver first, pinning each dependency's snapshot deploy closest
     * to the commit date. If that fails, because the commit-date snapshot is unavailable or the source no longer compiles
     * against it, the resolution target steps back through the ladder, then falls back to latest snapshots. A missing
     * coverage agent fails before anything is built: a commit with nothing to measure was excluded before this, and the
     * failure halts the project, where checked earlier it halted nothing and every scheduled run failed the same way.
     */
    @Override
    public BuildVerdict compile() throws Exception {
        mojo.requireCoverageAgent(args);

        /**
         * host-side ProjectBuilder calls (pre-flight, root and module model building) resolve snapshot parents and BOM
         * imports in THIS JVM, where the time machine only applies when the extension is loaded via the host
         * maven.ext.class.path. Pin those calls to the commit instant, shifted by the successful attempt's back-off
         * offset, so a later snapshot deploy that breaks historical POM interpolation cannot fail the analysis of an
         * older commit.
         */
        if (args.isTimeMachineEnabled()) {
            args.setTimeMachineTargetOffset(Duration.ZERO);
            mojo.warnIfHostTimeMachineMissing();
        }

        buildingRequest = Maven.buildingRequest(mojo.mavenSession);
        Maven.pinMultiModuleProjectDirectory(buildingRequest, args.getGit().getWorkTree());
        if (Objects.nonNull(args.getTimeMachineTargetOffset())) {
            Maven.isolateRepositorySession(buildingRequest);
        }
        /**
         * pre-flight host model building is advisory only: the host JVM lacks the analyzed project's
         * .mvn/extensions.xml environment, so its resolution failures are not authoritative — the fork build decides,
         * and a genuine dependency problem resurfaces there
         */
        if (mojo.resolveDependenciesOffline(args) instanceof BuildOutcome.Skipped skipped) {
            log.warn(String.format("pre-flight host model building failed (%s), deferring to fork build", skipped.getReason()));
        }

        BuildOutcome toReturn;
        if (args.isTimeMachineEnabled()) {
            toReturn = buildWithBackoff(buildingRequest);
        } else {
            toReturn = mojo.buildProject(args, mojo.invocationRequest(args, false, Duration.ZERO, BuildPhase.COMPILE), buildingRequest, BuildPhase.COMPILE, args.getBuildTimeout());
        }
        return verdict(toReturn);
    }
    /**
     * The test run builds on the compile run's pinned snapshots, at the offset that compiled, within what that compile
     * run left of the build timeout. Only the run that compiled counts: every rung of the time-machine ladder has a whole
     * build timeout of its own, so measuring from the first rung left a commit that needed the ladder a test run of a
     * few minutes, or a negative timeout that killed the fork at once. The host then builds the model of every module,
     * which can fail as the build does.
     */
    @Override
    public BuildVerdict test() throws Exception {
        Duration offset = args.getTimeMachineTargetOffset();
        BuildOutcome tested = mojo.buildProject(
                args,
                mojo.invocationRequest(args, Objects.nonNull(offset), Optional.ofNullable(offset).orElse(Duration.ZERO), BuildPhase.TESTS),
                buildingRequest,
                BuildPhase.TESTS,
                args.getBuildTimeout().minus(args.getCompilePhaseTook()));
        if (tested instanceof BuildOutcome.Skipped skipped) {
            return verdict(skipped);
        }

        ProjectBuildingResult result = ((BuildOutcome.Proceeded) tested).getResult();
        Optional<BuildOutcome.Skipped> modules = mojo.buildAndCollectModules(result.getProject(), args.getGit().getWorkTree(), buildingRequest, args, reactors);
        if (modules.isPresent()) {
            return verdict(modules.get());
        }

        if (args.isIgnoreCoverage()) {
            return BuildVerdict.passed();
        }
        return BuildVerdict.tested(TestRuns.read(
                reactors.stream().flatMap(reactor -> TestReportDirectories.resolve(reactor).stream()).distinct().toList(),
                new MavenMessageReporter(log)));
    }
    /**
     * The reactor's modules as the host model built them, scanned into one class graph. The clone's POMs are relaxed
     * first, so the language server can import every module, and source JARs the language server hangs on are purged
     * before it loads.
     */
    @Override
    public ClassGraphSpec projects() throws Exception {
        new OutOfModuleResources(log).relax(args.getGit().getWorkTree());

        ClassGraphSpec toReturn = mojo.scanProjects(args, reactors);
        log.info(MemoryReport.snapshot("after classgraph scan"));
        args.setClassGraph(toReturn);
        mojo.purgeNonJavaSourceJars(args);
        return toReturn;
    }
    @Override
    public ProjectSpec sourceOnlyProject() {
        return SourceOnlyProjectSpec.forWorkTree(args.getGit().getWorkTree(), mojo.project);
    }
    @Override
    public void populateProjectModel(SubmissionContext ctx) {
        new ProjectModelPopulator(log).accept(ctx);
    }
    @Override
    public void printSummary(SubmissionContext ctx) {
        new SubmissionSummaryPrinter(log).accept(ctx);
    }
    @VisibleForTesting
    public BuildOutcome buildWithBackoff(ProjectBuildingRequest request) throws Exception {
        Instant commitTimestamp = TimeMachineSupport.resolveCommitTimestamp(args);
        List<String> attemptFailures = Lists.newArrayList();
        BuildOutcome.Skipped firstSkipped = null;
        Duration offset = Duration.ZERO;
        for (;;) {
            args.setTimeMachineTargetOffset(offset);
            BuildOutcome outcome = mojo.buildProject(args, mojo.invocationRequest(args, true, offset, BuildPhase.COMPILE), request, BuildPhase.COMPILE, args.getBuildTimeout());
            if (outcome instanceof BuildOutcome.Proceeded) {
                return outcome;
            }

            /**
             * sidecars must be read before the next invocationRequest call, which deletes the superseded meta dir.
             * A dependency-resolution failure cannot be fixed by stepping further back, so the ladder short-circuits
             * to the latest-snapshots attempt.
             */
            BuildOutcome.Skipped skipped = (BuildOutcome.Skipped) outcome;
            if (Objects.isNull(firstSkipped)) {
                firstSkipped = skipped;
            }
            attemptFailures.add(attemptEntry("offset " + offset, skipped));

            /**
             * a timeout is the one failure the ladder must not answer with another attempt: it is no evidence that
             * the snapshot picks were wrong, and every further rung costs another full buildTimeout — more than the
             * deadline wrapping the whole commit can absorb.
             */
            if (skipped.isTimedOut()) {
                log.warn(String.format("commit %s: time-machine build timed out at offset %s, not retrying", args.getCommitId(), offset));
                return new BuildOutcome.Skipped(
                        firstSkipped.getReason(), firstSkipped.getCategory(), attemptHistoryDetail(attemptFailures), true);
            }

            Optional<Duration> next;
            if (AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE == skipped.getCategory()) {
                next = Optional.empty();
            } else {
                List<Instant> pickedDeploys = TimeMachineBackoff.readPickedDeploys(args.getTimeMachineMetaDir());
                next = TimeMachineBackoff.nextOffset(TIME_MACHINE_BACKOFF_LADDER, offset, commitTimestamp, pickedDeploys);
            }
            if (next.isEmpty()) {
                log.warn(String.format("commit %s: time-machine build failed at offset %s (%s), retrying with latest snapshots", args.getCommitId(), offset, skipped.getReason()));
                args.setTimeMachineTargetOffset(null);
                BuildOutcome latest = mojo.buildProject(args, mojo.invocationRequest(args, false, Duration.ZERO, BuildPhase.COMPILE), request, BuildPhase.COMPILE, args.getBuildTimeout());
                if (latest instanceof BuildOutcome.Skipped lastSkipped) {
                    attemptFailures.add(attemptEntry("latest snapshots", lastSkipped));
                    /**
                     * the first attempt is the commit-faithful build, so its failure describes the commit's true
                     * state; later attempts fail against increasingly anachronistic picks. Headline the first, and
                     * keep the full ladder in the detail. A last attempt that timed out says so, as a timeout at any
                     * other rung does: without it the timeout read as the author's broken build, whose commit is
                     * reviewed and scored as degraded, where a build that ran out of time is the environment's.
                     */
                    return new BuildOutcome.Skipped(firstSkipped.getReason(), firstSkipped.getCategory(), attemptHistoryDetail(attemptFailures), lastSkipped.isTimedOut());
                }
                return latest;
            }

            offset = next.get();
            log.warn(String.format("commit %s: time-machine build failed (%s), retrying with target offset %s", args.getCommitId(), skipped.getReason(), offset));
        }
    }
    private static BuildVerdict verdict(BuildOutcome outcome) {
        if (outcome instanceof BuildOutcome.Skipped skipped) {
            return new BuildVerdict.Failed(skipped.getReason(), skipped.getCategory(), skipped.getDetail(), skipped.isTimedOut());
        }
        return BuildVerdict.passed();
    }
    private static String attemptEntry(String label, BuildOutcome.Skipped skipped) {
        String toReturn = label + ": " + skipped.getReason();
        if (StringUtils.isNotBlank(skipped.getDetail())) {
            toReturn += CharUtils.LF + StringUtils.abbreviate(skipped.getDetail(), ATTEMPT_DETAIL_LIMIT);
        }
        return toReturn;
    }
    private static String attemptHistoryDetail(List<String> attemptFailures) {
        return "time-machine attempts:" + ATTEMPT_SEPARATOR + Joiner.on(ATTEMPT_SEPARATOR).join(attemptFailures);
    }
}
