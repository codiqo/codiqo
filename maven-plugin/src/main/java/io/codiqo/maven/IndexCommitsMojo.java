package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.lib.Repository;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.maven.auth.BrowserLogin;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.CommitIndexPublisher;
import io.codiqo.submit.CommitIndexPublisher.MissingAnalysesSelection;
import io.codiqo.submit.CommitIndexer;
import io.codiqo.util.JGit;

@Mojo(name = "index-commits",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true,
        requiresProject = true)
public class IndexCommitsMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.indexRef", defaultValue = "HEAD")
    private String indexRef;

    @Parameter(property = "codiqo.commitWindow", defaultValue = "P3M")
    private String commitWindow;

    @Parameter(property = "codiqo.indexBatchSize", defaultValue = "200")
    private int batchSize;

    @Parameter(property = "codiqo.branch")
    private String branch;

    @Parameter(property = "codiqo.includeBranches")
    private String includeBranches;

    @Parameter(property = "codiqo.includeAuthorEmails")
    private String includeAuthorEmails;

    @Parameter(property = "codiqo.excludeAuthorEmails")
    private String excludeAuthorEmails;

    @Parameter(property = "codiqo.missingAnalysesOutputFile", defaultValue = "${project.build.directory}/codiqo/missing-analyses.txt")
    private File missingAnalysesOutputFile;

    @Parameter(property = "codiqo.missingAnalysesLimit", defaultValue = "1024")
    private int missingAnalysesLimit;

    @Parameter(property = "codiqo.connectTimeoutSeconds", defaultValue = "30")
    private long connectTimeoutSeconds;

    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "60")
    private long readTimeoutSeconds;

    @Parameter(property = "codiqo.firstParentOnly", defaultValue = "true")
    private boolean firstParentOnly;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try (Repository repo = JGit.openRepository(project.getBasedir())) {
            String resolvedApiKey = BrowserLogin.resolveApiKey(apiKey, authUrl, getLog());
            String projectId = project.getGroupId() + ":" + project.getArtifactId();
            getLog().info("using projectId: " + projectId);

            String resolvedBranch = resolveBranch(repo);
            getLog().info("using branch: " + resolvedBranch);

            RunArgs args = new RunArgs();
            Optional.ofNullable(includeBranches).ifPresent(args::setIncludeBranches);
            Optional.ofNullable(includeAuthorEmails).ifPresent(args::setIncludeAuthorEmails);
            Optional.ofNullable(excludeAuthorEmails).ifPresent(args::setExcludeAuthorEmails);
            args.setFirstParentOnly(firstParentOnly);

            Date cutoff = Date.from(LocalDate.now(ZoneOffset.UTC).minus(Period.parse(commitWindow)).atStartOfDay(ZoneOffset.UTC).toInstant());
            List<CommitModel> commits = CommitIndexer.extractCommits(repo, args, indexRef, cutoff, resolvedBranch);
            getLog().info("extracted " + commits.size() + " commits since " + cutoff + " (window=" + commitWindow
                    + ", selection=" + (firstParentOnly ? "first-parent/mainline" : "all-commits") + ")");

            CommitIndexApi client = CommitIndexPublisher.buildClient(apiUrl, resolvedApiKey, connectTimeoutSeconds, readTimeoutSeconds);
            getLog().info("connecting to " + apiUrl);

            Log log = new MavenMessageReporter(getLog());
            CommitIndexPublisher.indexBatches(log, client, apiUrl, projectId, buildProjectMetadata(projectId, repo), commits, batchSize);
            writeMissingAnalyses(log, client, repo, projectId, resolvedBranch);
        } catch (MojoExecutionException | MojoFailureException err) {
            throw err;
        } catch (Exception err) {
            throw new MojoFailureException(err.getMessage(), err);
        }
    }
    /**
     * the git remotes first; the POM's own SCM entries are the fallback for a clone that has none, which is what a
     * Jenkins detached checkout of a mirror looks like.
     */
    private ProjectModel buildProjectMetadata(String projectId, Repository repo) {
        ProjectModel toReturn = new ProjectModel();
        toReturn.setCode(projectId);
        toReturn.setName(Optional.ofNullable(project.getName()).filter(StringUtils::isNotBlank).orElse(project.getArtifactId()));

        Log log = new MavenMessageReporter(getLog());
        List<URI> repoUrls = CommitIndexPublisher.repositoryUrls(log, repo);
        if (CollectionUtils.isEmpty(repoUrls)) {
            Scm scm = project.getScm();
            if (Objects.nonNull(scm)) {
                addScmUri(log, repoUrls, scm.getDeveloperConnection(), "project.scm.developerConnection");
                addScmUri(log, repoUrls, scm.getConnection(), "project.scm.connection");
                addScmUri(log, repoUrls, scm.getUrl(), "project.scm.url");
            }
        }
        if (CollectionUtils.isNotEmpty(repoUrls)) {
            toReturn.setRepositoryUrls(repoUrls);
        }

        try {
            JGit.detectDefaultBranch(repo).ifPresent(toReturn::setDefaultBranch);
        } catch (Exception err) {
            getLog().warn("failed to detect default branch: " + err.getMessage());
        }
        return toReturn;
    }
    private void writeMissingAnalyses(Log log, CommitIndexApi client, Repository repo, String projectId, String resolvedBranch) throws IOException, ApiException {
        MissingAnalysesSelection selection = CommitIndexPublisher.selectAnalyzable(repo,
                CommitIndexPublisher.listMissingAnalyses(log, client, apiUrl, projectId, resolvedBranch, missingAnalysesLimit));

        FileUtils.forceMkdir(missingAnalysesOutputFile.getParentFile());
        FileUtils.writeLines(missingAnalysesOutputFile, StandardCharsets.UTF_8.name(), selection.getAnalyzableShas());
        getLog().info("wrote " + selection.getAnalyzableShas().size() + " missing-analysis SHAs to " + missingAnalysesOutputFile.getAbsolutePath());

        if (BooleanUtils.or(new boolean[]{
                selection.getSkippedMissingCommitCount() > 0,
                selection.getSkippedMissingParentCount() > 0})) {
            getLog().warn("skipped " + selection.getSkippedMissingCommitCount() + " commits not present locally and "
                    + selection.getSkippedMissingParentCount() + " commits whose first parent is not present locally (deepen the Jenkins clone if you want these analyzed)");
        }
    }
    private String resolveBranch(Repository repo) throws IOException, MojoExecutionException {
        if (StringUtils.isNotBlank(branch)) {
            return branch.trim();
        }
        String current = repo.getBranch();
        if (JGit.isDetachedHead(current)) {
            return JGit.detectDefaultBranch(repo).orElseThrow(() -> new MojoExecutionException(
                    "cannot resolve branch: HEAD is detached and no default branch is available; set -Dcodiqo.branch explicitly"));
        }
        return current;
    }
    private static void addScmUri(Log log, List<URI> repoUrls, String rawUrl, String source) {
        if (StringUtils.isNotBlank(rawUrl)) {
            CommitIndexPublisher.addRepositoryUri(log, repoUrls, rawUrl, source);
        }
    }
}
