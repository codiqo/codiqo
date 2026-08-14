package io.codiqo.maven;

import java.util.List;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.maven.auth.BrowserLogin;
import io.codiqo.submit.SubmissionContext;

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

    @Override
    protected void doLlmScoring(SubmissionContext ctx) throws Exception {
        String resolvedApiKey = BrowserLogin.resolveApiKey(apiKey, authUrl, getLog());

        AnalysisAcceptedModel response = AnalysisSubmitter.submit(
                apiUrl,
                resolvedApiKey,
                connectTimeoutSeconds,
                readTimeoutSeconds,
                ctx.getSubmissionModel(),
                getLog());
        getLog().info(String.format("accepted analysis id: %s status: %s", response.getAnalysisId(), response.getStatus()));
    }
    @Override
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category, String detail, List<FileChangeModel> files, ProjectMetricsModel projectMetrics) throws Exception {
        String resolvedApiKey = BrowserLogin.resolveApiKey(apiKey, authUrl, getLog());

        AnalysisSubmitter.exclude(
                apiUrl,
                resolvedApiKey,
                connectTimeoutSeconds,
                readTimeoutSeconds,
                commitSha,
                reason,
                category,
                detail,
                files,
                projectMetrics,
                getLog());
    }
}
