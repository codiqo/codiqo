package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.submit.AnalysisSubmitter;
import io.codiqo.submit.CommitIndexPublisher;
import io.codiqo.submit.CommitIndexPublisher.MissingAnalysesSelection;
import io.codiqo.submit.CommitIndexer;
import io.codiqo.submit.SourceHiding;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.submit.auth.CodiqoCredentials;
import io.codiqo.util.JGit;

/**
 * Walks git history over a time window, publishes it to the backend and writes the commits that still lack an analysis
 * to a file — the Gradle counterpart of the Maven index-commits goal, sharing {@link CommitIndexer} for the walk and
 * {@link CommitIndexPublisher} for the protocol. The output file drives the per-commit submit loop.
 *
 * <p>Without an API key the backend half is skipped and every commit in the window is written instead, which is what a
 * local dump loop wants: there is no server to say which analyses already exist.
 */
public class CodiqoIndexCommitsTask extends DefaultTask {
    private static final int DEFAULT_MISSING_ANALYSES_LIMIT = 1024;
    private static final String INDEX_OUTPUT_RELATIVE_PATH = "codiqo/indexed-commits.txt";

    @TaskAction
    public void index() throws Exception {
        Project root = getProject().getRootProject();
        CodiqoExtension ext = getProject().getExtensions().getByType(CodiqoExtension.class);

        String indexRef = prop("codiqo.indexRef", Constants.HEAD);
        String commitWindow = prop("codiqo.commitWindow", ext.getCommitWindow());
        Date cutoff = Date.from(LocalDate.now(ZoneOffset.UTC).minus(Period.parse(commitWindow)).atStartOfDay(ZoneOffset.UTC).toInstant());

        /**
         * The commits are chosen by the same settings the analysis reads (branches, author emails, first parent), from
         * the extension as well as -P, so the index never lists a commit the analysis would then filter out.
         */
        RunArgs args = GradleSettings.runArgs(GradleSettings.of(getProject(), ext));

        try (Repository repo = JGit.openRepository(root.getProjectDir())) {
            String branch = resolveBranch(repo);
            List<CommitModel> commits = CommitIndexer.extractCommits(repo, args, indexRef, cutoff, branch);

            long merges = commits.stream().filter(commit -> Boolean.TRUE.equals(commit.getIsMerge())).count();
            long reverts = commits.stream().filter(commit -> Boolean.TRUE.equals(commit.getIsRevert())).count();
            getLogger().lifecycle(String.format(Locale.ROOT,
                    "codiqo: extracted %d commits since %s (window=%s, branch=%s; %d merges, %d reverts)",
                    commits.size(), cutoff, commitWindow, branch, merges, reverts));

            File outputFile = resolveOutputFile(root, ext);
            FileUtils.forceMkdir(outputFile.getParentFile());
            FileUtils.writeLines(outputFile, StandardCharsets.UTF_8.name(), resolveShas(root, ext, repo, args, branch, commits));
            getLogger().lifecycle("codiqo: wrote the commit list to " + outputFile.getAbsolutePath());

            args.writeIndexMetadata(resolveMetadataFile(root, ext).toPath());
        }
    }
    /**
     * the SHAs the caller should analyze. With a key that is the server's missing-analyses answer, narrowed to what
     * this clone can actually diff; without one there is nothing to ask, so the whole extracted window is written.
     */
    private List<String> resolveShas(
            Project root,
            CodiqoExtension ext,
            Repository repo,
            RunArgs args,
            String branch,
            List<CommitModel> commits) throws Exception {
        Optional<CodiqoCredential> credential = resolveCredential(ext);
        if (credential.isEmpty()) {
            getLogger().lifecycle("codiqo: no API key configured and no browser login stored — writing every extracted commit instead of the server's missing-analysis list");
            /**
             * the index keeps an excluded author's commit only for the server to store its exclusion; with no server
             * the analysis would build it just to exclude it
             */
            return commits.stream()
                    .filter(commit -> BooleanUtils.negate(args.isExcludedAuthor(commit.getAuthorEmail())))
                    .map(CommitModel::getSha)
                    .toList();
        }

        String apiUrl = prop("codiqo.apiUrl", ext.getApiUrl());
        String projectId = root.getGroup() + ":" + root.getName();
        getLogger().lifecycle("codiqo: indexing " + commits.size() + " commits for " + projectId + " at " + apiUrl);

        Log log = new SlfLogFactory().getLogger(CodiqoIndexCommitsTask.class);
        CommitIndexApi client = CommitIndexPublisher.buildClient(apiUrl, credential.get(), args.getConnectTimeout().toSeconds(), args.getReadTimeout().toSeconds());

        ProjectModel metadata = new ProjectModel();
        metadata.setCode(projectId);
        metadata.setName(root.getName());
        List<URI> repoUrls = CommitIndexPublisher.repositoryUrls(log, repo);
        if (CollectionUtils.isNotEmpty(repoUrls)) {
            metadata.setRepositoryUrls(repoUrls);
        }
        JGit.detectDefaultBranch(repo).ifPresent(metadata::setDefaultBranch);

        CommitIndexPublisher.indexBatches(log, client, apiUrl, projectId, metadata, commits, RunArgs.INDEX_BATCH_SIZE);

        MissingAnalysesSelection selection = CommitIndexPublisher.selectMissingAnalyses(
                log,
                client,
                AnalysisSubmitter.buildClient(apiUrl, credential.get(), args.getConnectTimeout().toSeconds(), args.getReadTimeout().toSeconds()),
                sourceHiding(root, ext),
                apiUrl,
                projectId,
                branch,
                missingAnalysesLimit(),
                repo,
                args,
                RunArgs.INDEX_BATCH_SIZE);
        if (BooleanUtils.or(new boolean[]{
                selection.getSkippedMissingCommitCount() > 0,
                selection.getSkippedMissingParentCount() > 0})) {
            getLogger().warn("codiqo: skipped " + selection.getSkippedMissingCommitCount() + " commits not present locally and "
                    + selection.getSkippedMissingParentCount() + " whose first parent is not present locally (deepen the clone to analyze these)");
        }
        return selection.getAnalyzableShas();
    }
    /** read as the analysis reads it ({@link GradleModelCollector}): only a hidden run needs the key */
    private static SourceHiding sourceHiding(Project root, CodiqoExtension ext) {
        if (GradleSettings.flag(root, "codiqo.sourceHidden", ext.isSourceHidden())) {
            return SourceHiding.of(true, GradleModelCollector.resolveSourceHashKey(root, ext));
        }
        return SourceHiding.NONE;
    }
    /** a configured key, else the stored browser login; never the browser, which an index run has nobody to answer */
    private Optional<CodiqoCredential> resolveCredential(CodiqoExtension ext) throws IOException {
        return CodiqoCredentials.resolve(
                prop("codiqo.apiKey", ext.getApiKey()),
                prop("codiqo.authUrl", ext.getAuthUrl()),
                prop("codiqo.resourceUrl", ext.getResourceUrl()),
                false,
                new GradleLifecycleLog(getLogger()));
    }
    private String resolveBranch(Repository repo) throws IOException {
        String override = prop("codiqo.branch", null);
        if (StringUtils.isNotBlank(override)) {
            return override.trim();
        }
        String current = repo.getBranch();
        if (JGit.isDetachedHead(current)) {
            return JGit.detectDefaultBranch(repo).orElseThrow(() -> new IllegalStateException(
                    "cannot resolve branch: HEAD is detached and no default branch is available; set -Pcodiqo.branch explicitly"));
        }
        return current;
    }
    private File resolveMetadataFile(Project root, CodiqoExtension ext) {
        String override = prop("codiqo.indexMetadataFile", ext.getIndexMetadataFile());
        if (StringUtils.isNotBlank(override)) {
            return new File(override);
        }
        return new File(root.getLayout().getBuildDirectory().getAsFile().get(), "codiqo/index-metadata.properties");
    }
    private File resolveOutputFile(Project root, CodiqoExtension ext) {
        String override = prop("codiqo.indexOutputFile", ext.getIndexOutputFile());
        if (StringUtils.isNotBlank(override)) {
            return new File(override);
        }
        return new File(root.getLayout().getBuildDirectory().getAsFile().get(), INDEX_OUTPUT_RELATIVE_PATH);
    }
    private int missingAnalysesLimit() {
        return Optional.ofNullable(getProject().findProperty("codiqo.missingAnalysesLimit"))
                .map(Object::toString)
                .map(Integer::parseInt)
                .orElse(DEFAULT_MISSING_ANALYSES_LIMIT);
    }
    private String prop(String name, String fallback) {
        return Optional.ofNullable(getProject().findProperty(name)).map(Object::toString).orElse(fallback);
    }
}
