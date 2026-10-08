package io.codiqo.submit.hotspots;

import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.jgit.revwalk.RevCommit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;

import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.HotspotsApi;
import io.codiqo.client.model.FixCommitsModel;
import io.codiqo.submit.auth.CodiqoApiClients;
import io.codiqo.submit.auth.CodiqoCredential;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Which commits fixed a defect in the code they touched, for the fix count that makes a class a hotspot. A commit the
 * server scored is a fix when its analysis classified it as one; any other commit falls back to its message. The
 * message alone found 39% of the commits the analyses classified as fixes, and 31% of what it matched was not a fix
 * ("fix test", a dependency revert), which moved half of the top ten hotspots on fully scored projects.
 */
public final class FixCommits {
    private static final Set<Integer> MESSAGES_DECIDE = Set.of(HttpResponseStatus.NOT_FOUND.code(), HttpResponseStatus.BAD_REQUEST.code(),
            HttpResponseStatus.METHOD_NOT_ALLOWED.code());
    /** Whole words only, so that "debug", "prefix" and "fixture" are not counted as fix commits. */
    private static final Pattern FIX = Pattern.compile("\\b(?:bug|hot)?fix(?:es|ed|ing)?\\b|\\bbugs?\\b|\\brevert", Pattern.CASE_INSENSITIVE);

    private final Set<String> scored;
    private final Set<String> fixes;

    private FixCommits(Set<String> scored, Set<String> fixes) {
        this.scored = scored;
        this.fixes = fixes;
    }
    public boolean isFix(RevCommit commit) {
        boolean toReturn = FIX.matcher(commit.getShortMessage()).find();
        if (scored.contains(commit.getName())) {
            toReturn = fixes.contains(commit.getName());
        }
        return toReturn;
    }
    public int scoredCount() {
        return scored.size();
    }
    /** every commit judged by its message: no server, or a goal that does not submit */
    public static FixCommits byMessage() {
        return of(Set.of(), Set.of());
    }
    /**
     * The server's classification of the project's commits since {@code since}. A project the server does not know yet
     * (the first analysis of a repository creates it) has scored nothing, so every commit falls back to its message.
     */
    public static FixCommits fetch(String apiUrl, CodiqoCredential credential, long connectTimeoutSeconds, long readTimeoutSeconds, String projectId, Instant since,
            Log log) throws Exception {
        HotspotsApi client = new HotspotsApi(CodiqoApiClients.newApiClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds));
        FixCommits toReturn = byMessage();
        try {
            /**
             * One attempt, not ApiRetry's ten over up to fifteen minutes: this runs while the hotspots are built, with
             * the language servers and PMD's class loaders still open, and an unreachable API held all of that through
             * the whole backoff only to skip the snapshot anyway. A failure costs this commit's snapshot; the previous
             * one stays and the next analysis asks again.
             */
            FixCommitsModel model = client.listFixCommits(projectId, since.toString());
            toReturn = of(Sets.newHashSet(model.getScoredShas()), Sets.newHashSet(model.getFixShas()));
            log.info("fix commits: %d scored since %s, %d of them fixes; the rest are judged by their message", model.getScoredShas().size(), since,
                    model.getFixShas().size());
        } catch (ApiException err) {
            /**
             * 404: the project is not known yet. 400 and 405: a server older than this endpoint, where the path falls on
             * the commit route and its SHA pattern rejects "fix-commits". Either way the messages decide, and the
             * snapshot is still built.
             */
            if (MESSAGES_DECIDE.contains(err.getCode())) {
                log.info("fix commits: the server has none for project %s (HTTP %d), every commit is judged by its message", projectId, err.getCode());
            } else {
                throw err;
            }
        }
        return toReturn;
    }
    @VisibleForTesting
    public static FixCommits of(Set<String> scored, Set<String> fixes) {
        return new FixCommits(scored, fixes);
    }

    /** where the fix commits come from, asked only when a snapshot is actually built */
    @FunctionalInterface
    public interface Source {
        FixCommits load(Instant since) throws Exception;
    }
}
