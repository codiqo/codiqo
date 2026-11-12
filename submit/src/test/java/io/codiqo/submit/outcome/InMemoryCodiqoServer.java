package io.codiqo.submit.outcome;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.ProjectHaltModel;
import io.codiqo.llm.review.PreviousFinding;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.submit.CodiqoServer;
import io.codiqo.submit.IndexedCommits;
import io.codiqo.submit.ProjectHaltedException;
import io.codiqo.submit.hotspots.FixCommits;

/** Codiqo as one run sees it, kept in memory: what the run sent is recorded for the test to read */
final class InMemoryCodiqoServer implements CodiqoServer {
    final Map<String, AnalysisExcludeModel> exclusions = Maps.newLinkedHashMap();
    final List<AnalysisSubmissionModel> submissions = Lists.newArrayList();
    /** the review trace sent with each submission */
    final List<Optional<File>> traces = Lists.newArrayList();
    /** each recorded halt: the commit and the banner's reason */
    final List<Pair<String, String>> halts = Lists.newArrayList();
    boolean halted;

    @Override
    public IndexedCommits indexedCommits() {
        return IndexedCommits.NONE;
    }
    @Override
    public void requireRunning() {
        if (halted) {
            throw new ProjectHaltedException("com.example:app", new ProjectHaltModel().reason("an earlier run failed on Codiqo's side"));
        }
    }
    @Override
    public void exclude(String commitSha, AnalysisExcludeModel exclusion) {
        exclusions.put(commitSha, exclusion);
    }
    @Override
    public AnalysisAcceptedModel submit(AnalysisSubmissionModel submission, Optional<File> reviewTrace) {
        submissions.add(submission);
        traces.add(reviewTrace);
        return new AnalysisAcceptedModel().analysisId(UUID.randomUUID());
    }
    @Override
    public AnalysisAcceptedModel submitUncommitted(AnalysisSubmissionModel submission) {
        throw new UnsupportedOperationException("a commit's run submits no uncommitted changes");
    }
    @Override
    public AnalysisResultModel awaitCompletion(UUID analysisId, Duration deadline, Duration pollInterval) {
        throw new UnsupportedOperationException("a commit's run does not wait for its score");
    }
    @Override
    public boolean halt(String commitSha, String reason, String detail) {
        halts.add(Pair.of(commitSha, reason));
        return true;
    }
    @Override
    public ReviewTags projectTags() {
        return ReviewTags.NONE;
    }
    @Override
    public List<PreviousFinding> previousFindings(String commitSha) {
        return List.of();
    }
    @Override
    public FixCommits fixCommits(Instant since) {
        return FixCommits.byMessage();
    }
    @Override
    public void submitHotspots(HotspotSnapshotModel snapshot) {
    }
    @Override
    public ReviewEndpoint reviewEndpoint(RunArgs args) {
        return new ReviewEndpoint(args.getReviewBaseUrl(), null, null);
    }
}
