package io.codiqo.maven;

import java.io.File;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.snakeyaml.engine.v2.api.LoadSettings;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.StdDateFormat;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import io.codiqo.api.RunArgs;
import io.codiqo.client.ApiException;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.maven.auth.BrowserLogin;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.AnalysisSubmitter;

@Mojo(name = "submit-analysis-file",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true,
        requiresProject = false)
public class SubmitAnalysisFileMojo extends AbstractMojo {
    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.inputFile", required = true)
    private File inputFile;

    @Parameter(property = "codiqo.connectTimeoutSeconds", defaultValue = "30")
    private long connectTimeoutSeconds;

    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "300")
    private long readTimeoutSeconds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String resolvedApiKey = BrowserLogin.resolveApiKey(apiKey, authUrl, getLog());

        AnalysisSubmissionModel submission;
        try {
            submission = yamlMapper().readValue(inputFile, AnalysisSubmissionModel.class);
        } catch (Exception err) {
            throw new MojoExecutionException("failed to read submission file: " + inputFile.getAbsolutePath(), err);
        }
        getLog().info("read submission from " + inputFile.getAbsolutePath());

        try {
            AnalysisAcceptedModel response = AnalysisSubmitter.submit(
                    apiUrl,
                    resolvedApiKey,
                    connectTimeoutSeconds,
                    readTimeoutSeconds,
                    submission,
                    new MavenMessageReporter(getLog()));
            getLog().info(String.format("accepted analysis id: %s status: %s", response.getAnalysisId(), response.getStatus()));
        } catch (ApiException err) {
            throw new MojoExecutionException(err);
        }
    }
    private static ObjectMapper yamlMapper() {
        LoadSettings loadSettings = LoadSettings.builder().setCodePointLimit(Integer.MAX_VALUE).build();

        YAMLFactory yamlFactory = YAMLFactory.builder().loadSettings(loadSettings).build();

        return YAMLMapper.builder(yamlFactory)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL))
                .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true))
                .build();
    }
}
