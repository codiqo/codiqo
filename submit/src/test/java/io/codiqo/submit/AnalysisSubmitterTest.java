package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.BugsModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.submit.auth.ApiKeyCredential;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;

class AnalysisSubmitterTest {
    private static final String API_KEY = "test-api-key";
    private static final Log LOG = new NoopLog();
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private HttpServer server;
    private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }
    @AfterEach
    void stopServer() {
        server.stop(0);
    }
    /** a server without staged uploads answers the open call 404, and the submission goes in one request as before */
    @Test
    void submitFallsBackToOneRequestWhenTheServerHasNoStagedUploads() throws Exception {
        UUID analysisId = UUID.randomUUID();
        server.createContext("/api/v1/analyses", exchange -> {
            RecordedRequest request = RecordedRequest.from(exchange);
            recorded.add(request);
            callCount.incrementAndGet();
            if (Strings.CS.startsWith(request.path, "/api/v1/analyses/uploads")) {
                respond(exchange, 404, JSON.objectNode().put("message", "not found").toString());
            } else {
                respond(exchange, 201, jsonAcceptedBody(analysisId, "accepted"));
            }
        });

        AnalysisAcceptedModel response = AnalysisSubmitter.submit(
                serverUrl(), new ApiKeyCredential(API_KEY), 5, 5, sampleSubmission(), LOG);

        assertEquals(List.of("POST /api/v1/analyses/uploads", "POST /api/v1/analyses"), recorded.stream().map(r -> r.method + " " + r.path).toList());
        RecordedRequest req = recorded.get(1);
        assertEquals(API_KEY, req.apiKeyHeader);
        JsonNode body = MAPPER.readTree(req.body);
        assertEquals(SHA, body.path("commit").path("sha").asString(), "the body carries the commit");
        assertEquals("codiqo-test", body.path("project").path("code").asString(), "the body carries the project");

        assertNotNull(response);
        assertEquals(analysisId, response.getAnalysisId());
        assertEquals(AnalysisAcceptedModel.StatusEnum.ACCEPTED, response.getStatus());
    }
    /** an older server matches the open call to its GET-only analysis route and answers 405 */
    @Test
    void submitFallsBackToOneRequestWhenTheServerAnswersMethodNotAllowed() throws Exception {
        UUID analysisId = UUID.randomUUID();
        server.createContext("/api/v1/analyses", exchange -> {
            RecordedRequest request = RecordedRequest.from(exchange);
            recorded.add(request);
            if (Strings.CS.startsWith(request.path, "/api/v1/analyses/uploads")) {
                respond(exchange, 405, JSON.objectNode().put("message", "method not allowed").toString());
            } else {
                respond(exchange, 201, jsonAcceptedBody(analysisId, "accepted"));
            }
        });

        AnalysisAcceptedModel response = AnalysisSubmitter.submit(serverUrl(), new ApiKeyCredential(API_KEY), 5, 5, sampleSubmission(), LOG);

        assertEquals(List.of("POST /api/v1/analyses/uploads", "POST /api/v1/analyses"), recorded.stream().map(r -> r.method + " " + r.path).toList());
        assertEquals(analysisId, response.getAnalysisId());
    }
    /**
     * The first finalize is applied but its answer lost (the server stores the analysis and deletes the upload, then the
     * connection fails): the retry finds no upload, and the submission is staged again to read the accepted analysis.
     */
    @Test
    void aFinalizeWhoseAnswerWasLostIsReadBackByStagingAgain() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger finalizes = new AtomicInteger();
        server.createContext("/api/v1/analyses", exchange -> {
            RecordedRequest request = RecordedRequest.from(exchange);
            recorded.add(request);
            if (Strings.CS.endsWith(request.path, "/finalize")) {
                int attempt = finalizes.incrementAndGet();
                if (attempt == 1) {
                    respond(exchange, 503, JSON.objectNode().put("message", "the answer was lost").toString());
                } else if (attempt == 2) {
                    respond(exchange, 404, JSON.objectNode().put("message", "no such upload").toString());
                } else {
                    respond(exchange, 201, jsonAcceptedBody(analysisId, "accepted"));
                }
            } else if ("POST".equals(request.method)) {
                UUID id = opens.incrementAndGet() == 1 ? first : second;
                respond(exchange, 201, JSON.objectNode().put("uploadId", id.toString()).put("expiresAt", "2026-01-02T00:00:00Z").toString());
            } else {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
        });

        AnalysisAcceptedModel response = AnalysisSubmitter.submit(serverUrl(), new ApiKeyCredential(API_KEY), 5, 5, sampleSubmission(), LOG);

        assertEquals(analysisId, response.getAnalysisId());
        assertEquals(2, opens.get(), "staged again after the retry found the upload gone");
        assertTrue(recorded.stream().anyMatch(r -> r.path.equals("/api/v1/analyses/uploads/" + second + "/finalize")));
    }
    /** a 404 on the first finalize is the server's answer, not a lost one: it fails the submission */
    @Test
    void aFinalizeRejectedOnItsFirstAttemptFails() throws Exception {
        server.createContext("/api/v1/analyses", exchange -> {
            RecordedRequest request = RecordedRequest.from(exchange);
            recorded.add(request);
            if (Strings.CS.endsWith(request.path, "/finalize")) {
                respond(exchange, 404, JSON.objectNode().put("message", "expired").toString());
            } else if ("POST".equals(request.method)) {
                respond(exchange, 201, JSON.objectNode().put("uploadId", UUID.randomUUID().toString()).put("expiresAt", "2026-01-02T00:00:00Z").toString());
            } else {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
        });

        ApiException err = assertThrows(ApiException.class, () -> AnalysisSubmitter.submit(serverUrl(), new ApiKeyCredential(API_KEY), 5, 5, sampleSubmission(), LOG));

        assertEquals(HttpResponseStatus.NOT_FOUND.code(), err.getCode());
        assertEquals(1, recorded.stream().filter(r -> "POST".equals(r.method) && r.path.equals("/api/v1/analyses/uploads")).count(), "not staged again");
    }
    @Test
    void submitStagesTheUploadInBatchesAndFinalizes() throws Exception {
        UUID uploadId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        server.createContext("/api/v1/analyses", exchange -> {
            RecordedRequest request = RecordedRequest.from(exchange);
            recorded.add(request);
            callCount.incrementAndGet();
            if (Strings.CS.endsWith(request.path, "/finalize")) {
                respond(exchange, 201, jsonAcceptedBody(analysisId, "accepted"));
            } else if ("POST".equals(request.method)) {
                respond(exchange, 201, JSON.objectNode().put("uploadId", uploadId.toString()).put("expiresAt", "2026-01-02T00:00:00Z").toString());
            } else {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
        });
        AnalysisSubmissionModel submission = sampleSubmission();
        String body = StringUtils.repeat('x', 1536 * 1024);
        submission.setFiles(Lists.newArrayList(List.of(file("A.java", body), file("B.java", body), file("C.java", body))));
        submission.setLocalReview(new LocalReviewModel().bugs(new BugsModel()));

        AnalysisAcceptedModel response = AnalysisSubmitter.submit(serverUrl(), new ApiKeyCredential(API_KEY), 5, 5, submission, LOG);

        String base = "/api/v1/analyses/uploads/" + uploadId;
        assertEquals(List.of("POST /api/v1/analyses/uploads", "PUT " + base + "/files", "PUT " + base + "/files", "PUT " + base + "/local-review", "POST " + base + "/finalize"),
                recorded.stream().map(r -> r.method + " " + r.path).toList(), "three 1.5 MB files make two batches under the 4 MB ceiling");
        JsonNode open = MAPPER.readTree(recorded.get(0).body);
        assertEquals(0, open.path("files").size(), "the open call carries no files");
        assertTrue(open.path("localReview").isMissingNode() || open.path("localReview").isNull(), "nor any other part");
        assertEquals(3, MAPPER.readTree(recorded.get(4).body).path("fileCount").asInt());
        assertEquals(3, submission.getFiles().size(), "the caller's submission is left as it was");
        assertNotNull(submission.getLocalReview());
        assertEquals(analysisId, response.getAnalysisId());
    }
    @Test
    void submitPropagatesNonRetryableApiErrorWithoutRetries() throws Exception {
        installHandler(400, JSON.objectNode().put("message", "bad submission").toString());

        ApiException err = assertThrows(ApiException.class, () -> AnalysisSubmitter.submit(
                serverUrl(), new ApiKeyCredential(API_KEY), 5, 5, sampleSubmission(), LOG));

        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), err.getCode());
        assertEquals(1, callCount.get(), "4xx must not be retried");
    }
    @Test
    void awaitCompletionReturnsAFailedAnalysisInsteadOfThrowing() throws Exception {
        UUID analysisId = UUID.randomUUID();
        installHandler(200, jsonResultBody(analysisId, "failed"));

        AnalysisResultModel result = AnalysisSubmitter.awaitCompletion(
                serverUrl(), new ApiKeyCredential(API_KEY), 5, 5, analysisId, Duration.ofSeconds(30), Duration.ofSeconds(1), LOG);

        assertEquals(AnalysisResultModel.StatusEnum.FAILED, result.getStatus(),
                "a failed analysis is returned, so every caller has to inspect the status itself");
        assertEquals(1, callCount.get(), "failed is terminal and must not be polled again");
    }
    private static void respond(HttpExchange exchange, int status, String responseBody) throws IOException {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
    private static FileChangeModel file(String path, String diff) {
        FileChangeModel toReturn = new FileChangeModel();
        toReturn.setPath(path);
        toReturn.setDiff(diff);
        return toReturn;
    }
    private void installHandler(int status, String responseBody) {
        server.createContext("/api/v1/analyses", exchange -> {
            callCount.incrementAndGet();
            recorded.add(RecordedRequest.from(exchange));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }
    private String serverUrl() {
        return RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
    }
    private static AnalysisSubmissionModel sampleSubmission() {
        ProjectModel project = new ProjectModel();
        project.setCode("codiqo-test");
        project.setName("Codiqo Test");

        CommitModel commit = new CommitModel();
        commit.setSha(SHA);
        commit.setMessage("test commit");
        commit.setAuthor("Tester");
        commit.setAuthorEmail("tester@example.com");
        commit.setTimestamp(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        AnalysisSubmissionModel submission = new AnalysisSubmissionModel();
        submission.setProject(project);
        submission.setCommit(commit);
        return submission;
    }
    private static String jsonResultBody(UUID analysisId, String status) {
        ObjectNode toReturn = accepted(analysisId, status);
        toReturn.putObject("project").put("code", "codiqo-test");
        toReturn.putObject("commit").put("sha", SHA);
        toReturn.putObject("riskScore").put("overall", 0.0).put("grade", "F");
        return toReturn.toString();
    }
    private static String jsonAcceptedBody(UUID analysisId, String status) {
        return accepted(analysisId, status).toString();
    }
    private static ObjectNode accepted(UUID analysisId, String status) {
        return JSON.objectNode().put("analysisId", analysisId.toString()).put("status", status);
    }
    private static final class RecordedRequest {
        final String method;
        final String path;
        final String apiKeyHeader;
        final String body;

        RecordedRequest(String method, String path, String apiKeyHeader, String body) {
            this.method = method;
            this.path = path;
            this.apiKeyHeader = apiKeyHeader;
            this.body = body;
        }
        static RecordedRequest from(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String apiKeyHeader = exchange.getRequestHeaders().getFirst("X-API-Key");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            return new RecordedRequest(method, path, apiKeyHeader, body);
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
