package io.codiqo.maven;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.maven.populator.SubmissionContext;
import io.codiqo.util.Env;

@Mojo(name = "submit-commit-analysis",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class SubmitCommitAnalysisMojo extends AnalyzeCommitMojo {
    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Override
    protected void doLlmScoring(SubmissionContext ctx) throws Exception {
        String resolvedApiKey = Env.resolveRequired(apiKey, "codiqo.apiKey");

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
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category) throws Exception {
        String resolvedApiKey = Env.resolveRequired(apiKey, "codiqo.apiKey");

        AnalysisSubmitter.exclude(
                apiUrl,
                resolvedApiKey,
                connectTimeoutSeconds,
                readTimeoutSeconds,
                commitSha,
                reason,
                category,
                getLog());
    }
}
