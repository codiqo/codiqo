package io.codiqo.maven;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.AnalysisSubmitter;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.ProjectTags;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.util.JGit;
import io.codiqo.submit.review.LocalReviewException;
import io.codiqo.submit.review.LocalReviewRun;
import io.codiqo.maven.logging.MavenLogFactory;

/**
 * Scores the working tree as if it were a commit.
 *
 * <p>Submits by default and fails when the server cannot be reached, or when it reports the submitted analysis as
 * failed; {@code codiqo.submit=false} keeps the run entirely local. It is the one goal a developer runs before
 * anything is committed, so it is also where the browser login belongs: with no {@code codiqo.apiKey} configured, the
 * first run authorises the machine and every run after it reuses the stored key.
 */
@Mojo(name = "analyze-uncommitted-changes",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class AnalyzeUncommittedChangesMojo extends AbstractAnalyzeMojo {
    /** short enough that an interactive run feels responsive, long enough not to hammer the API while scoring runs */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    @Parameter(property = "codiqo.include.untracked", defaultValue = "true")
    private boolean includeUntracked;

    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.resourceUrl", defaultValue = RunArgs.DEFAULT_RESOURCE_URL)
    private String resourceUrl;

    /** off keeps the run entirely local — the analysis is only written out, nothing leaves the machine */
    @Parameter(property = "codiqo.submit", defaultValue = "true")
    private boolean submit;

    /**
     * How long to wait for the server to finish scoring. Deliberately not {@link RunArgs#getBuildTimeout()}: that
     * bounds a forked build in CI and is sized for one, so borrowing it here left an interactive terminal blocked for
     * three quarters of an hour whenever the scorer wedged.
     */
    @Parameter(property = "codiqo.scoringTimeoutMinutes", defaultValue = "10")
    private int scoringTimeoutMinutes;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true)
    protected List<MavenProject> reactors;

    /** resolved once per run, so the review and the submission share one browser login */
    private CodiqoCredential credential;
    private LocalReviewRun reviewRun;

    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        super.doPrepare(args);

        args.setIncludeUntracked(includeUntracked);
        applyReviewSettings(args);
    }
    @Override
    protected void doExecute(RunArgs args) throws Exception {
        try (ClassGraphSpec scan = scanProjects(args, reactors)) {
            startReview(args);
            super.doExecute(args);
        } finally {
            if (Objects.nonNull(reviewRun)) {
                reviewRun.close();
            }
        }
    }
    /**
     * The changes are reviewed as a commit object of the working tree, beside the analysis, so a developer's work in
     * progress is judged the way a submitted commit is. Only a submitted run is reviewed: the review spends the
     * organization's LLM budget through the proxy, which a local run has no reason to.
     */
    private void startReview(RunArgs args) throws Exception {
        if (BooleanUtils.and(new boolean[] { submit, args.isReviewEnabled() })) {
            Path workTree = args.getGit().getWorkTree().toPath();
            String sha = WorkingTreeCommit.create(workTree, includeUntracked);
            if (CommitExclusions.hasAnalyzableFile(JGit.changedFileNames(args.getGit(), sha), DefaultLanguageProcessors.supportedExtensions())) {
                CodiqoCredential creds = credential();
                String projectCode = project.getGroupId() + ":" + project.getArtifactId();
                reviewRun = LocalReviewRun.start(
                        args,
                        workTree,
                        sha,
                        LocalReviewRun.endpoint(args, resourceUrl, projectCode, () -> creds),
                        LocalReviewGuidance.read(args, getLog()),
                        () -> ProjectTags.fetch(apiUrl, creds, connectTimeoutSeconds, readTimeoutSeconds, projectCode, new MavenMessageReporter(getLog())),
                        DefaultLanguageProcessors.reviewLanguages(),
                        new MavenLogFactory(getLog()));
                getLog().info("local review of the uncommitted changes started next to the analysis");
            } else {
                getLog().info("local review skipped: the uncommitted changes touch no file the analysis reads");
            }
        }
    }
    private CodiqoCredential credential() throws Exception {
        if (Objects.isNull(credential)) {
            credential = MavenCredentials.resolve(apiKey, authUrl, resourceUrl, getLog());
        }
        return credential;
    }
    @Override
    protected void doSubmit(SubmissionContext ctx) throws Exception {
        if (submit) {
            /** a review that failed leaves the changes unjudged: the developer is told, and nothing is sent */
            if (Objects.nonNull(reviewRun)) {
                try {
                    reviewRun.attachTo(ctx);
                } catch (LocalReviewException err) {
                    throw new MojoFailureException("the uncommitted changes were not reviewed, so nothing was submitted: " + err.getMessage(), err);
                }
            }
            CodiqoCredential creds = credential();
            Log reporter = new MavenMessageReporter(getLog());

            AnalysisAcceptedModel response = AnalysisSubmitter.submitUncommitted(
                    apiUrl,
                    creds,
                    connectTimeoutSeconds,
                    readTimeoutSeconds,
                    ctx.getSubmissionModel(),
                    sourceHiding(),
                    reporter);
            getLog().info(String.format("accepted uncommitted analysis id: %s status: %s",
                    response.getAnalysisId(),
                    response.getStatus()));

            AnalysisResultModel result = AnalysisSubmitter.awaitCompletion(
                    apiUrl,
                    creds,
                    connectTimeoutSeconds,
                    readTimeoutSeconds,
                    response.getAnalysisId(),
                    Duration.ofMinutes(scoringTimeoutMinutes),
                    POLL_INTERVAL,
                    reporter);

            /**
             * a terminal FAILED is the server saying it accepted the submission and could not score it, so the goal
             * must fail too — a script or IDE task reading only the exit code would otherwise treat it as a clean
             * run. A poll timeout stays a success: the analysis is still running server-side, and awaitCompletion
             * already logged where to read the result.
             */
            if (AnalysisResultModel.StatusEnum.FAILED == result.getStatus()) {
                throw new MojoFailureException(String.format(
                        "analysis %s was accepted but failed server-side — nothing was scored", response.getAnalysisId()));
            }
        }
    }
}
