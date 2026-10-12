package io.codiqo.maven;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jgit.lib.Repository;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisBuildFailureModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.llm.review.FindingTriage;
import io.codiqo.llm.review.FindingVerdict;
import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.review.LocalReviewModels;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.llm.review.ReviewTrace;
import io.codiqo.llm.review.ReviewUnit;
import io.codiqo.llm.review.StaticFinding;
import io.codiqo.llm.review.StaticFindings;
import io.codiqo.llm.review.UnitLabelling;
import io.codiqo.llm.review.UnitLabels;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.AnalysisSubmitter;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.ProjectTags;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.submit.hotspots.FixCommits;
import io.codiqo.submit.hotspots.HotspotSnapshots;
import io.codiqo.util.JGit;
import io.codiqo.util.ProgressStage;

@Mojo(name = "submit-commit-analysis",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class SubmitCommitAnalysisMojo extends AnalyzeCommitMojo {
    /** enough unmatched labels to show the drift, few enough to keep the log readable */
    private static final int UNMATCHED_LABEL_SAMPLE = 10;

    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.resourceUrl", defaultValue = RunArgs.DEFAULT_RESOURCE_URL)
    private String resourceUrl;

    /**
     * Local mode, the one review switch: agents review and assess the commit next to the analysis, judge its static
     * findings after the build and leave a trace, and the server scores the commit from that assessment alone. The
     * agents read the analysis clone, which holds exactly the commit, so every analysed commit is reviewed, a backlog of
     * historical ones included; each one is a review the organization pays for. A review that fails excludes the commit.
     */
    @Parameter(property = "codiqo.review")
    private Boolean review;

    /** each review setting left unset keeps its {@link RunArgs} default */
    @Parameter(property = "codiqo.review.executable")
    private String reviewExecutable;

    @Parameter(property = "codiqo.review.baseUrl")
    private String reviewBaseUrl;

    @Parameter(property = "codiqo.review.coordinatorModel")
    private String reviewCoordinatorModel;

    @Parameter(property = "codiqo.review.reviewerModel")
    private String reviewReviewerModel;

    @Parameter(property = "codiqo.review.timeoutMinutes")
    private Integer reviewTimeoutMinutes;

    /** retries of a call through the relay, to the Codiqo proxy or a direct endpoint, that failed on the way (network error, timeout, 429 or 5xx) */
    @Parameter(property = "codiqo.review.relayRetries")
    private Integer reviewRelayRetries;

    /** how long a call through the relay, to the Codiqo proxy or a direct endpoint, may take to start answering */
    @Parameter(property = "codiqo.review.relayTimeoutSeconds")
    private Integer reviewRelayTimeoutSeconds;

    /** where the review's trace of every session and tool call goes */
    @Parameter(property = "codiqo.review.traceDirectory", defaultValue = "${project.build.directory}/codiqo")
    private File reviewTraceDirectory;

    private BackgroundReview<LocalReviewer.ReviewOutcome> backgroundReview;
    /**
     * Resolved once per run and shared by every call to Codiqo. Each call used to resolve its own, so a developer with
     * no stored login got one browser login from the fix-commit lookup in the middle of the analysis, while the
     * language servers held their memory, and when nobody approved it in time, a second one at the submission.
     */
    private CodiqoCredential credential;
    /** completed with the analysis's findings just before the review is joined; the review waits for it to triage */
    private final CompletableFuture<List<StaticFinding>> buildFindings = new CompletableFuture<>();
    private final CompletableFuture<List<ReviewUnit>> buildUnits = new CompletableFuture<>();

    @Override
    protected void doBeforeAnalysis(RunArgs args) throws Exception {
        Optional.ofNullable(review).ifPresent(args::setReviewEnabled);
        Optional.ofNullable(reviewTraceDirectory).ifPresent(args::setReviewTraceDirectory);
        Optional.ofNullable(reviewExecutable).ifPresent(args::setReviewExecutable);
        Optional.ofNullable(reviewBaseUrl).ifPresent(args::setReviewBaseUrl);
        Optional.ofNullable(reviewCoordinatorModel).ifPresent(args::setReviewCoordinatorModel);
        Optional.ofNullable(reviewReviewerModel).ifPresent(args::setReviewReviewerModel);
        Optional.ofNullable(reviewTimeoutMinutes).ifPresent(minutes -> args.setReviewTimeout(Duration.ofMinutes(minutes)));
        Optional.ofNullable(reviewRelayRetries).ifPresent(args::setReviewRelayRetries);
        Optional.ofNullable(reviewRelayTimeoutSeconds).ifPresent(seconds -> args.setReviewRelayRequestTimeout(Duration.ofSeconds(seconds)));

        /** before the build, while the developer is at the terminal: a browser login opened later would go unnoticed */
        credential();
    }
    /**
     * Starts the review in the analysis clone, which holds the commit as its clean HEAD however old the commit is, so
     * every analysed commit is reviewed, not only the tip a CI run checked out. It runs next to the build.
     */
    @Override
    protected void doAfterCheckout(RunArgs args) throws Exception {
        if (args.isReviewEnabled()) {
            Repository clone = args.getGit();
            // a commit that changes nothing the analysis reads is excluded after the build, so its review would be paid for and dropped
            if (CommitExclusions.hasAnalyzableFile(JGit.changedFileNames(clone, args.getCommitId()), DefaultLanguageProcessors.supportedExtensions())) {
                ReviewEndpoint endpoint = new ReviewEndpoint(args.getReviewBaseUrl(), null, null);
                if (endpoint.isProxiedBy(resourceUrl)) {
                    endpoint = new ReviewEndpoint(args.getReviewBaseUrl(), null, credential());
                }
                ReviewEndpoint reviewed = endpoint;
                String guidance = LocalReviewGuidance.read(args, getLog());
                Path workTree = clone.getWorkTree().toPath();
                String commit = args.getCommitId();
                backgroundReview = BackgroundReview.start(() -> {
                    LocalReviewer reviewer = new LocalReviewer(
                            args,
                            reviewed,
                            guidance,
                            projectTags(),
                            DefaultLanguageProcessors.reviewLanguages(),
                            new MavenLogFactory(getLog()).getLogger(LocalReviewer.class));
                    return reviewer.reviewThenFollowUp(workTree, commit, Optional.of(buildUnits::get), Optional.of(buildFindings::get));
                });
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
    protected boolean scoresFailedBuild(RunArgs args, AnalysisExcludeCategory category, boolean timedOut) {
        boolean authorBrokeTheBuild = BooleanUtils.and(new boolean[] {
                Objects.nonNull(backgroundReview), AnalysisExcludeCategory.BUILD_FAILURE == category, BooleanUtils.negate(timedOut) });
        return BooleanUtils.or(new boolean[] { super.scoresFailedBuild(args, category, timedOut), authorBrokeTheBuild });
    }
    private ReviewTags projectTags() throws Exception {
        return ProjectTags.fetch(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, project.getGroupId() + ":" + project.getArtifactId(), new MavenMessageReporter(getLog()));
    }
    @Override
    protected void doBeforeCheckoutRemoved(RunArgs args) throws Exception {
        if (Objects.nonNull(backgroundReview)) {
            backgroundReview.close();
        }
    }
    @Override
    protected void doLlmScoring(SubmissionContext ctx) throws Exception {
        if (Objects.nonNull(backgroundReview)) {
            UnitLabelling.Checklist checklist = UnitLabelling.checklist(ctx.getSubmissionModel());
            buildUnits.complete(checklist.getListed());
            buildFindings.complete(StaticFindings.introduced(ctx.getSubmissionModel(), DefaultLanguageProcessors.reviewLanguages()));
            LocalReviewer.ReviewOutcome reviewed;
            try {
                reviewed = backgroundReview.await(LocalReviewer.longestReview(ctx.getArgs()));
            } catch (MojoExecutionException err) {
                /**
                 * Local mode judges a commit with the review alone, so a review that failed or ran out of time leaves
                 * the commit unjudged: it is excluded, which keeps the next run from paying for the review again and
                 * the server's prompt from judging it in the review's place. Deleting the analysis retries it. An
                 * interrupt is the run being stopped, not a failed review: the thread's flag tells the two apart.
                 */
                if (Thread.currentThread().isInterrupted()) {
                    throw err;
                }
                getLog().error(String.format("commit %s excluded: %s", ctx.getArgs().getCommitId(), err.getMessage()), err);
                AnalysisBuildFailureModel buildFailure = ctx.getSubmissionModel().getBuildFailure();
                if (Objects.nonNull(buildFailure)) {
                    /**
                     * A build-failed commit keeps its build failure, with its files and metrics, as the exclusion a
                     * failed build without a review records: an operator's re-inclusion of a build failure survives a
                     * re-analysis only under that category, and Run degraded needs the files.
                     */
                    doExcludeAnalysis(ctx.getArgs().getCommitId(), buildFailure.getReason(), buildFailure.getCategory(), buildFailure.getDetail(),
                            ctx.getSubmissionModel().getFiles(), ctx.getSubmissionModel().getProjectMetrics());
                } else {
                    doExcludeAnalysis(ctx.getArgs().getCommitId(), err.getMessage(), AnalysisExcludeCategory.LOCAL_REVIEW_FAILURE, ExceptionUtils.getStackTrace(err));
                }
                return;
            }
            LocalReview finished = reviewed.getReview();
            reviewed.getTriage().ifPresent(this::logTriage);
            /**
             * The review is mapped before anything is attached to the submission, and its labels are applied all or
             * nothing, so a failure while mapping leaves no half-attached review behind on a submission that is sent.
             */
            LocalReviewModel model = LocalReviewModels.toModel(finished, reviewed.getTriage(), new MavenLogFactory(getLog()).getLogger(LocalReviewModels.class));
            int labelled = 0;
            if (reviewed.getLabels().isPresent()) {
                labelled = labelById(ctx.getSubmissionModel(), checklist, finished, reviewed.getLabels().get());
            }
            if (labelled == 0) {
                labelByName(ctx.getSubmissionModel(), finished);
            }
            ctx.getSubmissionModel().setLocalReview(model);
        }

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
            getLog().info(String.format("accepted analysis id: %s status: %s", response.getAnalysisId(), response.getStatus()));
            stage.succeeded();
        }
    }
    private CodiqoCredential credential() throws MojoExecutionException {
        if (Objects.isNull(credential)) {
            credential = MavenCredentials.resolve(apiKey, authUrl, resourceUrl, getLog());
        }
        return credential;
    }
    /**
     * The labelling turn's labels, by id; returns the units they labelled. The reviewers' own labels are only matched
     * here, so the log shows how much of the commit each would have labelled. An answer that labels nothing (an empty
     * list, another key, only ids or categories that do not exist) still parses, since unknown fields and enum values
     * are read leniently; the caller then applies the reviewers' labels, as it does when the turn did not run, instead of
     * leaving every unit of the commit without a local label.
     */
    private int labelById(AnalysisSubmissionModel submission, UnitLabelling.Checklist checklist, LocalReview finished, UnitLabels labels) {
        LocalReviewModels.Labelling byName = LocalReviewModels.matchBlockCategories(submission, finished, DefaultLanguageProcessors.reviewLanguages()).getRight();
        UnitLabelling.Applied applied = UnitLabelling.apply(checklist, labels);
        getLog().info(String.format("local review labelled %d of the submission's %d code units by id, from %d labels; the reviewers' own labels would have named %d",
                applied.getLabelled(), 
                applied.getUnits(), 
                labels.getLabels().size(), 
                byName.getApplied()));
        if (CollectionUtils.isNotEmpty(applied.getUnknownIds())) {
            getLog().warn(String.format("%d label(s) named an id the review was not given and were dropped: %s",
                    applied.getUnknownIds().size(),
                    applied.getUnknownIds().stream().limit(UNMATCHED_LABEL_SAMPLE).toList()));
        }
        if (applied.getLabelled() == 0) {
            getLog().warn("the labelling turn labelled none of the submission's code units; the reviewers' own labels are used instead");
        }
        return applied.getLabelled();
    }
    private void labelByName(AnalysisSubmissionModel submission, LocalReview finished) {
        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission, finished, DefaultLanguageProcessors.reviewLanguages());
        getLog().info(String.format("local review labelled %d of the submission's %d code units, from %d labels",
                labelling.getApplied(), labelling.getUnits(), labelling.getReturned()));
        if (labelling.getReturned() == 0) {
            getLog().warn("the local review returned no code-unit labels: every unit of this commit is scored with the prompt's label or as MECHANICAL for an organisation scored without the prompt");
        }
        if (CollectionUtils.isNotEmpty(labelling.getUnmatched())) {
            getLog().warn(String.format("%d local review label(s) named no code unit of the submission, or several without a container to choose by, and were dropped: %s",
                    labelling.getUnmatched().size(),
                    labelling.getUnmatched().stream().limit(UNMATCHED_LABEL_SAMPLE).toList()));
        }
    }
    private void logTriage(FindingTriage triage) {
        Multiset<FindingVerdict.Verdict> verdicts = HashMultiset.create(triage.getVerdicts().stream().map(FindingVerdict::getVerdict).toList());
        getLog().info(String.format("local review triaged %d static-analysis findings: %d defects, %d false positives, %d harmless",
                verdicts.size(), verdicts.count(FindingVerdict.Verdict.DEFECT), verdicts.count(FindingVerdict.Verdict.FALSE_POSITIVE),
                verdicts.count(FindingVerdict.Verdict.HARMLESS)));
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
}
