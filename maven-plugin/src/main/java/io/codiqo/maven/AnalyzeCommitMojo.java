package io.codiqo.maven;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;

@Mojo(name = "analyze-commit",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class AnalyzeCommitMojo extends AbstractAnalyzeMojo {
    @Parameter(property = "codiqo.commitId", required = true)
    private String commitId;

    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        super.doPrepare(args);

        args.setCommitId(commitId);
        resolveCommit(args, commitId);
    }
    @Override
    protected void doExecute(RunArgs args) throws Exception {
        File temp = Files.createTempDirectory("codiqo").toFile();
        temp.deleteOnExit();

        StopWatch stopWatch = StopWatch.createStarted();
        Repository clone = Git.cloneRepository()
                .setURI(args.getGit().getDirectory().toURI().toString())
                .setDirectory(temp)
                .setNoCheckout(true)
                .setCloneAllBranches(true)
                .call()
                .getRepository();
        stopWatch.stop();
        getLog().info(String.format("cloned directory: %s for analysis in %s", temp.getAbsolutePath(), stopWatch.toString()));

        try {
            args.setGit(clone);
            try (Git git = Git.wrap(clone)) {
                git.checkout().setName(commitId).call();
                getLog().info(String.format("checked out commit ID: %s", commitId));
            }

            Collection<MavenProject> reactorProjects = Lists.newLinkedList();

            InvocationRequest invocationReq = invocationRequest(args);
            ProjectBuildingRequest buildingReq = buildingRequest();
            ProjectBuildingResult result = buildProject(args, invocationReq, buildingReq);
            buildAndCollectModules(result.getProject(), clone.getWorkTree(), buildingReq, reactorProjects);
            captureProjects(args, reactorProjects);

            super.doExecute(args);
        } finally {
            clone.close();
            FileUtils.deleteDirectory(temp);
        }
    }
}
