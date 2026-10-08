package io.codiqo.submit;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiClient;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.AnalysisApi;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.AnalysisUploadFilesModel;
import io.codiqo.client.model.AnalysisUploadFinalizeModel;
import io.codiqo.client.model.CodebaseIndexModel;
import io.codiqo.client.model.DependencyRegistryModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FullProjectCoverageModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.submit.auth.CodiqoApiClients;
import io.codiqo.submit.auth.CodiqoCredential;
import lombok.experimental.UtilityClass;
import io.netty.handler.codec.http.HttpResponseStatus;

@UtilityClass
public class AnalysisSubmitter {
    private static final String ANALYSIS_PATH = "%s/api/v1/analyses/%s";
    private static final Set<Integer> UNSTAGED_SERVER = Set.of(HttpResponseStatus.NOT_FOUND.code(), HttpResponseStatus.METHOD_NOT_ALLOWED.code());
    private static final Set<AnalysisResultModel.StatusEnum> TERMINAL_STATUSES = EnumSet.of(AnalysisResultModel.StatusEnum.COMPLETED, AnalysisResultModel.StatusEnum.FAILED);

    public static AnalysisAcceptedModel submit(
            String apiUrl,
            CodiqoCredential credential,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            AnalysisSubmissionModel submission,
            Log log) throws ApiException {
        ApiClient apiClient = CodiqoApiClients.newApiClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds);
        AnalysisApi client = new AnalysisApi(apiClient);
        log.info("submitting analysis to " + apiUrl + " (" + describePayloadSize(apiClient, submission) + ")");

