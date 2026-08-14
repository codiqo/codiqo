package io.codiqo.maven;

import java.util.List;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.maven.auth.BrowserLogin;
import io.codiqo.submit.SubmissionContext;

/**
 * Scores the working tree as if it were a commit.
 *
 * <p>Submits by default and fails when the server cannot be reached; {@code codiqo.submit=false} keeps the run
 * entirely local. It is the one goal a developer runs before anything is committed, so it is also where the browser
 * login belongs: with no {@code codiqo.apiKey} configured, the first run authorises the machine and every run after
 * it reuses the stored key.
 */
@Mojo(name = "analyze-uncommitted-changes",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class AnalyzeUncommittedChangesMojo extends AbstractAnalyzeMojo {
    @Parameter(property = "codiqo.include.untracked", required = false, defaultValue = "true")
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
            AnalysisAcceptedModel response = AnalysisSubmitter.submitUncommitted(
                    apiUrl,
                    BrowserLogin.resolveApiKey(apiKey, authUrl, getLog()),
                    connectTimeoutSeconds,
                    readTimeoutSeconds,
                    ctx.getSubmissionModel(),
                    getLog());
            getLog().info(String.format("accepted uncommitted analysis id: %s status: %s",
                    response.getAnalysisId(), response.getStatus()));
            return;
        }

        super.doLlmScoring(ctx);
    }
}
