package io.codiqo.submit;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.llm.review.PreviousFinding;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.submit.hotspots.FixCommits;
import io.codiqo.submit.hotspots.HotspotSnapshots;
import io.codiqo.submit.review.LocalReviewRun;

/** Codiqo's REST API, for one project, as the member the credential names. */
public final class HttpCodiqoServer implements CodiqoServer {
    private final String apiUrl;
    private final String resourceUrl;
    private final Callable<CodiqoCredential> credentialSource;
    private final long connectTimeoutSeconds;
    private final long readTimeoutSeconds;
    private final String projectCode;
    private final SourceHiding hiding;
    private final Log log;
    /**
     * Resolved on the first call that needs it and shared by every later one. Each call used to resolve its own, so a
     * developer with no stored login got one browser login from the fix-commit lookup in the middle of the analysis,
     * while the language servers held their memory, and when nobody approved it in time, a second one at the submission.
     */
    private CodiqoCredential credential;

    /**
     * @param projectCode the code the REST API names the project by
     * @param hiding applied to every model sent, the review's trace dropped with it ({@link AnalysisSubmitter#submit})
     */
    public HttpCodiqoServer(
            String apiUrl,
            String resourceUrl,
            Callable<CodiqoCredential> credentialSource,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            String projectCode,
            SourceHiding hiding,
            Log log) {
        this.apiUrl = Objects.requireNonNull(apiUrl);
        this.resourceUrl = Objects.requireNonNull(resourceUrl);
        this.credentialSource = Objects.requireNonNull(credentialSource);
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.projectCode = Objects.requireNonNull(projectCode);
        this.hiding = Objects.requireNonNull(hiding);
        this.log = Objects.requireNonNull(log);
    }
    @Override
    public IndexedCommits indexedCommits() {
        return sha -> IndexedCommits.of(log, commitIndexClient(), apiUrl, projectCode).contains(sha);
    }
    @Override
    public void requireRunning() throws Exception {
        ProjectHalts.requireRunning(log, commitIndexClient(), apiUrl, projectCode);
    }
    @Override
    public void exclude(String commitSha, AnalysisExcludeModel exclusion) throws Exception {
        AnalysisSubmitter.exclude(
                apiUrl,
                credential(),
                connectTimeoutSeconds,
                readTimeoutSeconds,
                commitSha,
                exclusion.getReason(),
                exclusion.getCategory(),
                exclusion.getDetail(),
                exclusion.getFiles(),
                exclusion.getProjectMetrics(),
                hiding,
                log);
    }
    @Override
    public AnalysisAcceptedModel submit(AnalysisSubmissionModel submission, Optional<File> reviewTrace) throws Exception {
        if (reviewTrace.isPresent()) {
            return AnalysisSubmitter.submit(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, submission, reviewTrace.get(), hiding, log);
        }
        return AnalysisSubmitter.submit(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, submission, hiding, log);
    }
    @Override
    public AnalysisAcceptedModel submitUncommitted(AnalysisSubmissionModel submission) throws Exception {
        return AnalysisSubmitter.submitUncommitted(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, submission, hiding, log);
    }
    @Override
    public AnalysisResultModel awaitCompletion(UUID analysisId, Duration deadline, Duration pollInterval) throws Exception {
        return AnalysisSubmitter.awaitCompletion(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, analysisId, deadline, pollInterval, log);
    }
    /** a credential that cannot be resolved is logged like a refused halt: the caller still fails with its own cause */
    @Override
    public boolean halt(String commitSha, Throwable cause) {
        CommitIndexApi client;
        try {
            client = commitIndexClient();
        } catch (Exception err) {
            log.error(err, "project %s could not be halted: %s", projectCode, err.getMessage());
            return false;
        }
        return ProjectHalts.halt(log, client, apiUrl, projectCode, commitSha, cause, hiding.isHidden());
    }
    @Override
    public ReviewTags projectTags() throws Exception {
        return ProjectTags.fetch(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, projectCode, log);
    }
    /** a credential that cannot be resolved leaves the review without the hint, as a failed fetch does */
    @Override
    public List<PreviousFinding> previousFindings(String commitSha) {
        CodiqoCredential member;
        try {
            member = credential();
        } catch (Exception err) {
            log.warn("previous findings: not fetched, no credential: %s", err.getMessage());
            return List.of();
        }
        return PreviousFindings.fetch(apiUrl, member, connectTimeoutSeconds, readTimeoutSeconds, projectCode, commitSha, hiding.isHidden(), log);
    }
    @Override
    public FixCommits fixCommits(Instant since) throws Exception {
        return FixCommits.fetch(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, projectCode, since, log);
    }
    @Override
    public void submitHotspots(HotspotSnapshotModel snapshot) throws Exception {
        HotspotSnapshots.submit(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds, projectCode, snapshot, hiding, log);
    }
    @Override
    public ReviewEndpoint reviewEndpoint(RunArgs args) throws Exception {
        return LocalReviewRun.endpoint(args, resourceUrl, projectCode, this::credential);
    }
    private CommitIndexApi commitIndexClient() throws Exception {
        return CommitIndexPublisher.buildClient(apiUrl, credential(), connectTimeoutSeconds, readTimeoutSeconds);
    }
    private synchronized CodiqoCredential credential() throws Exception {
        if (Objects.isNull(credential)) {
            credential = credentialSource.call();
        }
        return credential;
    }
}
