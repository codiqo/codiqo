package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.client.ApiClient;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.client.model.CommitIndexBatchModel;
import io.codiqo.client.model.CommitIndexBatchResultModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.MissingAnalysesModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.util.Env;
import io.codiqo.util.JGit;
import io.codiqo.util.RepositoryUrls;

@Mojo(name = "index-commits",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true,
        requiresProject = true)
public class IndexCommitsMojo extends AbstractMojo {
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final int UNKNOWN_PARENTS_SAMPLE_LIMIT = 10;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.indexRef", defaultValue = "HEAD")
    private String indexRef;

    @Parameter(property = "codiqo.commitWindow", defaultValue = "P3M")
    private String commitWindow;

    @Parameter(property = "codiqo.indexBatchSize", defaultValue = "200")
    private int batchSize;

    @Parameter(property = "codiqo.branch")
    private String branch;

    @Parameter(property = "codiqo.includeAuthorEmails")
    private String includeAuthorEmails;

    @Parameter(property = "codiqo.missingAnalysesOutputFile", defaultValue = "${project.build.directory}/codiqo/missing-analyses.txt")
    private File missingAnalysesOutputFile;

    @Parameter(property = "codiqo.missingAnalysesLimit", defaultValue = "1024")
    private int missingAnalysesLimit;

