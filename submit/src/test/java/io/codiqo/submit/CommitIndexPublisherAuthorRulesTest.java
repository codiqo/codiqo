package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
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
 * A commit the author rules reject is indexed, so Codiqo knows it and a finding it fixed can be marked fixed by it, and the
 * index step records it as excluded at once: it never reaches the per-commit loop, whose setup and build it would start
 * only to be excluded there.
 */
class CommitIndexPublisherAuthorRulesTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String MAIN = "main";
    private static final String PROJECT = "p1";
    private static final String MISSING_PATH = "/api/v1/projects/" + PROJECT + "/commits/missing-analyses";
    private static final String ANALYSES_PATH = "/api/v1/analyses/commit/";
    private static final String DEVOPS = "devops.team@example.com";

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
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails(DEVOPS);
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));

        MissingAnalysesSelection selection = codiqo.select(args);

        assertEquals(List.of(fix.getName(), feature.getName()), selection.getAnalyzableShas());
        assertEquals(Map.of(release.getName(), "FILTERED_BY_RULES:author excluded by codiqo.excludeAuthorEmails"), codiqo.excluded,
                "the exclusion the per-commit gate gives the commit");
        assertEquals(2, codiqo.listings, "read again after the exclusion, so the limit is filled with commits to analyse");
    }
    @Test
    void theIndexStepExcludesACommitTheIncludeRulesRejectWithTheGatesReason() throws Exception {
        RunArgs args = new RunArgs();
        args.setIncludeAuthorEmails("alice@example.com");
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));

        MissingAnalysesSelection selection = codiqo.select(args);

        assertEquals(List.of(fix.getName(), feature.getName()), selection.getAnalyzableShas());
        assertEquals(Set.of(release.getName()), codiqo.excluded.keySet());
        assertEquals("FILTERED_BY_RULES:filtered by include-rules — branch match: true (branches=[main]), author match: false (author=" + DEVOPS + ")",
                codiqo.excluded.get(release.getName()));
    }
    @Test
    void withNoCommitOfARejectedAuthorTheListIsReadOnceAndNothingIsExcluded() throws Exception {
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails("someone.else@example.com");
        StubCodiqo codiqo = new StubCodiqo(List.of(fix.getName(), release.getName(), feature.getName()));

        MissingAnalysesSelection selection = codiqo.select(args);

        assertEquals(List.of(fix.getName(), release.getName(), feature.getName()), selection.getAnalyzableShas());
        assertEquals(Map.of(), codiqo.excluded);
        assertEquals(1, codiqo.listings);
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

    /** answers the missing analyses with every commit not excluded yet, and records each exclusion as category:reason */
    private final class StubCodiqo {
        private final List<String> missing;
        private final Map<String, String> excluded = Maps.newLinkedHashMap();
        private int listings;

        private StubCodiqo(List<String> missing) {
            this.missing = Lists.newArrayList(missing);
        }
        private MissingAnalysesSelection select(RunArgs args) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress(RunArgs.LOOPBACK_HOST, 0), 0);
            server.createContext(MISSING_PATH, exchange -> {
                listings++;
                ObjectNode answer = JSON.objectNode().put("projectId", PROJECT);
                ArrayNode shas = answer.putArray("commitShas");
                missing.stream().filter(Predicate.not(excluded::containsKey)).forEach(shas::add);
                respond(exchange, HttpResponseStatus.OK.code(), answer.toString());
            });
            server.createContext(ANALYSES_PATH, exchange -> {
                String sha = exchange.getRequestURI().getPath().substring(ANALYSES_PATH.length()).replace("/exclude", "");
                JsonNode body = new ObjectMapper().readTree(IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8));
                excluded.put(sha, body.get("category").asString() + ":" + body.get("reason").asString());
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
