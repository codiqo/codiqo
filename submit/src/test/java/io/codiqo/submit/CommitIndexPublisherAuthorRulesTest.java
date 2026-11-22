package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.submit.CommitIndexPublisher.MissingAnalysesSelection;
import io.codiqo.submit.auth.ApiKeyCredential;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A single-parent commit of an excluded author is indexed, so Codiqo knows it and a finding it fixed can be marked fixed
 * by it, and the index step stores its exclusion at once: it never reaches the per-commit loop, whose setup and build it
 * would start only to be excluded there.
 */
class CommitIndexPublisherAuthorRulesTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String MAIN = "main";
    private static final String PROJECT = "p1";
    private static final String MISSING_PATH = "/api/v1/projects/" + PROJECT + "/commits/missing-analyses";
    private static final String ANALYSES_PATH = "/api/v1/analyses/commit/";
    private static final String DEVOPS = "devops.team@example.com";
    private static final String EXCLUDED_AUTHOR = "author excluded by codiqo.excludeAuthorEmails";
    private static final String HASH_KEY = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    private Git git;
    private RevCommit feature;
    private RevCommit release;
    private RevCommit fix;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch(MAIN).setDirectory(tempDir.toFile()).call();
        feature = commitAs("a.txt", "feature", "alice@example.com");
        release = commitAs("pom.xml", "Release 1.0", DEVOPS);
        fix = commitAs("b.txt", "fix", "alice@example.com");
    }
    @AfterEach
    void closeRepo() {
        git.close();
    }
    @Test
    void theIndexStepExcludesACommitOfAnExcludedAuthorAndListsTheRestForAnalysis() throws Exception {
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));

        MissingAnalysesSelection selection = codiqo.select(excludingDevops(), SourceHiding.NONE);

        assertEquals(List.of(fix.getName(), feature.getName()), selection.getAnalyzableShas());
        assertEquals(Map.of(release.getName(), "FILTERED_BY_RULES:" + EXCLUDED_AUTHOR), codiqo.excluded, "the exclusion the per-commit gate gives the commit");
        assertEquals(2, codiqo.listings, "read again after the exclusion, so the limit is filled with commits to analyse");
    }
    /** a stored exclusion is final, so the include rules' verdict stays with the gate, as the index never keeps such a commit */
    @Test
    void aCommitTheIncludeRulesRejectIsLeftToThePerCommitGate() throws Exception {
        RunArgs args = new RunArgs();
        args.setIncludeAuthorEmails("alice@example.com");
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));

        MissingAnalysesSelection selection = codiqo.select(args, SourceHiding.NONE);

        assertEquals(List.of(fix.getName(), release.getName(), feature.getName()), selection.getAnalyzableShas());
        assertEquals(Map.of(), codiqo.excluded);
        assertEquals(1, codiqo.listings);
    }
    @Test
    void withNoCommitOfAnExcludedAuthorTheListIsReadOnceAndNothingIsExcluded() throws Exception {
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails("someone.else@example.com");
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));

        MissingAnalysesSelection selection = codiqo.select(args, SourceHiding.NONE);

        assertEquals(List.of(fix.getName(), release.getName(), feature.getName()), selection.getAnalyzableShas());
        assertEquals(Map.of(), codiqo.excluded);
        assertEquals(1, codiqo.listings);
    }
    /**
     * the server accepts an exclusion by organization and commit but lists missing analyses by project, so a commit two
     * projects of one organization index stays listed for the second: each commit is tried once, so the reads end
     */
    @Test
    void theReadsEndWhenTheServerKeepsListingACommitItExcluded() throws Exception {
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));
        codiqo.keepsListingExcluded = true;

        MissingAnalysesSelection selection = codiqo.select(excludingDevops(), SourceHiding.NONE);

        assertEquals(List.of(fix.getName(), feature.getName()), selection.getAnalyzableShas(), "the excluded commit is not built");
        assertEquals(1, codiqo.exclusionRequests);
        assertEquals(2, codiqo.listings);
    }
    /** a refused exclusion does not fail the index step: the commit stays listed and the per-commit gate excludes it */
    @Test
    void aCommitWhoseExclusionTheServerRefusesIsLeftToThePerCommitGate() throws Exception {
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));
        codiqo.refusesExclusions = true;

        MissingAnalysesSelection selection = codiqo.select(excludingDevops(), SourceHiding.NONE);

        assertEquals(List.of(fix.getName(), release.getName(), feature.getName()), selection.getAnalyzableShas());
        assertEquals(1, codiqo.exclusionRequests);
        assertEquals(1, codiqo.listings, "nothing was excluded, so the list is not read again");
    }
    /** the index step sends the exclusion through the same source hiding as the per-commit gate */
    @Test
    void aHiddenSourceRunSendsTheExclusionHidden() throws Exception {
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));

        codiqo.select(excludingDevops(), SourceHiding.of(true, Optional.of(HASH_KEY)));

        assertEquals(Map.of(release.getName(), "FILTERED_BY_RULES:" + SourceHiding.reason(EXCLUDED_AUTHOR)), codiqo.excluded);
        assertTrue(codiqo.hidden.contains(release.getName()), "the exclusion is flagged hidden");
    }
    private static RunArgs excludingDevops() {
        RunArgs toReturn = new RunArgs();
        toReturn.setExcludeAuthorEmails(DEVOPS);
        return toReturn;
    }
    private RevCommit commitAs(String path, String message, String email) throws Exception {
        Files.writeString(tempDir.resolve(path), message, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).setAuthor("Author", email).setCommitter("Author", email).call();
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * answers the missing analyses with every commit not excluded yet (or every commit, when it keeps listing excluded
     * ones), and records each exclusion as category:reason
     */
    private final class StubCodiqo {
        private final List<String> missing;
        private final Map<String, String> excluded = Maps.newLinkedHashMap();
        private final Set<String> hidden = Sets.newHashSet();
        private boolean keepsListingExcluded;
        private boolean refusesExclusions;
        private int listings;
        private int exclusionRequests;

        private StubCodiqo(List<String> missing) {
            this.missing = Lists.newArrayList(missing);
        }
        private MissingAnalysesSelection select(RunArgs args, SourceHiding hiding) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress(RunArgs.LOOPBACK_HOST, 0), 0);
            server.createContext(MISSING_PATH, exchange -> {
                listings++;
                ObjectNode answer = JSON.objectNode().put("projectId", PROJECT);
                ArrayNode shas = answer.putArray("commitShas");
                missing.stream().filter(sha -> keepsListingExcluded || BooleanUtils.negate(excluded.containsKey(sha))).forEach(shas::add);
                respond(exchange, HttpResponseStatus.OK.code(), answer.toString());
            });
            server.createContext(ANALYSES_PATH, exchange -> {
                exclusionRequests++;
                if (refusesExclusions) {
                    respond(exchange, HttpResponseStatus.BAD_REQUEST.code(), "{}");
                    return;
                }
                String sha = exchange.getRequestURI().getPath().substring(ANALYSES_PATH.length()).replace("/exclude", "");
                JsonNode body = new ObjectMapper().readTree(IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8));
                excluded.put(sha, body.get("category").asString() + ":" + body.get("reason").asString());
                if (body.path("sourceHidden").asBoolean()) {
                    hidden.add(sha);
                }
                exchange.sendResponseHeaders(HttpResponseStatus.NO_CONTENT.code(), -1);
                exchange.close();
            });
            server.start();
            try {
                String url = RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
                ApiKeyCredential credential = new ApiKeyCredential("k");
                return CommitIndexPublisher.selectMissingAnalyses(
                        new NoopLog(),
                        CommitIndexPublisher.buildClient(url, credential, 5, 5),
                        AnalysisSubmitter.buildClient(url, credential, 5, 5),
                        hiding,
                        url,
                        PROJECT,
                        MAIN,
                        100,
                        git.getRepository(),
                        args,
                        RunArgs.INDEX_BATCH_SIZE);
            } finally {
                server.stop(0);
            }
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
