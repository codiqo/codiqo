package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.CommitModel;
import io.codiqo.submit.CommitExclusions.MergeExclusion;
import io.codiqo.submit.auth.ApiKeyCredential;
import io.codiqo.util.JGit;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The release flow that put already scored work on the analysed branch a second time: {@code dev} is analysed, every
 * release merges it into {@code main} with a merge commit, and a release job then fast-forwards {@code dev} onto that
 * merge. From then on {@code dev}'s first parents run through {@code main}'s merges, each of which carries, against the
 * previous release, every commit {@code dev} had since, all of them indexed and analysed one by one already.
 */
class CommitExclusionsLineMergeTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Date EPOCH = new Date(0);
    private static final String DEV = "dev";
    private static final String MAIN = "main";
    private static final String PROJECT = "p1";
    private static final String INDEX_PATH = "/api/v1/projects/" + PROJECT + "/commits";

    @TempDir
    Path tempDir;

    private Git git;
    private Repository repo;
    private RevCommit init;
    private RevCommit firstDevCommit;
    private RevCommit devPullRequest;
    private RevCommit lastDevCommitOfRelease1;
    private RevCommit release1;
    private RevCommit firstDevCommitOfRelease2;
    private RevCommit lastDevCommitOfRelease2;
    private RevCommit release2;
    private RevCommit seenDevCommitOfRelease3;
    private RevCommit firstUnseenDevCommitOfRelease3;
    private RevCommit lastUnseenDevCommitOfRelease3;
    private RevCommit release3;
    private RevCommit pullRequestAfterTheMoves;
    private RevCommit pullRequestBranchCommit;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch(MAIN).setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        init = commit("init.txt", "main branch builds");

        git.branchCreate().setName(DEV).call();
        git.checkout().setName(DEV).call();
        firstDevCommit = commit("a.txt", "first dev commit");
        devPullRequest = pullRequestInto(DEV, "feature-1", "f1.txt");
        lastDevCommitOfRelease1 = commit("b.txt", "last dev commit of release 1");
        release1 = release();

        firstDevCommitOfRelease2 = commit("c.txt", "first dev commit of release 2");
        lastDevCommitOfRelease2 = commit("d.txt", "last dev commit of release 2");
        release2 = release();

        seenDevCommitOfRelease3 = commit("e.txt", "a dev commit an index run saw");
        firstUnseenDevCommitOfRelease3 = commit("f.txt", "a dev commit released before any index run saw it");
        lastUnseenDevCommitOfRelease3 = commit("g.txt", "another dev commit released before any index run saw it");
        release3 = release();

        pullRequestAfterTheMoves = pullRequestInto(DEV, "feature-2", "f2.txt");
        pullRequestBranchCommit = parse(pullRequestAfterTheMoves.getParent(1));
    }
    @AfterEach
    void closeRepo() {
        git.close();
    }
    /** what put the merges up for analysis: after the moves, the first-parent walk of {@code dev} is {@code main}'s */
    @Test
    void theReleaseMergesAreOnTheFirstParentHistoryOfTheMovedBranch() throws Exception {
        RunArgs args = new RunArgs();
        args.setFirstParentOnly(true);

        List<String> spine = CommitIndexer.extractCommits(repo, args, DEV, EPOCH, DEV).stream().map(CommitModel::getSha).toList();

        assertEquals(List.of(pullRequestAfterTheMoves.getName(), release3.getName(), release2.getName(), release1.getName(), init.getName()), spine);
    }
    @Test
    void theFirstReleaseMergeIsExcludedAfterOneLookup() throws Exception {
        RecordingIndex index = indexedBeforeTheMoves();

        MergeExclusion excluded = CommitExclusions.mergeExclusion(repo, release1.getName(), true, index).orElseThrow();

        assertTrue(Strings.CS.contains(excluded.getReason(), JGit.shortSha(lastDevCommitOfRelease1.getName())), excluded.getReason());
        assertEquals(List.of(lastDevCommitOfRelease1.getName()), index.asked, "the merged line's newest commit is indexed, so one lookup decides");
        assertTrue(excluded.getUnseen().isEmpty(), "every commit of the line was indexed already");
    }
    @Test
    void aReleaseMergeIsExcludedAsAMergeCommit() throws Exception {
        assertEquals(AnalysisExcludeCategory.MERGE_COMMIT,
                CommitExclusions.beforeAnalysis(runArgs(release2), indexedBeforeTheMoves()).orElseThrow().getCategory());
    }
    /**
     * The last commits before a release were never indexed: the branch moved onto the release merge before a run walked
     * them, and no later run reaches them. They travel with the exclusion, newest first, for the index step to index.
     */
    @Test
    void theCommitsNoIndexRunSawTravelWithTheExclusion() throws Exception {
        MergeExclusion excluded = CommitExclusions.mergeExclusion(repo, release3.getName(), true, indexedBeforeTheMoves()).orElseThrow();

        assertEquals(List.of(lastUnseenDevCommitOfRelease3, firstUnseenDevCommitOfRelease3), excluded.getUnseen());
        assertTrue(Strings.CS.contains(excluded.getReason(), JGit.shortSha(seenDevCommitOfRelease3.getName())), excluded.getReason());
    }
    /** the gate only reads; the index step indexes the unseen commits, oldest first, and lists them for analysis */
    @Test
    void theIndexStepIndexesTheUnseenCommitsOldestFirst() throws Exception {
        Set<String> indexed = indexedBeforeTheMoves().indexed;
        List<String> posted = Lists.newArrayList();
        HttpServer server = HttpServer.create(new InetSocketAddress(RunArgs.LOOPBACK_HOST, 0), 0);
        server.createContext(INDEX_PATH, exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (INDEX_PATH.equals(path)) {
                JsonNode batch = new ObjectMapper().readTree(IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8));
                batch.get("commits").forEach(commit -> posted.add(commit.get("sha").asString()));
                respond(exchange, HttpResponseStatus.OK.code(), JSON.objectNode().put("accepted", batch.get("commits").size()).toString());
            } else if (indexed.contains(path.substring(INDEX_PATH.length() + 1))) {
                ObjectNode answer = JSON.objectNode().put("analysisState", "completed");
                answer.putObject("commit").put("sha", path.substring(INDEX_PATH.length() + 1));
                respond(exchange, HttpResponseStatus.OK.code(), answer.toString());
            } else {
                respond(exchange, HttpResponseStatus.NOT_FOUND.code(), JSON.objectNode().put("message", "not indexed").toString());
            }
        });
        server.start();
        try {
            String url = RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
            int added = CommitIndexPublisher.indexUnseenLines(
                    new NoopLog(),
                    CommitIndexPublisher.buildClient(url, new ApiKeyCredential("k"), 5, 5),
                    url,
                    PROJECT,
                    repo,
                    new RunArgs(),
                    List.of(release1.getName(), release3.getName(), pullRequestAfterTheMoves.getName(), seenDevCommitOfRelease3.getName()),
                    RunArgs.INDEX_BATCH_SIZE);

            assertEquals(2, added);
            assertEquals(List.of(firstUnseenDevCommitOfRelease3.getName(), lastUnseenDevCommitOfRelease3.getName()), posted,
                    "oldest first: a failed later batch leaves the newest unindexed, which the gate reaches first and returns again");
        } finally {
            server.stop(0);
        }
    }
    @Test
    void aPullRequestIntoTheMovedBranchIsStillAnalysed() throws Exception {
        RecordingIndex index = indexedBeforeTheMoves();

        assertTrue(CommitExclusions.mergeSkipReason(runArgs(pullRequestAfterTheMoves), index).isEmpty());
        assertEquals(List.of(pullRequestBranchCommit.getName()), index.asked);
    }
    /** a pull request's line has no indexed commit, so without a bound it is looked up commit by commit to its end */
    @Test
    void aLongPullRequestLineIsLookedUpOnlyToTheLimit() throws Exception {
        git.branchCreate().setName("feature-3").call();
        git.checkout().setName("feature-3").call();
        for (int i = 0; i <= RunArgs.MERGE_LINE_LOOKUP_LIMIT; i++) {
            commit("long-" + i + ".txt", "pull request commit " + i);
        }
        git.checkout().setName(DEV).call();
        RevCommit longPullRequest = merge("feature-3", "Merge pull request from feature-3");
        RecordingIndex index = indexedBeforeTheMoves();

        assertTrue(CommitExclusions.mergeSkipReason(runArgs(longPullRequest), index).isEmpty());
        assertEquals(RunArgs.MERGE_LINE_LOOKUP_LIMIT, index.asked.size());
    }
    /**
     * The same move without a release: a teammate's push was indexed while it was the branch's tip, then a developer
     * pulled it into a local commit and pushed the merge, which leaves the pushed commit off the branch's first parents.
     * The merge's delta is exactly that commit, analysed on its own already.
     */
    @Test
    void aPullMergeOfAnIndexedPushIsExcluded() throws Exception {
        git.branchCreate().setName("origin-dev").call();
        git.checkout().setName("origin-dev").call();
        RevCommit pushed = commit("pushed.txt", "a teammate's push, indexed while it was the tip");
        git.checkout().setName(DEV).call();
        commit("local.txt", "a local commit made before pulling");
        RevCommit pull = merge("origin-dev", "Merge branch 'dev' of github.com:example/repo into dev");

        Set<String> indexed = Sets.newHashSet(indexedBeforeTheMoves().indexed);
        indexed.add(pushed.getName());

        assertEquals(AnalysisExcludeCategory.MERGE_COMMIT,
                CommitExclusions.beforeAnalysis(runArgs(pull), new RecordingIndex(indexed)).orElseThrow().getCategory());
    }
    /** without a server nothing is known to be indexed, and a merge is analysed as before */
    @Test
    void anAnalysisWithoutAServerAnalysesTheReleaseMerge() throws Exception {
        assertTrue(CommitExclusions.mergeSkipReason(runArgs(release1), IndexedCommits.NONE).isEmpty());
    }
    @Test
    void theUnseenCommitsAreIndexedUnderTheIndexRules() throws Exception {
        Files.writeString(tempDir.resolve("version.txt"), "1.1", StandardCharsets.UTF_8);
        git.add().addFilepattern("version.txt").call();
        RevCommit bot = git.commit().setMessage("release 1.1").setAuthor("Release Bot", "release-bot@corp.example").call();
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails("release-bot@corp.example");

        List<CommitModel> models = CommitIndexer.toCommitModels(repo, args, List.of(bot, firstUnseenDevCommitOfRelease3));

        assertEquals(List.of(firstUnseenDevCommitOfRelease3.getName()), models.stream().map(CommitModel::getSha).toList());
        assertTrue(models.iterator().next().getBranches().contains(DEV));
    }
    /** what index runs had walked on {@code dev} while it still had its own first parents, and after each move */
    private RecordingIndex indexedBeforeTheMoves() {
        return new RecordingIndex(Set.of(
                init.getName(), firstDevCommit.getName(), devPullRequest.getName(), lastDevCommitOfRelease1.getName(), release1.getName(),
                firstDevCommitOfRelease2.getName(), lastDevCommitOfRelease2.getName(), release2.getName(),
                seenDevCommitOfRelease3.getName(), release3.getName()));
    }
    private RunArgs runArgs(RevCommit commit) {
        RunArgs toReturn = new RunArgs();
        toReturn.setGit(repo);
        toReturn.setCommitId(commit.getName());
        toReturn.setFirstParentOnly(true);
        return toReturn;
    }
    /** merges {@code dev} into {@code main} as a pull request does, then fast-forwards {@code dev} onto that merge */
    private RevCommit release() throws Exception {
        git.checkout().setName(MAIN).call();
        RevCommit toReturn = merge(DEV, "Merge pull request from dev");

        git.branchCreate().setName(DEV).setForce(true).setStartPoint(toReturn).call();
        git.checkout().setName(DEV).call();
        return toReturn;
    }
    private RevCommit pullRequestInto(String target, String branch, String file) throws Exception {
        git.branchCreate().setName(branch).call();
        git.checkout().setName(branch).call();
        commit(file, "pull request work");

        git.checkout().setName(target).call();
        return merge(branch, "Merge pull request from " + branch);
    }
    /** GitHub's "Create a merge commit" always merges with --no-ff */
    private RevCommit merge(String branch, String message) throws Exception {
        ObjectId head = git.merge().include(repo.resolve(branch))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true).setMessage(message).call().getNewHead();
        return parse(head);
    }
    private RevCommit commit(String path, String message) throws Exception {
        Files.writeString(tempDir.resolve(path), message, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).setAuthor("Dev", "dev@corp.example").call();
    }
    private RevCommit parse(ObjectId id) throws Exception {
        try (RevWalk walk = new RevWalk(repo)) {
            return walk.parseCommit(id);
        }
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    private static final class RecordingIndex implements IndexedCommits {
        private final Set<String> indexed;
        private final List<String> asked = Lists.newArrayList();

        private RecordingIndex(Set<String> indexed) {
            this.indexed = Sets.newHashSet(indexed);
        }
        @Override
        public boolean contains(String sha) {
            asked.add(sha);
            return indexed.contains(sha);
        }
    }
    private static final class NoopLog implements Log {
        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public void log(Level level, String message, Object... formatArgs) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
