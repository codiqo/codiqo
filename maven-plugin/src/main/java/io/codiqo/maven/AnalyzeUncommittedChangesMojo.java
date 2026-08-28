package io.codiqo.maven;

import java.time.Duration;
import java.util.List;

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
import io.codiqo.maven.auth.BrowserLogin;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.AnalysisSubmitter;
import io.codiqo.submit.SubmissionContext;

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

    /** off keeps the run entirely local — analysis and the console report, nothing leaves the machine */
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

    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        super.doPrepare(args);

        args.setIncludeUntracked(includeUntracked);
    }
    @Override
    protected void doExecute(RunArgs args) throws Exception {
        try (ClassGraphSpec scan = scanProjects(args, reactors)) {
            super.doExecute(args);
        }
    }
    @Override
    protected void doLlmScoring(SubmissionContext ctx) throws Exception {
        if (submit) {
            String resolvedApiKey = BrowserLogin.resolveApiKey(apiKey, authUrl, getLog());
            Log reporter = new MavenMessageReporter(getLog());

            AnalysisAcceptedModel response = AnalysisSubmitter.submitUncommitted(
                    apiUrl,
                    resolvedApiKey,
                    connectTimeoutSeconds,
                    readTimeoutSeconds,
                    ctx.getSubmissionModel(),
                    reporter);
            getLog().info(String.format("accepted uncommitted analysis id: %s status: %s",
                    response.getAnalysisId(),
                    response.getStatus()));

            AnalysisResultModel result = AnalysisSubmitter.awaitCompletion(
                    apiUrl,
                    resolvedApiKey,
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
            return;
        }

        super.doLlmScoring(ctx);
    }
}
