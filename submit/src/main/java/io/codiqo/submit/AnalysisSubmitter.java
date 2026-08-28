package io.codiqo.submit;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        AnalysisApi client = buildClient(apiUrl, apiKey, connectTimeoutSeconds, readTimeoutSeconds);
        log.info("submitting analysis to " + apiUrl);

        return ApiRetry.call(log, "submitAnalysis", apiUrl, () -> client.submitAnalysis(submission));
    }
    public static AnalysisAcceptedModel submitUncommitted(
            String apiUrl,
            String apiKey,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            AnalysisSubmissionModel submission,
            Log log) throws ApiException {
        AnalysisApi client = buildClient(apiUrl, apiKey, connectTimeoutSeconds, readTimeoutSeconds);
        log.info("submitting uncommitted changes to " + apiUrl);

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
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(Strings.CS.removeEnd(apiUrl, "/"));
        apiClient.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        apiClient.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        apiClient.setRequestInterceptor(builder -> builder.header(API_KEY_HEADER, apiKey));
        return new AnalysisApi(apiClient);
    }
}
