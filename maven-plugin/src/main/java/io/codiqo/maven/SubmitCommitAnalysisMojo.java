package io.codiqo.maven;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jgit.lib.Constants;

import io.codiqo.api.RunArgs;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.CodiqoServer;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.HttpCodiqoServer;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.outcome.AnalysedProject;
import io.codiqo.submit.outcome.CommitOutcome;
import io.codiqo.submit.outcome.CommitRun;
import io.codiqo.submit.outcome.ReviewStarter;
import io.codiqo.util.JGit;

/**
 * Analyses one commit and submits it to Codiqo, or excludes it, through the commit's run ({@link CommitRun}) that the
 * Gradle worker runs too, with Maven's build of the commit behind {@link MavenCommitBuild}.
 */
@Mojo(name = "submit-commit-analysis",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class SubmitCommitAnalysisMojo extends AbstractAnalyzeMojo {
    @Parameter(property = "codiqo.commitId", required = true)
    private String commitId;

    @Parameter(property = "codiqo.firstParentOnly", defaultValue = "true")
    private boolean firstParentOnly;

    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.resourceUrl", defaultValue = RunArgs.DEFAULT_RESOURCE_URL)
    private String resourceUrl;

    /**
     * Made in {@link #doPrepare}, once the run knows whether it hides the source: it keeps that hiding for every model it
     * sends, so a server made before would send a hidden-source project's code in plain.
     */
    private CodiqoServer server;

    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        super.doPrepare(args);

        requireResolvableCommit(args, commitId);
        args.setCommitId(JGit.resolveCommit(args.getGit(), commitId));
        args.setFirstParentOnly(firstParentOnly);
        /**
         * Resolved here, against the user's repository: once the clone checks the analyzed commit out, HEAD means that
         * commit itself, so resolving later would rank hotspots at the analyzed commit instead of the run's tip.
         */
        args.setHotspotsCommitId(JGit.resolveCommit(args.getGit(), StringUtils.defaultIfBlank(args.getHotspotsCommitId(), Constants.HEAD)));
        applyReviewSettings(args);

        /** the project is the root POM's {@code groupId:artifactId}, the code its submissions name it by */
        server = new HttpCodiqoServer(
                apiUrl,
                resourceUrl,
                () -> MavenCredentials.resolve(apiKey, authUrl, resourceUrl, getLog()),
                connectTimeoutSeconds,
                readTimeoutSeconds,
                project.getGroupId() + ":" + project.getArtifactId(),
                sourceHiding(),
                new MavenMessageReporter(getLog()));
    }
    /**
     * The commit is admitted from the developer's repository, before anything is cloned, built or paid for; the halt
     * check in it resolves the member's login while the developer is at the terminal, where a browser login opened
     * later would go unnoticed. A JDK the language server cannot start on fails the run after that and before the
     * commit's run, so it halts nothing: it is the machine's to fix, and the next scheduled run fails the same cheap way
     * until it is. A commit excluded before needs no language server, so it is excluded whatever the JDK.
     */
    @Override
    protected void doExecute(RunArgs args) throws Exception {
        CommitRun commit = new CommitRun(
                args,
                server,
                new MavenCommitBuild(this, args),
                new AnalysedProject(project.getGroupId() + ":" + project.getArtifactId(), project.getName(), clientInfo()),
                new OutputSerializer(preferYaml, new MavenLogFactory(getLog()).getLogger(OutputSerializer.class)),
                ReviewStarter.LOCAL,
                new MavenLogFactory(getLog()));
        if (commit.admit().isEmpty()) {
            if (JGit.isMerge(args.getGit(), args.getCommitId())) {
                getLog().info(String.format("merge commit %s analyzed via first-parent delta, credited to side-branch author %s",
                        args.getCommitId(), CommitExclusions.creditedAuthorEmail(args)));
            }
            DefaultLanguageProcessors.requireRuntime(args);

            /** the run fails with the failure; a halt is already recorded, and the clone already removed */
            if (commit.run() instanceof CommitOutcome.Failed failed) {
                throw failed.getCause();
            }
        }
    }
}
