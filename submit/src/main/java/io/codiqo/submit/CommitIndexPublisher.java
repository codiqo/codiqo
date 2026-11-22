package io.codiqo.submit;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.AnalysisApi;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.CommitIndexBatchModel;
import io.codiqo.client.model.CommitIndexBatchResultModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.MissingAnalysesModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.submit.CommitExclusions.Exclusion;
import io.codiqo.submit.CommitExclusions.MergeExclusion;
import io.codiqo.submit.auth.CodiqoApiClients;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.util.JGit;
import io.codiqo.util.RepositoryUrls;
import lombok.experimental.UtilityClass;

/**
 * Backend half of commit indexing: publish the walked history and ask which commits still lack an analysis. Split out
 * of the Maven mojo so the Gradle plugin drives the same protocol — {@link CommitIndexer} already owns the git walk,
 * and this owns everything past it.
 */
@UtilityClass
public class CommitIndexPublisher {
    private static final int UNKNOWN_PARENTS_SAMPLE_LIMIT = 10;

    public static CommitIndexApi buildClient(String apiUrl, CodiqoCredential credential, long connectTimeoutSeconds, long readTimeoutSeconds) {
        return new CommitIndexApi(CodiqoApiClients.newApiClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds));
    }
    public static void indexBatches(
            Log log,
            CommitIndexApi client,
            String apiUrl,
            String projectId,
            ProjectModel projectMetadata,
            List<CommitModel> commits,
            int batchSize) throws ApiException {
        int totalAccepted = 0;
        Set<String> unknownParents = Sets.newLinkedHashSet();

        for (List<CommitModel> chunk : ListUtils.partition(commits, batchSize)) {
            CommitIndexBatchModel batch = new CommitIndexBatchModel().commits(chunk).project(projectMetadata);
            CommitIndexBatchResultModel result = ApiRetry.call(log, "indexCommits", apiUrl, () -> client.indexCommits(projectId, batch));
            totalAccepted += Optional.ofNullable(result.getAccepted()).orElse(0);
            if (CollectionUtils.isNotEmpty(result.getUnknownParents())) {
                unknownParents.addAll(result.getUnknownParents());
            }
        }
        log.info("indexed " + totalAccepted + "/" + commits.size() + " commits");

        if (CollectionUtils.isNotEmpty(unknownParents)) {
            String sample = Joiner.on(", ").join(unknownParents.stream().limit(UNKNOWN_PARENTS_SAMPLE_LIMIT).toList());
            String suffix = unknownParents.size() > UNKNOWN_PARENTS_SAMPLE_LIMIT ? " ..." : StringUtils.EMPTY;
            log.warn("server reported " + unknownParents.size() + " unknown parent SHAs (re-run with a wider commit window): " + sample + suffix);
        }
    }
    public static List<String> listMissingAnalyses(
            Log log,
            CommitIndexApi client,
            String apiUrl,
            String projectId,
            String branch,
            int limit) throws ApiException {
        MissingAnalysesModel response = ApiRetry.call(log, "listMissingAnalyses", apiUrl,
                () -> client.listMissingAnalyses(projectId, branch, limit));
        if (Objects.nonNull(response.getHalt())) {
            throw new ProjectHaltedException(projectId, response.getHalt());
        }
        return Optional.ofNullable(response.getCommitShas()).orElse(Collections.emptyList());
    }
    /**
     * The missing analyses this clone can run, once the commits of missing merges' lines that no index run reached are
     * indexed ({@link #indexUnseenLines}). The list is read again when any were, so they are analysed in this run. A
     * listed commit of an excluded author is excluded here instead ({@link ExcludedAuthorCommits}).
     */
    public static MissingAnalysesSelection selectMissingAnalyses(
            Log log,
            CommitIndexApi client,
            AnalysisApi analyses,
            SourceHiding hiding,
            String apiUrl,
            String projectId,
            String branch,
            int limit,
            Repository repo,
            RunArgs filterArgs,
            int batchSize) throws Exception {
        ExcludedAuthorCommits excludedAuthors = new ExcludedAuthorCommits(log, analyses, hiding, apiUrl, repo, filterArgs);
        MissingAnalysesSelection toReturn = selectAnalyzable(repo, listMissingToAnalyse(log, client, apiUrl, projectId, branch, limit, excludedAuthors));
        if (indexUnseenLines(log, client, apiUrl, projectId, repo, filterArgs, toReturn.getAnalyzableShas(), batchSize) > 0) {
            toReturn = selectAnalyzable(repo, listMissingToAnalyse(log, client, apiUrl, projectId, branch, limit, excludedAuthors));
        }
        return toReturn;
    }
    /**
     * Indexes the commits of each merge's merged-in line that no index run reached ({@link CommitExclusions#mergeExclusion})
     * under the walk's rules, and says how many it indexed. They go oldest first: the gate stops at a line's newest
     * indexed commit, so had a later batch failed after the newest ones were indexed, the older ones would never be
     * looked at again.
     */
    public static int indexUnseenLines(
            Log log,
            CommitIndexApi client,
            String apiUrl,
            String projectId,
            Repository repo,
            RunArgs filterArgs,
            List<String> shas,
            int batchSize) throws Exception {
        IndexedCommits indexed = IndexedCommits.of(log, client, apiUrl, projectId);
        List<RevCommit> unseen = Lists.newArrayList();
        for (String sha : shas) {
            if (JGit.isMerge(repo, sha)) {
                Optional<MergeExclusion> exclusion = CommitExclusions.mergeExclusion(repo, sha, filterArgs.isFirstParentOnly(), indexed);
                if (exclusion.isPresent()) {
                    unseen.addAll(Lists.reverse(exclusion.get().getUnseen()));
                }
            }
        }

        int toReturn = 0;
        if (CollectionUtils.isNotEmpty(unseen)) {
            List<CommitModel> commits = CommitIndexer.toCommitModels(repo, filterArgs, unseen);
            log.info(String.format(Locale.ROOT, "%d commits of lines merged onto the branch were never indexed; indexing the %d the index rules admit",
                    unseen.size(), commits.size()));
            indexBatches(log, client, apiUrl, projectId, null, commits, batchSize);
            toReturn = commits.size();
        }
        return toReturn;
    }
    /**
     * Repository URLs taken from the git remotes. A build tool's own SCM metadata is a separate, tool-specific
     * fallback the caller layers on when this comes back empty.
     */
    public static List<URI> repositoryUrls(Log log, Repository repo) {
        List<URI> toReturn = Lists.newArrayList();
        JGit.detectRemoteUrls(repo).forEach(rawUrl -> addRepositoryUri(log, toReturn, rawUrl, "git remote"));
        return toReturn;
    }
    public static void addRepositoryUri(Log log, List<URI> repoUrls, String rawUrl, String source) {
        try {
            URI repositoryUri = RepositoryUrls.toUri(rawUrl);
            if (BooleanUtils.negate(repoUrls.contains(repositoryUri))) {
                repoUrls.add(repositoryUri);
            }
        } catch (URISyntaxException err) {
            log.warn("failed to parse repository URL from " + source + ": " + rawUrl + " (" + err.getMessage() + ")");
        }
    }
    /**
     * Drops the SHAs this clone cannot analyze. The server answers from the full indexed history, but a shallow or
     * partial clone may not hold the commit itself, and a commit whose first parent is absent has no diff to compute.
     */
    public static MissingAnalysesSelection selectAnalyzable(Repository repo, List<String> shas) throws IOException {
        List<String> analyzable = Lists.newArrayListWithCapacity(shas.size());
        int skippedMissingCommit = 0;
        int skippedMissingParent = 0;

        try (RevWalk walk = new RevWalk(repo)) {
            ObjectReader reader = walk.getObjectReader();
            for (String sha : shas) {
                ObjectId commitId = repo.resolve(sha);
                if (Objects.isNull(commitId) || BooleanUtils.negate(reader.has(commitId))) {
                    skippedMissingCommit++;
                    continue;
                }
                RevCommit commit = walk.parseCommit(commitId);
                if (commit.getParentCount() > 0 && BooleanUtils.negate(reader.has(commit.getParent(0)))) {
                    skippedMissingParent++;
                    continue;
                }
                analyzable.add(sha);
            }
        }

        return new MissingAnalysesSelection(analyzable, skippedMissingCommit, skippedMissingParent);
    }
    /**
     * The missing analyses less the commits of excluded authors, whose exclusion is stored here: listed for the
     * per-commit loop, such a commit would start the loop's setup and a run of its own, only for
     * {@link CommitExclusions#beforeAnalysis} to exclude it there. The list is read again while a pass stores a new
     * exclusion, so the limit is filled with commits to analyse. Each commit is tried once, so the reads end even when
     * the server keeps listing a commit whose exclusion it accepted.
     */
    private static List<String> listMissingToAnalyse(
            Log log,
            CommitIndexApi client,
            String apiUrl,
            String projectId,
            String branch,
            int limit,
            ExcludedAuthorCommits excludedAuthors) throws Exception {
        List<String> listed = listMissingAnalyses(log, client, apiUrl, projectId, branch, limit);
        while (excludedAuthors.exclude(listed) > 0) {
            listed = listMissingAnalyses(log, client, apiUrl, projectId, branch, limit);
        }
        return excludedAuthors.withoutExcluded(listed);
    }
    public static final class MissingAnalysesSelection extends ImmutableTriple<List<String>, Integer, Integer> {
        public MissingAnalysesSelection(List<String> analyzableShas, int skippedMissingCommitCount, int skippedMissingParentCount) {
            super(analyzableShas, skippedMissingCommitCount, skippedMissingParentCount);
        }
        public List<String> getAnalyzableShas() {
            return getLeft();
        }
        public int getSkippedMissingCommitCount() {
            return getMiddle();
        }
        public int getSkippedMissingParentCount() {
            return getRight();
        }
    }

    /**
     * Stores the exclusion of each listed single-parent commit whose credited author {@code codiqo.excludeAuthorEmails}
     * names ({@link CommitExclusions#excludedAuthor}), sent through {@link AnalysisSubmitter#exclude} with the run's
     * {@link SourceHiding}, as the per-commit gate sends it. A merge is left to that gate, which asks the merge rule
     * first. Each commit is tried once per index run; one whose exclusion the server refuses stays listed, and the gate
     * excludes it as before.
     */
    private static final class ExcludedAuthorCommits {
        private final Log log;
        private final AnalysisApi analyses;
        private final SourceHiding hiding;
        private final String apiUrl;
        private final Repository repo;
        private final RunArgs filterArgs;
        private final Set<String> tried = Sets.newHashSet();
        private final Set<String> excluded = Sets.newHashSet();

        private ExcludedAuthorCommits(Log log, AnalysisApi analyses, SourceHiding hiding, String apiUrl, Repository repo, RunArgs filterArgs) {
            this.log = log;
            this.analyses = analyses;
            this.hiding = hiding;
            this.apiUrl = apiUrl;
            this.repo = repo;
            this.filterArgs = filterArgs;
        }
        /** stores the exclusion of each such commit not tried yet, and says how many it stored */
        private int exclude(List<String> shas) throws IOException {
            int toReturn = 0;
            try (RevWalk walk = new RevWalk(repo)) {
                ObjectReader reader = walk.getObjectReader();
                for (String sha : shas) {
                    if (BooleanUtils.negate(tried.add(sha))) {
                        continue;
                    }
                    ObjectId commitId = repo.resolve(sha);
                    if (Objects.isNull(commitId) || BooleanUtils.negate(reader.has(commitId))) {
                        continue;
                    }
                    RevCommit commit = walk.parseCommit(commitId);
                    if (JGit.isMerge(commit) || BooleanUtils.negate(filterArgs.isExcludedAuthor(JGit.creditedAuthor(repo, commit).getEmailAddress()))) {
                        continue;
                    }
                    if (store(sha)) {
                        excluded.add(sha);
                        toReturn++;
                    }
                }
            }
            return toReturn;
        }
        private boolean store(String sha) {
            Exclusion exclusion = CommitExclusions.excludedAuthor();
            AnalysisExcludeModel body = new AnalysisExcludeModel().reason(exclusion.getReason()).category(exclusion.getCategory()).files(List.of());
            try {
                AnalysisSubmitter.exclude(analyses, apiUrl, sha, body, hiding, log);
                return true;
            } catch (ApiException err) {
                log.warn("could not exclude commit " + sha + " at index time, so the per-commit gate excludes it instead: " + ApiRetry.summarize(err));
                return false;
            }
        }
        private List<String> withoutExcluded(List<String> shas) {
            return shas.stream().filter(sha -> BooleanUtils.negate(excluded.contains(sha))).toList();
        }
    }
}
