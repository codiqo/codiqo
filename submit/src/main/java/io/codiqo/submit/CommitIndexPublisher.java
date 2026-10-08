package io.codiqo.submit;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
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

import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.client.model.CommitIndexBatchModel;
import io.codiqo.client.model.CommitIndexBatchResultModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.MissingAnalysesModel;
import io.codiqo.client.model.ProjectModel;
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
        return Optional.ofNullable(response.getCommitShas()).orElse(Collections.emptyList());
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
}
