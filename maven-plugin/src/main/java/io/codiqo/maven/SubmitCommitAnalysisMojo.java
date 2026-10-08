package io.codiqo.maven;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.llm.review.FindingTriage;
import io.codiqo.llm.review.FindingVerdict;
import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.review.LocalReviewModels;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.StaticFinding;
import io.codiqo.llm.review.StaticFindings;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.AnalysisSubmitter;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.submit.hotspots.FixCommits;
import io.codiqo.submit.hotspots.HotspotSnapshots;
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

    /**
     * Runs a local agent review of the commit next to the analysis and attaches its bugs and token usage to the
     * submission. Only the commit checked out as a clean HEAD is reviewed: the agents read surrounding files from the
     * working tree, so for any other commit they would see code that is not the commit's.
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

    /** retries of a call through the Codiqo proxy that failed on the way (network error, timeout, 429 or 5xx) */
    @Parameter(property = "codiqo.review.relayRetries")
    private Integer reviewRelayRetries;

    /** how long a call through the Codiqo proxy may take to start answering */
    @Parameter(property = "codiqo.review.relayTimeoutSeconds")
    private Integer reviewRelayTimeoutSeconds;

    /**
     * Also has the review assess the commit (code-unit difficulty, summary, tags, task types, quality dimensions);
     * the server scores from it once the organization is switched over, and only compares it until then.
     */
    @Parameter(property = "codiqo.review.assess")
    private Boolean reviewAssess;

    /**
     * After the build, ask a fork of the review's coordinator session which PMD and SpotBugs findings on added lines
     * are real defects. A confirmed one joins the review's bugs; every verdict places its finding in the assessment's
     * static-analysis review, so this needs {@code codiqo.review.assess} for anything but the defects.
     */
    @Parameter(property = "codiqo.review.triage")
    private Boolean reviewTriage;

    private BackgroundReview<LocalReviewer.ReviewAndTriage> backgroundReview;
    /**
     * Resolved once per run and shared by every call to Codiqo. Each call used to resolve its own, so a developer with
     * no stored login got one browser login from the fix-commit lookup in the middle of the analysis, while the
     * language servers held their memory, and when nobody approved it in time, a second one at the submission.
     */
    private CodiqoCredential credential;
    /** completed with the analysis's findings just before the review is joined; the review waits for it to triage */
    private final CompletableFuture<List<StaticFinding>> buildFindings = new CompletableFuture<>();

    @Override
    protected void doBeforeAnalysis(RunArgs args) throws Exception {
        Optional.ofNullable(review).ifPresent(args::setReviewEnabled);
        Optional.ofNullable(reviewAssess).ifPresent(args::setReviewAssess);
        Optional.ofNullable(reviewTriage).ifPresent(args::setReviewTriage);
        Optional.ofNullable(reviewExecutable).ifPresent(args::setReviewExecutable);
        Optional.ofNullable(reviewBaseUrl).ifPresent(args::setReviewBaseUrl);
        Optional.ofNullable(reviewCoordinatorModel).ifPresent(args::setReviewCoordinatorModel);
        Optional.ofNullable(reviewReviewerModel).ifPresent(args::setReviewReviewerModel);
        Optional.ofNullable(reviewTimeoutMinutes).ifPresent(minutes -> args.setReviewTimeout(Duration.ofMinutes(minutes)));
        Optional.ofNullable(reviewRelayRetries).ifPresent(args::setReviewRelayRetries);
        Optional.ofNullable(reviewRelayTimeoutSeconds).ifPresent(seconds -> args.setReviewRelayRequestTimeout(Duration.ofSeconds(seconds)));

        /** before the build, while the developer is at the terminal: a browser login opened later would go unnoticed */
        credential();

        if (args.isReviewEnabled()) {
            Repository repository = args.getGit();
            ObjectId head = repository.resolve(Constants.HEAD);
            boolean clean;
            try (Git git = Git.wrap(repository)) {
                clean = git.status().call().isClean();
            }

            if (Objects.nonNull(head) && head.name().equals(args.getCommitId()) && clean) {
                ReviewEndpoint endpoint = new ReviewEndpoint(args.getReviewBaseUrl(), null, null);
                if (endpoint.isProxiedBy(resourceUrl)) {
                    endpoint = new ReviewEndpoint(args.getReviewBaseUrl(), null, credential());
                }
                LocalReviewer reviewer = new LocalReviewer(args, endpoint, LocalReviewGuidance.read(args, getLog()),
                        new MavenLogFactory(getLog()).getLogger(LocalReviewer.class));
                Path workTree = repository.getWorkTree().toPath();
                String commit = args.getCommitId();
                Optional<LocalReviewer.FindingsSource> findings = args.isReviewTriage() ? Optional.of(buildFindings::get) : Optional.empty();
                backgroundReview = BackgroundReview.start(() -> reviewer.reviewThenTriage(workTree, commit, findings));
                getLog().info("local review of " + args.getCommitId() + " started next to the analysis");
            } else {
                getLog().info("local review skipped: " + args.getCommitId() + " is not the clean HEAD of " + repository.getWorkTree());
            }
        }
    }
    @Override
    protected void doExecute(RunArgs args) throws Exception {
        try {
            super.doExecute(args);
        } finally {
            if (Objects.nonNull(backgroundReview)) {
                backgroundReview.close();
            }
        }
    }
    @Override
    protected void doLlmScoring(SubmissionContext ctx) throws Exception {
        if (Objects.nonNull(backgroundReview)) {
            buildFindings.complete(StaticFindings.introduced(ctx.getSubmissionModel()));
            LocalReviewer.ReviewAndTriage reviewed = backgroundReview.await(LocalReviewer.longestReview(ctx.getArgs(), ctx.getArgs().isReviewTriage()));
            LocalReview finished = reviewed.getReview();
            reviewed.getTriage().ifPresent(this::logTriage);
            /**
             * The review is mapped before anything is attached to the submission, and its labels are applied all or
             * nothing, so a failure while mapping leaves no half-attached review behind on a submission that is sent.
             */
            LocalReviewModel model = LocalReviewModels.toModel(finished, reviewed.getTriage());
            if (Objects.nonNull(finished.getAssessment())) {
                int labelled = LocalReviewModels.applyBlockCategories(ctx.getSubmissionModel(), finished);
                getLog().info(String.format("local review labelled %d of the submission's code units", labelled));
            }
            ctx.getSubmissionModel().setLocalReview(model);
        }

        try (ProgressStage stage = ProgressStage.start(ctx.getArgs(), "submit")) {
            AnalysisAcceptedModel response = AnalysisSubmitter.submit(
                    apiUrl,
                    credential(),
                    connectTimeoutSeconds,
                    readTimeoutSeconds,
                    ctx.getSubmissionModel(),
                    new MavenMessageReporter(getLog()));
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