    @Parameter(property = "codiqo.connectTimeoutSeconds", defaultValue = "30")
    private long connectTimeoutSeconds;

    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "60")
    private long readTimeoutSeconds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try (Repository repo = JGit.openRepository(project.getBasedir())) {
            String resolvedApiKey = Env.resolveRequired(apiKey, "codiqo.apiKey");
            String projectId = project.getGroupId() + ":" + project.getArtifactId();
            getLog().info("using projectId: " + projectId);

            String resolvedBranch = resolveBranch(repo);
            getLog().info("using branch: " + resolvedBranch);

            RunArgs args = new RunArgs();
            Optional.ofNullable(includeAuthorEmails).ifPresent(args::setIncludeAuthorEmails);

            Period window = Period.parse(commitWindow);
            Date cutoff = Date.from(LocalDate.now(ZoneOffset.UTC).minus(window).atStartOfDay(ZoneOffset.UTC).toInstant());
            List<CommitModel> commits = extractCommits(repo, args, indexRef, cutoff, resolvedBranch);
            getLog().info("extracted " + commits.size() + " commits since " + cutoff + " (window=" + commitWindow + ")");

            ProjectModel projectMetadata = buildProjectMetadata(projectId, repo);

            CommitIndexApi client = buildClient(resolvedApiKey);
            getLog().info("connecting to " + apiUrl);

            indexBatches(client, projectId, projectMetadata, commits);
            writeMissingAnalyses(client, repo, projectId, resolvedBranch);
        } catch (MojoExecutionException | MojoFailureException err) {
            throw err;
        } catch (Exception err) {
            throw new MojoFailureException(err.getMessage(), err);
        }
    }
    private ProjectModel buildProjectMetadata(String projectId, Repository repo) {
        ProjectModel toReturn = new ProjectModel();
        toReturn.setCode(projectId);
        toReturn.setName(Optional.ofNullable(project.getName()).filter(StringUtils::isNotBlank).orElse(project.getArtifactId()));

        List<URI> repoUrls = Lists.newArrayList();
        JGit.detectRemoteUrls(repo).forEach(rawUrl -> addRepositoryUri(repoUrls, rawUrl, "git remote"));
        if (CollectionUtils.isEmpty(repoUrls)) {
            Optional.ofNullable(project.getScm()).map(Scm::getDeveloperConnection).filter(StringUtils::isNotBlank).ifPresent(rawUrl -> addRepositoryUri(repoUrls, rawUrl, "project.scm.developerConnection"));
            Optional.ofNullable(project.getScm()).map(Scm::getConnection).filter(StringUtils::isNotBlank).ifPresent(rawUrl -> addRepositoryUri(repoUrls, rawUrl, "project.scm.connection"));
            Optional.ofNullable(project.getScm()).map(Scm::getUrl).filter(StringUtils::isNotBlank).ifPresent(rawUrl -> addRepositoryUri(repoUrls, rawUrl, "project.scm.url"));
        }
        if (!repoUrls.isEmpty()) {
            toReturn.setRepositoryUrls(repoUrls);
        }

        try {
            JGit.detectDefaultBranch(repo).ifPresent(toReturn::setDefaultBranch);
        } catch (Exception err) {
            getLog().warn("failed to detect default branch: " + err.getMessage());
        }

        return toReturn;
    }
    private CommitIndexApi buildClient(String key) {
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(Strings.CS.removeEnd(apiUrl, "/"));
        apiClient.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        apiClient.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        apiClient.setRequestInterceptor(builder -> builder.header(API_KEY_HEADER, key));
        return new CommitIndexApi(apiClient);
    }
    private void indexBatches(
            CommitIndexApi client,
            String projectId,
            ProjectModel projectMetadata,
            List<CommitModel> commits) throws ApiException {
        int totalAccepted = 0;
        Set<String> unknownParents = Sets.newLinkedHashSet();

        for (List<CommitModel> chunk : Lists.partition(commits, batchSize)) {
            CommitIndexBatchModel batch = new CommitIndexBatchModel().commits(chunk).project(projectMetadata);
            CommitIndexBatchResultModel result = ApiRetry.call(
                    getLog(),
                    "indexCommits",
                    apiUrl,
                    () -> client.indexCommits(projectId, batch));
            totalAccepted += Optional.ofNullable(result.getAccepted()).orElse(0);
            if (CollectionUtils.isNotEmpty(result.getUnknownParents())) {
                unknownParents.addAll(result.getUnknownParents());
            }
        }
        getLog().info("indexed " + totalAccepted + "/" + commits.size() + " commits");

        if (CollectionUtils.isNotEmpty(unknownParents)) {
            String sample = Joiner.on(", ").join(Iterables.limit(unknownParents, UNKNOWN_PARENTS_SAMPLE_LIMIT));
            String suffix = unknownParents.size() > UNKNOWN_PARENTS_SAMPLE_LIMIT ? " ..." : "";
            getLog().warn("server reported " + unknownParents.size() + " unknown parent SHAs (re-run with a wider codiqo.commitWindow): " + sample + suffix);
        }
    }
    private void writeMissingAnalyses(CommitIndexApi client, Repository repo, String projectId, String resolvedBranch) throws IOException, ApiException {
        MissingAnalysesModel response = ApiRetry.call(
                getLog(),
                "listMissingAnalyses",
                apiUrl,
                () -> client.listMissingAnalyses(projectId, resolvedBranch, missingAnalysesLimit));
        List<String> shas = Optional.ofNullable(response.getCommitShas()).orElse(Collections.emptyList());

        List<String> analyzable = Lists.newArrayListWithExpectedSize(shas.size());
        int skippedMissingCommit = 0;
        int skippedMissingParent = 0;

        try (RevWalk walk = new RevWalk(repo)) {
            for (String sha : shas) {
                ObjectId commitId = repo.resolve(sha);
                if (Objects.isNull(commitId)) {
                    skippedMissingCommit++;
                    continue;
                }
                RevCommit commit = walk.parseCommit(commitId);
                if (commit.getParentCount() > 0 && Objects.isNull(repo.resolve(commit.getParent(0).getId().getName()))) {
                    skippedMissingParent++;
                    continue;
                }
                analyzable.add(sha);
            }
        }

        FileUtils.forceMkdir(missingAnalysesOutputFile.getParentFile());
        FileUtils.writeLines(missingAnalysesOutputFile, StandardCharsets.UTF_8.name(), analyzable);
        getLog().info("wrote " + analyzable.size() + " missing-analysis SHAs to " + missingAnalysesOutputFile.getAbsolutePath());
        if (skippedMissingCommit > 0 || skippedMissingParent > 0) {
            getLog().warn("skipped " + skippedMissingCommit + " commits not present locally and "
                    + skippedMissingParent + " commits whose first parent is not present locally"
                    + " (deepen the Jenkins clone if you want these analyzed)");
        }
    }
    private String resolveBranch(Repository repo) throws IOException, MojoExecutionException {
        if (StringUtils.isNotBlank(branch)) {
            return branch.trim();
        }
        String current = repo.getBranch();
        if (StringUtils.isNotBlank(current) && !current.matches("^[a-f0-9]{40}$")) {
            return current;
        }
        return JGit.detectDefaultBranch(repo)
                .orElseThrow(() -> new MojoExecutionException(
                        "cannot resolve branch: HEAD is detached and no default branch is available; set -Dcodiqo.branch explicitly"));
    }
    static List<CommitModel> extractCommits(
            Repository repo,
            RunArgs filterArgs,
            String indexRef,
            Date cutoff,
            String branch) throws Exception {
        List<CommitModel> toReturn = Lists.newArrayList();

        ObjectId startId = repo.resolve(indexRef);
        if (Objects.isNull(startId)) {
            throw new MojoExecutionException("cannot resolve codiqo.indexRef: " + indexRef);
        }

        Map<String, List<String>> branchIndex = JGit.buildBranchIndex(repo);

        try (RevWalk walk = new RevWalk(repo)) {
            walk.sort(RevSort.TOPO);
            walk.setRevFilter(CommitTimeRevFilter.after(cutoff.toInstant()));
            walk.markStart(walk.parseCommit(startId));

            for (RevCommit commit : walk) {
                List<String> branches = branchIndex.getOrDefault(commit.getName(), Collections.emptyList());
                if (BooleanUtils.or(new boolean[] {
                        BooleanUtils.negate(branches.contains(branch)),
                        BooleanUtils.negate(filterArgs.matchesByAuthor(commit.getAuthorIdent().getEmailAddress()))
                })) {
                    continue;
                }
                toReturn.add(toCommitModel(commit, branches));
            }
        }
        return toReturn;
    }
    private static CommitModel toCommitModel(RevCommit commit, List<String> branches) {
        CommitModel toReturn = new CommitModel();

        toReturn.setSha(commit.getName());
        toReturn.setMessage(commit.getFullMessage());
        toReturn.setAuthor(commit.getAuthorIdent().getName());
        toReturn.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
        toReturn.setTimestamp(commit.getAuthorIdent().getWhenAsInstant().atOffset(ZoneOffset.UTC));

        toReturn.setParents(JGit.parentShas(commit));
        toReturn.setBranches(branches);
        toReturn.setIsMerge(commit.getParentCount() > 1);

        JGit.detectRevertedSha(commit.getFullMessage()).ifPresent(sha -> {
            toReturn.setIsRevert(true);
            toReturn.setRevertedCommitId(sha);
        });
        return toReturn;
    }
    static URI toUri(String raw) throws URISyntaxException {
        return RepositoryUrls.toUri(raw);
    }
    private void addRepositoryUri(List<URI> repoUrls, String rawUrl, String source) {
        try {
            URI repositoryUri = RepositoryUrls.toUri(rawUrl);
            if (!repoUrls.contains(repositoryUri)) {
                repoUrls.add(repositoryUri);
            }
        } catch (URISyntaxException err) {
            getLog().warn("failed to parse repository URL from " + source + ": " + rawUrl + " (" + err.getMessage() + ")");
        }
    }
}
