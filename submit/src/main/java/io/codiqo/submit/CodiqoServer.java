package io.codiqo.submit;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.llm.review.PreviousFinding;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.submit.hotspots.FixCommits;

/**
 * Everything one run of the analysis asks of Codiqo, for one project. Both build tools reach Codiqo through it, so a send
 * path cannot skip source hiding or resolve a login of its own: {@link HttpCodiqoServer} hides the source of every model
 * it sends and resolves the member's credential once, on the first call that needs it.
 */
public interface CodiqoServer {
    /** the commits the project indexed, asked about only for a merge ({@link CommitExclusions#mergeExclusion}) */
    IndexedCommits indexedCommits();
    /** throws {@link ProjectHaltedException} while an administrator has the project halted */
    void requireRunning() throws Exception;
    void exclude(String commitSha, AnalysisExcludeModel exclusion) throws Exception;
    /** with the local review's trace when the commit was reviewed: the trace goes with the review it explains, never alone */
    AnalysisAcceptedModel submit(AnalysisSubmissionModel submission, Optional<File> reviewTrace) throws Exception;
    AnalysisAcceptedModel submitUncommitted(AnalysisSubmissionModel submission) throws Exception;
    AnalysisResultModel awaitCompletion(UUID analysisId, Duration deadline, Duration pollInterval) throws Exception;
    /** records the failure as the project's halt, and says whether it was recorded; it never throws ({@link ProjectHalts#halt}) */
    boolean halt(String commitSha, Throwable cause);
    ReviewTags projectTags() throws Exception;
    /** best-effort: none when they cannot be fetched, or when the project hides its source ({@link PreviousFindings}) */
    List<PreviousFinding> previousFindings(String commitSha);
    FixCommits fixCommits(Instant since) throws Exception;
    void submitHotspots(HotspotSnapshotModel snapshot) throws Exception;
    /** the end point the local review calls its models at ({@link io.codiqo.submit.review.LocalReviewRun#endpoint}) */
    ReviewEndpoint reviewEndpoint(RunArgs args) throws Exception;
}
