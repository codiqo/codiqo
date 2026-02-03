package io.codiqo.llm;

import java.time.Duration;
import java.util.List;

import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import lombok.Builder;
import lombok.Value;

public interface ReportBuilder {
    String buildReport(ScoringResult result, LlmScoringRequest request, ReportContext reportContext);

    @Value
    @Builder
    class ReportContext {
        String commitId;
        String author;
        String authorEmail;
        String timestamp;
        String commitMessage;
        List<String> branches;
        boolean mergeCommit;
        boolean revertCommit;
        String revertedCommitId;
        String repositoryName;
        String llmModel;
        Duration analysisDuration;
    }
}
