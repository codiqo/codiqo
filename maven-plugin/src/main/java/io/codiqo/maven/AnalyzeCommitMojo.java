package io.codiqo.maven;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.github.classgraph.ScanResult;

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

        /**
         * clone the repository to a temporary location to avoid modifying the user's working directory and excluding uncommitted files.
         */
        StopWatch stopWatch = StopWatch.createStarted();
        StoredConfig originalConfig = args.getGit().getConfig();
        args.setDefaultBranch(args.getGit().getBranch());
        Repository clone = Git.cloneRepository()
                .setURI(args.getGit().getDirectory().toURI().toString())
                .setDirectory(temp)
                .setNoCheckout(true)
                .setCloneAllBranches(true)
                .call()
                .getRepository();
        stopWatch.stop();
        getLog().info(String.format("cloned directory: %s for analysis in %s", temp.getAbsolutePath(), stopWatch.toString()));

        /**
         * copy over the remote URLs to the cloned repository to allow for proper resolution of relative URLs during build.
         */
        StoredConfig cloneConfig = clone.getConfig();
        try {
            for (String remote : originalConfig.getSubsections("remote")) {
                String url = originalConfig.getString("remote", remote, "url");
                String fetch = originalConfig.getString("remote", remote, "fetch");
                if (StringUtils.isNotEmpty(url)) {
                    cloneConfig.setString("remote", remote, "url", url);
                    args.getRemoteUrls().add(url);
                }
                if (StringUtils.isNotEmpty(fetch)) {
                    cloneConfig.setString("remote", remote, "fetch", fetch);
                }
            }
        } finally {
            cloneConfig.save();
        }

        try {
            /**
             * checkout the specified commit ID for analysis exactly as it was at that point in time (it is cleaned cloned repository w/o anything untracked).
             */
            args.setGit(clone);
            try (Git git = Git.wrap(clone)) {
                git.clean().setCleanDirectories(true).setForce(true).call();
                git.reset().setMode(ResetCommand.ResetType.HARD).call();

                git.checkout().setForced(true).setName(commitId).call();
                getLog().info(String.format("checked out commit ID: %s", commitId));
            }

            /**
             * attempt to build the project at the specified commit ID (potentially completely different multiple modules structure).
             * this may require different JDK/MVN home since the project's build requirements may have changed since then.
             */
            InvocationRequest invocationReq = invocationRequest(args);
            ProjectBuildingRequest buildingReq = buildingRequest();
            ProjectBuildingResult result = buildProject(args, invocationReq, buildingReq);

            Collection<MavenProject> reactors = Lists.newLinkedList();
            buildAndCollectModules(result.getProject(), clone.getWorkTree(), buildingReq, reactors);
            try (ScanResult scan = scanProjects(args, reactors)) {
                super.doExecute(args);
            }
        } finally {
            clone.close();
            FileUtils.deleteDirectory(temp);
        }
    }
}
