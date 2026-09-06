package io.codiqo.submit;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.Strings;

import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiClient;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.AnalysisApi;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.ProjectMetricsModel;
import lombok.experimental.UtilityClass;

@UtilityClass
public class AnalysisSubmitter {
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String ANALYSIS_PATH = "%s/api/v1/analyses/%s";
    private static final Set<AnalysisResultModel.StatusEnum> TERMINAL_STATUSES = EnumSet.of(AnalysisResultModel.StatusEnum.COMPLETED, AnalysisResultModel.StatusEnum.FAILED);

    public static AnalysisAcceptedModel submit(
            String apiUrl,
            String apiKey,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            AnalysisSubmissionModel submission,
            Log log) throws ApiException {
        ApiClient apiClient = newApiClient(apiUrl, apiKey, connectTimeoutSeconds, readTimeoutSeconds);
        AnalysisApi client = new AnalysisApi(apiClient);
        log.info("submitting analysis to " + apiUrl + " (" + describePayloadSize(apiClient, submission) + ")");

        return ApiRetry.call(log, "submitAnalysis", apiUrl, () -> client.submitAnalysis(submission));
    }
    public static AnalysisAcceptedModel submitUncommitted(
            String apiUrl,
            String apiKey,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            AnalysisSubmissionModel submission,
            Log log) throws ApiException {
        ApiClient apiClient = newApiClient(apiUrl, apiKey, connectTimeoutSeconds, readTimeoutSeconds);
        AnalysisApi client = new AnalysisApi(apiClient);
        log.info("submitting uncommitted changes to " + apiUrl + " (" + describePayloadSize(apiClient, submission) + ")");

        return ApiRetry.call(log, "submitUncommittedAnalysis", apiUrl, () -> client.submitUncommittedAnalysis(submission));
    }
    public static void exclude(
            String apiUrl,
            String apiKey,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            String commitSha,
            String reason,
            AnalysisExcludeCategory category,
            String detail,
            List<FileChangeModel> files,
            ProjectMetricsModel projectMetrics,
            Log log) throws ApiException {
        AnalysisApi client = buildClient(apiUrl, apiKey, connectTimeoutSeconds, readTimeoutSeconds);
        log.info("excluding commit " + commitSha + " at " + apiUrl + " (reason: " + reason + ", category: " + category + ", files: " + files.size() + ")");

        AnalysisExcludeModel body = new AnalysisExcludeModel();
        body.setReason(reason);
        body.setCategory(category);
        body.setDetail(detail);
        body.setFiles(files);
        body.setProjectMetrics(projectMetrics);

        ApiRetry.call(log, "excludeAnalysis", apiUrl, () -> {
            client.excludeAnalysis(commitSha, body);
            return new Object();
        });
    }
    /**
     * Polls the analysis until it reaches a terminal status or the deadline passes, and returns the last state seen.
     * Scoring is asynchronous, so a submission alone says nothing about the outcome, and a scorer that never finishes
     * must not wedge the build.
     *
     * <p>The overall wait is the caller's to choose and is deliberately not the build timeout: that one is sized for
     * a forked CI build, so borrowing it would block an interactive terminal for the better part of an hour.
     */
    public static AnalysisResultModel awaitCompletion(
            String apiUrl,
            String apiKey,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            UUID analysisId,
            Duration deadline,
            Duration pollInterval,
            Log log) throws ApiException {
        AnalysisApi client = buildClient(apiUrl, apiKey, connectTimeoutSeconds, readTimeoutSeconds);
        Instant giveUpAt = Instant.now().plus(deadline);
        log.info(String.format("waiting up to %s for analysis %s to finish", deadline, analysisId));

        for (;;) {
            AnalysisResultModel last = ApiRetry.call(log, "getAnalysis", apiUrl, () -> client.getAnalysis(analysisId));
            AnalysisResultModel.StatusEnum status = last.getStatus();
            if (TERMINAL_STATUSES.contains(status)) {
                log.info(String.format("analysis %s finished with status %s", analysisId, status));
                return last;
            }
            if (Instant.now().plus(pollInterval).isAfter(giveUpAt)) {
                log.warn(String.format(
                        "gave up waiting for analysis %s after %s; last status was %s. The analysis keeps running server-side — poll %s to see the result",
                        analysisId,
                        deadline,
                        status,
                        ANALYSIS_PATH.formatted(Strings.CS.removeEnd(apiUrl, "/"), analysisId)));
                return last;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
                log.warn("interrupted while waiting for analysis " + analysisId);
                return last;
            }
        }
    }
    public static AnalysisApi buildClient(String apiUrl, String apiKey, long connectTimeoutSeconds, long readTimeoutSeconds) {
        return new AnalysisApi(newApiClient(apiUrl, apiKey, connectTimeoutSeconds, readTimeoutSeconds));
    }
    private static ApiClient newApiClient(String apiUrl, String apiKey, long connectTimeoutSeconds, long readTimeoutSeconds) {
        ApiClient toReturn = new ApiClient();
        toReturn.updateBaseUri(Strings.CS.removeEnd(apiUrl, "/"));
        toReturn.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        toReturn.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        toReturn.setRequestInterceptor(builder -> builder.header(API_KEY_HEADER, apiKey));
        return toReturn;
    }
    /**
     * The server enforces a request ceiling and rejects an oversized submission with a bare 413 carrying no body --
     * it is refused by the HTTP layer before any application filter runs, so without this neither side records how
     * big the payload actually was. Counted through a null sink rather than {@code writeValueAsBytes}: the payloads
     * worth measuring are the ones already large enough to be rejected, and buffering one into a byte[] purely to
     * read its length would be the largest allocation on that path.
     */
    private static String describePayloadSize(ApiClient apiClient, AnalysisSubmissionModel submission) {
        CountingOutputStream counter = new CountingOutputStream(NullOutputStream.INSTANCE);
        apiClient.getObjectMapper().writeValue(counter, submission);

        long bytes = counter.getByteCount();
        return FileUtils.byteCountToDisplaySize(bytes) + ", " + bytes + " bytes";
    }
}
