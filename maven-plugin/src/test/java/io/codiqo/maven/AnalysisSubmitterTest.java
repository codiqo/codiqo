package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.client.ApiException;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.ProjectModel;

class AnalysisSubmitterTest {
    private static final String API_KEY = "test-api-key";
    private static final Log LOG = new SystemStreamLog();

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
    @Test
    void submitPostsJsonBodyWithApiKeyHeaderAndParsesResponse() throws Exception {
        UUID analysisId = UUID.randomUUID();
        installHandler(201, jsonAcceptedBody(analysisId, "accepted"));

        AnalysisAcceptedModel response = AnalysisSubmitter.submit(
                serverUrl(), API_KEY, 5, 5, sampleSubmission(), LOG);

        assertEquals(1, recorded.size());
        RecordedRequest req = recorded.get(0);
        assertEquals("POST", req.method);
        assertEquals("/api/v1/analyses", req.path);
        assertEquals(API_KEY, req.apiKeyHeader);
        assertTrue(req.body.contains("\"sha\""), "body must be JSON with commit sha");
        assertTrue(req.body.contains("codiqo-test"), "body must contain project code");

        assertNotNull(response);
        assertEquals(analysisId, response.getAnalysisId());
        assertEquals(AnalysisAcceptedModel.StatusEnum.ACCEPTED, response.getStatus());
    }
    @Test
    void submitPropagatesNonRetryableApiErrorWithoutRetries() throws Exception {
        installHandler(400, "{\"message\":\"bad submission\"}");

        ApiException err = assertThrows(ApiException.class, () -> AnalysisSubmitter.submit(
                serverUrl(), API_KEY, 5, 5, sampleSubmission(), LOG));

        assertEquals(400, err.getCode());
        assertEquals(1, callCount.get(), "4xx must not be retried");
    }
    private void installHandler(int status, String responseBody) {
        server.createContext("/api/v1/analyses", exchange -> {
            callCount.incrementAndGet();
            recorded.add(RecordedRequest.from(exchange));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }
    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
    private static AnalysisSubmissionModel sampleSubmission() {
        ProjectModel project = new ProjectModel();
        project.setCode("codiqo-test");
        project.setName("Codiqo Test");

        CommitModel commit = new CommitModel();
        commit.setSha("0123456789abcdef0123456789abcdef01234567");
        commit.setMessage("test commit");
        commit.setAuthor("Tester");
        commit.setAuthorEmail("tester@example.com");
        commit.setTimestamp(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        AnalysisSubmissionModel submission = new AnalysisSubmissionModel();
        submission.setProject(project);
        submission.setCommit(commit);
        return submission;
    }
    private static String jsonAcceptedBody(UUID analysisId, String status) {
        return "{\"analysisId\":\"" + analysisId + "\",\"status\":\"" + status + "\"}";
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
}
