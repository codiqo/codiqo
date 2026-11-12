package io.codiqo.maven;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jgit.lib.Repository;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.llm.review.ReviewTrace;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.CodiqoServer;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.HttpCodiqoServer;
import io.codiqo.submit.IndexedCommits;
import io.codiqo.submit.ProjectHalts;
import io.codiqo.submit.SubmissionAssembly;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.hotspots.FixCommits;
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
     * Made in {@link #doPrepare}, once the run knows whether it hides the source: it keeps that hiding for every model it
     * sends, so a server made before would send a hidden-source project's code in plain.
     */
    private CodiqoServer server;
    /**
     * The commit whose failure halts the project, set once the server confirmed the project is not halted. A failure
     * before that point (no credential, a halted project, the server unreachable) starts no review and is not halted.
     */
    private String haltableCommitId;

    /** the project is the root POM's {@code groupId:artifactId}, the code its submissions name it by */
    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        super.doPrepare(args);

        server = new HttpCodiqoServer(
                apiUrl,
                resourceUrl,
                () -> MavenCredentials.resolve(apiKey, authUrl, resourceUrl, getLog()),
                connectTimeoutSeconds,
                readTimeoutSeconds,
                project.getGroupId() + ":" + project.getArtifactId(),
                sourceHiding(),
                new MavenMessageReporter(getLog()));
    }
    @Override
    protected void doBeforeAnalysis(RunArgs args) throws Exception {
        applyReviewSettings(args);

        /**
         * Before the build: the developer is at the terminal for a browser login, which this first call to Codiqo after
         * the exclusion gate needs at the latest, and one opened later would go unnoticed. Before the review starts: a
         * halted project must not pay for another review the same failure would waste.
         */
        server.requireRunning();
        haltableCommitId = args.getCommitId();
    }
    @Override
    protected IndexedCommits indexedCommits() throws Exception {
        return server.indexedCommits();
    }
    /**
     * A run that fails here submits nothing, so the next scheduled run would analyse the same commit and pay for its
     * review again, every hour. The project is halted instead, and stays halted until an administrator resumes it,
     * unless the failure halts nothing ({@link ProjectHalts#haltsNothing}: a stopped run, the author's broken build, a
     * failed download); a halted project is already halted.
     */
    @Override
    protected void doOnFailure(RunArgs args, Exception err) {
        /** a run that fails after its submission was accepted has scored its commit, so it halts nothing */
        if (Objects.nonNull(haltableCommitId) && BooleanUtils.negate(BooleanUtils.or(new boolean[] { ProjectHalts.haltsNothing(err), args.isSubmissionAccepted() }))) {
            server.halt(haltableCommitId, ProjectHalts.reasonOf(err), ExceptionUtils.getStackTrace(err));
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
                reviewRun = LocalReviewRun.start(
                        args,
                        clone.getWorkTree().toPath(),
                        args.getCommitId(),
                        server.reviewEndpoint(args),
                        LocalReviewGuidance.read(args, getLog()),
                        server::projectTags,
                        () -> server.previousFindings(args.getCommitId()),
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
    @Override
    protected void doBeforeCheckoutRemoved(RunArgs args) throws Exception {
        if (Objects.nonNull(reviewRun)) {
            reviewRun.close();
        }
    }
    @Override
    protected void doSubmit(SubmissionContext ctx) throws Exception {
        if (Objects.nonNull(reviewRun)) {
            /** a review that failed on the provider or on Codiqo's side fails the run here, and doOnFailure halts the project */
            Optional<AnalysisExcludeModel> excluded = LocalReviewRun.exclusionOf(reviewRun.attachTo(ctx), ctx, new MavenMessageReporter(getLog()));
            if (excluded.isPresent()) {
                AnalysisExcludeModel exclusion = excluded.get();
                doExcludeAnalysis(ctx.getArgs().getCommitId(), exclusion.getReason(), exclusion.getCategory(), exclusion.getDetail(), exclusion.getFiles(), exclusion.getProjectMetrics());
                return;
            }
        }

        SubmissionAssembly.stampAnalysisTime(ctx);
        try (ProgressStage stage = ProgressStage.start(ctx.getArgs(), "submit")) {
            /** the review wrote its trace before it was joined */
            Optional<File> reviewTrace = Optional.empty();
            if (Objects.nonNull(ctx.getSubmissionModel().getLocalReview())) {
                reviewTrace = Optional.of(ReviewTrace.file(ctx.getArgs(), ctx.getArgs().getCommitId()));
            }
            AnalysisAcceptedModel response = server.submit(ctx.getSubmissionModel(), reviewTrace);
            ctx.getArgs().setSubmissionAccepted(true);
            getLog().info(String.format("accepted analysis id: %s status: %s", response.getAnalysisId(), response.getStatus()));
            stage.succeeded();
        }
    }
    @Override
    protected FixCommits fixCommits(SubmissionContext ctx, Instant since) throws Exception {
        return server.fixCommits(since);
    }
    @Override
    protected void doSubmitHotspots(SubmissionContext ctx, HotspotSnapshotModel snapshot) throws Exception {
        server.submitHotspots(snapshot);
    }
    @Override
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category, String detail, List<FileChangeModel> files, ProjectMetricsModel projectMetrics) throws Exception {
        server.exclude(commitSha, new AnalysisExcludeModel().reason(reason).category(category).detail(detail).files(files).projectMetrics(projectMetrics));
    }
    /** a build the author broke; an unresolvable dependency or a build that ran out of time is the environment's */
    private static boolean isAuthorsFailure(AnalysisExcludeCategory category, boolean timedOut) {
        return BooleanUtils.and(new boolean[] { AnalysisExcludeCategory.BUILD_FAILURE == category, BooleanUtils.negate(timedOut) });
    }
}
