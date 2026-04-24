package io.codiqo.maven;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import org.yaml.snakeyaml.LoaderOptions;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.codiqo.api.RunArgs;
import io.codiqo.client.ApiException;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.util.Env;

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

    @Parameter(property = "codiqo.inputFile", required = true)
    private File inputFile;

    @Parameter(property = "codiqo.connectTimeoutSeconds", defaultValue = "30")
    private long connectTimeoutSeconds;

    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "300")
    private long readTimeoutSeconds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String resolvedApiKey = Env.resolveRequired(apiKey, "codiqo.apiKey");

        AnalysisSubmissionModel submission;
        try {
            submission = yamlMapper().readValue(inputFile, AnalysisSubmissionModel.class);
        } catch (IOException err) {
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
                    getLog());
            getLog().info(String.format("accepted analysis id: %s status: %s", response.getAnalysisId(), response.getStatus()));
        } catch (ApiException err) {
            throw new MojoExecutionException(err);
        }
    }
    private static ObjectMapper yamlMapper() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(Integer.MAX_VALUE);

        YAMLFactory yamlFactory = YAMLFactory.builder().loaderOptions(loaderOptions).build();

        ObjectMapper mapper = new YAMLMapper(yamlFactory);
        mapper.setDefaultPropertyInclusion(Include.NON_NULL);
        mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
