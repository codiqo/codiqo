package io.codiqo.maven;

import java.util.List;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import io.codiqo.api.RunArgs;

@Mojo(name = "analyze-uncommitted-changes",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class AnalyzeUncommittedChangesMojo extends AbstractAnalyzeMojo {
    @Parameter(property = "codiqo.include.untracked", required = false, defaultValue = "true")
    private boolean includeUntracked;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true)
    protected List<MavenProject> reactorProjects;

    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        super.doPrepare(args);
        args.setIncludeUntracked(includeUntracked);
    }
    @Override
    protected void doExecute(RunArgs args) throws Exception {
        captureProjects(args, reactorProjects);
        super.doExecute(args);
    }
}