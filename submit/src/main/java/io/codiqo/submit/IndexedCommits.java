package io.codiqo.submit;

import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.CommitIndexApi;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * The commits a project's index holds, as the server knows them. An index run walks only the analysed branch's
 * first-parent history, so an indexed commit is one codiqo analyses on its own: the merge gate reads this to tell a pull
 * request from a merge of a line it already follows commit by commit ({@link CommitExclusions#mergeExclusion}).
 */
@FunctionalInterface
public interface IndexedCommits {
    /** a run that speaks to no server knows no indexed commit, so every merge is taken for a pull request */
    IndexedCommits NONE = sha -> false;

    boolean contains(String sha) throws Exception;

    /**
     * The server answers 404 for a commit it has not indexed, and also for a project it does not know, which has no
     * indexed commit either. A base URL that answers 404 for everything is not mistaken for an empty index for long: the
     * run's next call, the halt check before anything is built, fails on it.
     */
    static IndexedCommits of(Log log, CommitIndexApi client, String apiUrl, String projectId) {
        return sha -> {
            try {
                ApiRetry.call(log, "getCommitStatus", apiUrl, () -> client.getCommitStatus(projectId, sha));
                return true;
            } catch (ApiException err) {
                if (err.getCode() == HttpResponseStatus.NOT_FOUND.code()) {
                    return false;
                }
                throw err;
            }
        };
    }
}
