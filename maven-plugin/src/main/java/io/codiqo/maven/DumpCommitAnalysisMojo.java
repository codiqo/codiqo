package io.codiqo.maven;

import java.util.Objects;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import io.codiqo.api.RunArgs;
import io.codiqo.maven.populator.SubmissionContext;

@Mojo(name = "dump-commit-analysis",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class DumpCommitAnalysisMojo extends AnalyzeCommitMojo {
    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        if (Objects.isNull(outputDirectory)) {
            throw new MojoFailureException("codiqo.outputDirectory is required for dump-commit-analysis");
        }
        super.doPrepare(args);
    }
    @Override
    protected void doLlmScoring(SubmissionContext ctx) {
    }
}
