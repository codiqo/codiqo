package io.codiqo.maven;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jgit.lib.Repository;

import io.codiqo.api.RunArgs;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisBuildFailureModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.llm.review.ReviewTrace;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.AnalysisSubmitter;
import io.codiqo.submit.CommitBuildFailedException;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.CommitIndexPublisher;
import io.codiqo.submit.ProjectHalts;
import io.codiqo.submit.ProjectTags;
import io.codiqo.submit.SubmissionAssembly;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.submit.hotspots.FixCommits;
import io.codiqo.submit.hotspots.HotspotSnapshots;
import io.codiqo.submit.review.LocalReviewException;
import io.codiqo.submit.review.LocalReviewRun;
import io.codiqo.util.JGit;
import io.codiqo.util.ProgressStage;

@Mojo(name = "submit-commit-analysis",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class SubmitCommitAnalysisMojo extends AnalyzeCommitMojo {
    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.resourceUrl", defaultValue = RunArgs.DEFAULT_RESOURCE_URL)
    private String resourceUrl;

    private LocalReviewRun reviewRun;
    /**
     * Resolved once per run and shared by every call to Codiqo. Each call used to resolve its own, so a developer with
     * no stored login got one browser login from the fix-commit lookup in the middle of the analysis, while the
     * language servers held their memory, and when nobody approved it in time, a second one at the submission.
     */
    private CodiqoCredential credential;
    /**
     * The commit whose failure halts the project, set once the server confirmed the project is not halted. A failure
     * before that point (no credential, a halted project, the server unreachable) starts no review and is not halted.
     */
    private String haltableCommitId;

    @Override
    protected void doBeforeAnalysis(RunArgs args) throws Exception {
        applyReviewSettings(args);

        /** before the build, while the developer is at the terminal: a browser login opened later would go unnoticed */
        credential();

        /** before the review starts: a halted project must not pay for another review the same failure would waste */
        ProjectHalts.requireRunning(new MavenMessageReporter(getLog()), commitIndexClient(), apiUrl, projectId());
        haltableCommitId = args.getCommitId();
    }
    /**
     * A run that fails here submits nothing, so the next scheduled run would analyse the same commit and pay for its
     * review again, every hour. The project is halted instead, and stays halted until an administrator resumes it. A
     * run that was stopped is not a failure of codiqo's, a halted project is already halted, and a build the author
     * broke with {@code codiqo.skipOnBuildFailure} off is the author's failure ({@link CommitBuildFailedException}).
     */
    @Override
    protected void doOnFailure(RunArgs args, Exception err) {
        boolean haltsNothing = BooleanUtils.or(new boolean[] {
                Thread.currentThread().isInterrupted(),
                ExceptionUtils.getRootCause(err) instanceof InterruptedException,
                ExceptionUtils.indexOfThrowable(err, CommitBuildFailedException.class) >= 0 });
        /** a run that fails after its submission was accepted has scored its commit, so it halts nothing */
        if (Objects.nonNull(haltableCommitId) && BooleanUtils.negate(BooleanUtils.or(new boolean[] { haltsNothing, args.isSubmissionAccepted() }))) {
            try {
                ProjectHalts.halt(new MavenMessageReporter(getLog()), commitIndexClient(), apiUrl, projectId(), haltableCommitId, err);
            } catch (MojoExecutionException credentialErr) {
                getLog().error("project " + projectId() + " could not be halted: " + credentialErr.getMessage(), credentialErr);
            }
            ProgressStage.abort(args, ExceptionUtils.getRootCauseMessage(err), ProjectHalts.traceOf(args, haltableCommitId));
        }
    }
    /**
     * Starts the review in the analysis clone, which holds the commit as its clean HEAD however old the commit is, so
     * every analysed commit is reviewed, not only the tip a CI run checked out. It starts once the compile run has
     * decided and runs next to the test run. A compile the author broke is reviewed too, since that review is what
     * scores it; a compile that failed on the environment (dependencies, a timeout) starts none, so a build that
     * cannot run costs no review.
     */
    @Override
    protected void doAfterCompile(RunArgs args, BuildOutcome compileOutcome) throws Exception {
        boolean judged = switch (compileOutcome) {
            case BuildOutcome.Proceeded proceeded -> true;
            case BuildOutcome.Skipped skipped -> isAuthorsFailure(skipped.getCategory(), skipped.isTimedOut());
        };
        if (BooleanUtils.and(new boolean[] { args.isReviewEnabled(), judged })) {
            Repository clone = args.getGit();
            // a commit that changes nothing the analysis reads is excluded after the build, so its review would be paid for and dropped
            if (CommitExclusions.hasAnalyzableFile(JGit.changedFileNames(clone, args.getCommitId()), DefaultLanguageProcessors.supportedExtensions())) {
                ReviewEndpoint endpoint = LocalReviewRun.endpoint(args, resourceUrl, this::credential);
                reviewRun = LocalReviewRun.start(
                        args,
                        clone.getWorkTree().toPath(),
                        args.getCommitId(),
                        endpoint,
                        LocalReviewGuidance.read(args, getLog()),
                        this::projectTags,
                        DefaultLanguageProcessors.reviewLanguages(),
                        new MavenLogFactory(getLog()));
                getLog().info("local review of " + args.getCommitId() + " started next to the analysis");
            } else {
                getLog().info("local review skipped: " + args.getCommitId() + " changes no file the analysis reads");
            }
        }
    }
    /**
     * Local mode judges a commit with its review, which reads the code through git and needs no build, so a commit
     * whose review was started is scored even when its build fails: by rules, from the review's assessment, as a
     * build-failed commit. Excluding it would drop a review already paid for. Only a build the author broke counts: an
     * unresolvable dependency or a build that ran out of time is the environment's, and scoring it would alert on a
     * failure nobody made, so it stays excluded and is analysed again once the environment is fixed.
     */
    @Override
    protected boolean scoresFailedBuild(AnalysisExcludeCategory category, boolean timedOut) {
        return BooleanUtils.and(new boolean[] { Objects.nonNull(reviewRun), isAuthorsFailure(category, timedOut) });
    }
    private ReviewTags projectTags() throws Exception {
        return ProjectTags.fetch(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, project.getGroupId() + ":" + project.getArtifactId(), new MavenMessageReporter(getLog()));
    }
    @Override
    protected void doBeforeCheckoutRemoved(RunArgs args) throws Exception {
        if (Objects.nonNull(reviewRun)) {
            reviewRun.close();
        }
    }
    @Override
    protected void doSubmit(SubmissionContext ctx) throws Exception {
        if (Objects.nonNull(reviewRun)) {
            try {
                reviewRun.attachTo(ctx);
            } catch (LocalReviewException err) {
                /**
                 * Local mode judges a commit with the review alone, so a review that failed or ran out of time leaves
                 * the commit unjudged: it is excluded, which keeps the next run from paying for the review again and
                 * the server's prompt from judging it in the review's place. Deleting the analysis retries it. An
                 * interrupt is the run being stopped, not a failed review: the thread's flag tells the two apart. A
                 * review the provider failed is no judgement of the commit either: excluded, the commit would stay
                 * unscored for good, so the run fails and doOnFailure halts the project until the provider recovers.
                 */
                if (BooleanUtils.or(new boolean[] { Thread.currentThread().isInterrupted(), err.isProviderFailure() })) {
                    throw err;
                }
                getLog().error(String.format("commit %s excluded: %s", ctx.getArgs().getCommitId(), err.getMessage()), err);
                AnalysisBuildFailureModel buildFailure = ctx.getSubmissionModel().getBuildFailure();
                if (Objects.nonNull(buildFailure)) {
                    /**
                     * A build-failed commit keeps its build failure, with its files and metrics, as the exclusion a
                     * failed build without a review records: an operator's re-inclusion of a build failure survives a
                     * re-analysis only under that category, and the files are what the server stores as the excluded
                     * commit's per-file changes.
                     */
                    doExcludeAnalysis(
                            ctx.getArgs().getCommitId(),
                            buildFailure.getReason(),
                            buildFailure.getCategory(),
                            buildFailure.getDetail(),
                            ctx.getSubmissionModel().getFiles(),
                            ctx.getSubmissionModel().getProjectMetrics());
                } else {
                    doExcludeAnalysis(ctx.getArgs().getCommitId(), err.getMessage(), AnalysisExcludeCategory.LOCAL_REVIEW_FAILURE, ExceptionUtils.getStackTrace(err));
                }
                return;
            }
        }

        SubmissionAssembly.stampAnalysisTime(ctx);
        try (ProgressStage stage = ProgressStage.start(ctx.getArgs(), "submit")) {
            /** the review wrote its trace before it was joined; it goes with the review it explains, never alone */
            File reviewTrace = ReviewTrace.file(ctx.getArgs(), ctx.getArgs().getCommitId());
            AnalysisAcceptedModel response;
            if (BooleanUtils.and(new boolean[] { Objects.nonNull(ctx.getSubmissionModel().getLocalReview()), reviewTrace.isFile() })) {
                response = AnalysisSubmitter.submit(
                        apiUrl,
                        credential(),
                        connectTimeoutSeconds,
                        readTimeoutSeconds,
                        ctx.getSubmissionModel(),
                        reviewTrace,
                        new MavenMessageReporter(getLog()));
            } else {
                response = AnalysisSubmitter.submit(
                        apiUrl,
                        credential(),
                        connectTimeoutSeconds,
                        readTimeoutSeconds,
                        ctx.getSubmissionModel(),
                        new MavenMessageReporter(getLog()));
            }
            ctx.getArgs().setSubmissionAccepted(true);
            getLog().info(String.format("accepted analysis id: %s status: %s", response.getAnalysisId(), response.getStatus()));
            stage.succeeded();
        }
    }
    private CommitIndexApi commitIndexClient() throws MojoExecutionException {
        return CommitIndexPublisher.buildClient(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds);
    }
    private String projectId() {
        return project.getGroupId() + ":" + project.getArtifactId();
    }
    private CodiqoCredential credential() throws MojoExecutionException {
        if (Objects.isNull(credential)) {
            credential = MavenCredentials.resolve(apiKey, authUrl, resourceUrl, getLog());
        }
        return credential;
    }
    @Override
    protected FixCommits fixCommits(SubmissionContext ctx, Instant since) throws Exception {
        return FixCommits.fetch(
                apiUrl,
                credential(),
                connectTimeoutSeconds,
                readTimeoutSeconds,
                ctx.getProjectCode(),
                since,
                new MavenMessageReporter(getLog()));
    }
    @Override
    protected void doSubmitHotspots(SubmissionContext ctx, HotspotSnapshotModel snapshot) throws Exception {
        HotspotSnapshots.submit(
                apiUrl,
                credential(),
                connectTimeoutSeconds,
                readTimeoutSeconds,
                ctx.getProjectCode(),
                snapshot,
                new MavenMessageReporter(getLog()));
    }
    @Override
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category, String detail, List<FileChangeModel> files, ProjectMetricsModel projectMetrics) throws Exception {
        AnalysisSubmitter.exclude(
                apiUrl,
                credential(),
                connectTimeoutSeconds,
                readTimeoutSeconds,
                commitSha,
                reason,
                category,
                detail,
                files,
                projectMetrics,
                new MavenMessageReporter(getLog()));
    }
    /** a build the author broke; an unresolvable dependency or a build that ran out of time is the environment's */
    private static boolean isAuthorsFailure(AnalysisExcludeCategory category, boolean timedOut) {
        return BooleanUtils.and(new boolean[] { AnalysisExcludeCategory.BUILD_FAILURE == category, BooleanUtils.negate(timedOut) });
    }
}
