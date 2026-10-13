package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.review.LocalReviewException;
import io.codiqo.submit.review.LocalReviewRun;
import io.codiqo.submit.review.BackgroundReview;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.maven.logging.MavenLogFactory;

/**
 * Local mode judges a commit with the review alone, so a review that fails excludes the commit: the server's prompt
 * never judges it in the review's place, and the next run does not pay for the review again. The test mojo has no
 * server to submit to, so a submission would fail the call.
 */
class LocalReviewFailureTest {
    private static final String COMMIT = "1a2b3c4d";

    @Test
    void aFailedReviewExcludesTheCommitAndSubmitsNothing() throws Exception {
        List<String> excluded = Lists.newArrayList();
        SubmitCommitAnalysisMojo mojo = excludingMojo(excluded, () -> {
            throw new IOException("opencode serve exited with code 1");
        });

        mojo.doLlmScoring(context());

        assertEquals(List.of(COMMIT + " " + AnalysisExcludeCategory.LOCAL_REVIEW_FAILURE + " local review failed: opencode serve exited with code 1"), excluded);
    }
    /** an interrupt is the run being stopped, not a failed review: the commit is left for the next run */
    @Test
    void anInterruptedWaitIsNotAFailedReview() throws Exception {
        List<String> excluded = Lists.newArrayList();
        SubmitCommitAnalysisMojo mojo = excludingMojo(excluded, () -> {
            Thread.sleep(Long.MAX_VALUE);
            return null;
        });

        Thread.currentThread().interrupt();
        try {
            assertThrows(LocalReviewException.class, () -> mojo.doLlmScoring(context()));
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        }
        assertEquals(List.of(), excluded);
    }
    private static SubmitCommitAnalysisMojo excludingMojo(List<String> excluded, Callable<LocalReviewer.ReviewOutcome> review) throws IllegalAccessException {
        SubmitCommitAnalysisMojo toReturn = new SubmitCommitAnalysisMojo() {
            @Override
            protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category, String detail, List<FileChangeModel> files, ProjectMetricsModel projectMetrics) {
                excluded.add(commitSha + " " + category + " " + reason);
            }
        };
        FieldUtils.writeField(toReturn, "reviewRun", LocalReviewRun.of(BackgroundReview.start(review), DefaultLanguageProcessors.reviewLanguages(), new MavenLogFactory(new SystemStreamLog())), true);
        return toReturn;
    }
    private static SubmissionContext context() {
        RunArgs args = new RunArgs();
        args.setCommitId(COMMIT);
        return SubmissionContext.builder().args(args).submissionModel(new AnalysisSubmissionModel()).build();
    }
}