        Optional<UUID> uploadId = openUpload(client, apiUrl, submission, log);
        if (uploadId.isEmpty()) {
            return ApiRetry.call(log, "submitAnalysis", apiUrl, () -> client.submitAnalysis(submission));
        }
        Optional<AnalysisAcceptedModel> accepted = uploadParts(apiClient, client, apiUrl, uploadId.get(), submission, log);
        if (accepted.isEmpty()) {
            /** a re-submission of the same commit is idempotent on the server, so staging it again reads the accepted analysis */
            log.warn("upload " + uploadId.get() + " was finalized by an attempt whose answer was lost; staging the submission again to read the result");
            Optional<UUID> again = openUpload(client, apiUrl, submission, log);
            if (again.isPresent()) {
                accepted = uploadParts(apiClient, client, apiUrl, again.get(), submission, log);
            }
        }
        return accepted.orElseThrow(() -> new ApiException(HttpResponseStatus.NOT_FOUND.code(), "the staged upload of the submission could not be finalized"));
    }
    /**
     * Opens a staged upload with the submission's light part, or answers empty when the server predates staged
     * uploads, so the caller falls back to one request. The heavy fields are cleared only for this call and put back
     * whatever happens: a field added to the model later still travels in the open call instead of being dropped.
     */
    private static Optional<UUID> openUpload(AnalysisApi client, String apiUrl, AnalysisSubmissionModel submission, Log log) throws ApiException {
        List<FileChangeModel> files = submission.getFiles();
        DuplicationReportModel duplication = submission.getDuplication();
        DependencyRegistryModel dependencies = submission.getDependencies();
        FullProjectCoverageModel coverage = submission.getFullProjectCoverage();
        CodebaseIndexModel index = submission.getIndex();
        LocalReviewModel review = submission.getLocalReview();
        try {
            submission.setFiles(List.of());
            submission.setDuplication(null);
            submission.setDependencies(null);
            submission.setFullProjectCoverage(null);
            submission.setIndex(null);
            submission.setLocalReview(null);
            return Optional.of(ApiRetry.call(log, "openAnalysisUpload", apiUrl, () -> client.openAnalysisUpload(submission)).getUploadId());
        } catch (ApiException err) {
            /** a server without staged uploads answers 404, or 405 where the path matches its GET-only analysis route */
            if (UNSTAGED_SERVER.contains(err.getCode())) {
                log.info("the server takes no staged uploads yet (HTTP " + err.getCode() + "), submitting in one request");
                return Optional.empty();
            }
            throw err;
        } finally {
            submission.setFiles(files);
            submission.setDuplication(duplication);
            submission.setDependencies(dependencies);
            submission.setFullProjectCoverage(coverage);
            submission.setIndex(index);
            submission.setLocalReview(review);
        }
    }
    /**
     * Files in batches of at most {@link RunArgs#UPLOAD_BATCH_BYTES}, which stays far below the server's request
     * ceiling (32 MB) so that a retry resends little, then every part present, then the finalize call.
     *
     * @return the accepted analysis, or empty when a retried finalize found the upload already applied by an attempt
     *         whose answer was lost
     */
    private static Optional<AnalysisAcceptedModel> uploadParts(ApiClient apiClient, AnalysisApi client, String apiUrl, UUID uploadId, AnalysisSubmissionModel submission, Log log)
            throws ApiException {
        List<FileChangeModel> batch = Lists.newArrayList();
        long batchBytes = 0;
        int batches = 0;
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            long bytes = serializedSize(apiClient, file);
            if (BooleanUtils.and(new boolean[] { CollectionUtils.isNotEmpty(batch), batchBytes + bytes > RunArgs.UPLOAD_BATCH_BYTES })) {
                putFiles(client, apiUrl, uploadId, batch, log);
                batches++;
                batch = Lists.newArrayList();
                batchBytes = 0;
            }
            batch.add(file);
            batchBytes += bytes;
        }
        if (CollectionUtils.isNotEmpty(batch)) {
            putFiles(client, apiUrl, uploadId, batch, log);
            batches++;
        }

        if (Objects.nonNull(submission.getDuplication())) {
            ApiRetry.call(log, "putAnalysisUploadDuplication", apiUrl, () -> {
                client.putAnalysisUploadDuplication(uploadId, submission.getDuplication());
                return uploadId;
            });
        }
        if (Objects.nonNull(submission.getDependencies())) {
            ApiRetry.call(log, "putAnalysisUploadDependencies", apiUrl, () -> {
                client.putAnalysisUploadDependencies(uploadId, submission.getDependencies());
                return uploadId;
            });
        }
        if (Objects.nonNull(submission.getFullProjectCoverage())) {
            ApiRetry.call(log, "putAnalysisUploadFullCoverage", apiUrl, () -> {
                client.putAnalysisUploadFullCoverage(uploadId, submission.getFullProjectCoverage());
                return uploadId;
            });
        }
        if (Objects.nonNull(submission.getIndex())) {
            ApiRetry.call(log, "putAnalysisUploadIndex", apiUrl, () -> {
                client.putAnalysisUploadIndex(uploadId, submission.getIndex());
                return uploadId;
            });
        }
        if (Objects.nonNull(submission.getLocalReview())) {
            ApiRetry.call(log, "putAnalysisUploadLocalReview", apiUrl, () -> {
                client.putAnalysisUploadLocalReview(uploadId, submission.getLocalReview());
                return uploadId;
            });
        }

        /** the server keys files by path, so a path listed twice is stored once */
        int fileCount = (int) CollectionUtils.emptyIfNull(submission.getFiles()).stream().map(FileChangeModel::getPath).distinct().count();
        log.info(String.format("upload %s: %d files in %d batches, finalizing", uploadId, fileCount, batches));
        AtomicInteger attempts = new AtomicInteger();
        try {
            return Optional.of(ApiRetry.call(log, "finalizeAnalysisUpload", apiUrl, () -> {
                attempts.incrementAndGet();
                return client.finalizeAnalysisUpload(uploadId, new AnalysisUploadFinalizeModel().fileCount(fileCount));
            }));
        } catch (ApiException err) {
            /** the server deletes the upload in the transaction that stores the analysis: a retry's 404 means an earlier attempt was applied */
            if (BooleanUtils.and(new boolean[] { err.getCode() == HttpResponseStatus.NOT_FOUND.code(), attempts.get() > 1 })) {
                return Optional.empty();
            }
            throw err;
        }
    }
    private static void putFiles(AnalysisApi client, String apiUrl, UUID uploadId, List<FileChangeModel> files, Log log) throws ApiException {
        ApiRetry.call(log, "putAnalysisUploadFiles", apiUrl, () -> {
            client.putAnalysisUploadFiles(uploadId, new AnalysisUploadFilesModel().files(files));
            return uploadId;
        });
    }
    public static AnalysisAcceptedModel submitUncommitted(
            String apiUrl,
            CodiqoCredential credential,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            AnalysisSubmissionModel submission,
            Log log) throws ApiException {
        ApiClient apiClient = CodiqoApiClients.newApiClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds);
        AnalysisApi client = new AnalysisApi(apiClient);
        log.info("submitting uncommitted changes to " + apiUrl + " (" + describePayloadSize(apiClient, submission) + ")");

        return ApiRetry.call(log, "submitUncommittedAnalysis", apiUrl, () -> client.submitUncommittedAnalysis(submission));
    }
    public static void exclude(
            String apiUrl,
            CodiqoCredential credential,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            String commitSha,
            String reason,
            AnalysisExcludeCategory category,
            String detail,
            List<FileChangeModel> files,
            ProjectMetricsModel projectMetrics,
            Log log) throws ApiException {
        AnalysisApi client = buildClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds);
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
            CodiqoCredential credential,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            UUID analysisId,
            Duration deadline,
            Duration pollInterval,
            Log log) throws ApiException {
        AnalysisApi client = buildClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds);
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
    public static AnalysisApi buildClient(String apiUrl, CodiqoCredential credential, long connectTimeoutSeconds, long readTimeoutSeconds) {
        return new AnalysisApi(CodiqoApiClients.newApiClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds));
    }
    /**
     * The server enforces a request ceiling and rejects an oversized submission with a bare 413 carrying no body --
     * it is refused by the HTTP layer before any application filter runs, so without this neither side records how
     * big the payload actually was. Counted through a null sink rather than {@code writeValueAsBytes}: the payloads
     * worth measuring are the ones already large enough to be rejected, and buffering one into a byte[] purely to
     * read its length would be the largest allocation on that path.
     */
    private static String describePayloadSize(ApiClient apiClient, AnalysisSubmissionModel submission) {
        long bytes = serializedSize(apiClient, submission);
        return FileUtils.byteCountToDisplaySize(bytes) + ", " + bytes + " bytes";
    }
    private static long serializedSize(ApiClient apiClient, Object value) {
        CountingOutputStream counter = new CountingOutputStream(NullOutputStream.INSTANCE);
        apiClient.getObjectMapper().writeValue(counter, value);
        return counter.getByteCount();
    }
}
