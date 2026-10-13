package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.review.BackgroundReview;
import io.codiqo.submit.review.LocalReviewRun;

/** local mode on Gradle reads the same settings as on Maven and treats a failed review the same way */
class AnalysisEngineReviewTest {
    private static final SlfLogFactory LOGS = new SlfLogFactory();
    private static final Log LOG = LOGS.getLogger(AnalysisEngineReviewTest.class);

    @Test
    void theReviewSettingsReachTheRunAndTheTraceGoesBesideTheOutput() {
        AnalysisRequest request = new AnalysisRequest();
        request.setOutputDirectory("/work/build/codiqo");
        request.setReviewBaseUrl("http://127.0.0.1:11434/v1");
        request.setReviewTimeoutMinutes("45");
        request.setReviewRelayRetries("5");
        RunArgs args = new RunArgs();

        AnalysisEngine.applyReviewSettings(request, args);

        assertTrue(args.isReviewEnabled(), "on by default");
        assertEquals("http://127.0.0.1:11434/v1", args.getReviewBaseUrl());
        assertEquals(Duration.ofMinutes(45), args.getReviewTimeout());
        assertEquals(5, args.getReviewRelayRetries());
        assertEquals(new RunArgs().getReviewCoordinatorModel(), args.getReviewCoordinatorModel(), "an unset setting keeps its default");
        assertEquals(new File("/work/build/codiqo"), args.getReviewTraceDirectory());
    }
    @Test
    void aCommitWithoutAReviewIsSubmittedAsItIs() throws Exception {
        assertTrue(AnalysisEngine.judged(new AnalysisRequest(), new RunArgs(), Optional.empty(), context(), LOG));
    }
    @Test
    void aFailedReviewLeavesTheCommitUnsubmitted() throws Exception {
        try (BackgroundReview<LocalReviewer.ReviewOutcome> failing = BackgroundReview.start(() -> {
            throw new IOException("the coordinator answered nothing");
        })) {
            Optional<LocalReviewRun> review = Optional.of(LocalReviewRun.of(failing, DefaultLanguageProcessors.reviewLanguages(), LOGS));
            SubmissionContext ctx = context();

            assertFalse(AnalysisEngine.judged(new AnalysisRequest(), ctx.getArgs(), review, ctx, LOG), "excluded, never submitted for the prompt to judge");
        }
    }
    private static SubmissionContext context() {
        RunArgs args = new RunArgs();
        args.setCommitId("0123456789abcdef0123456789abcdef01234567");
        return SubmissionContext.builder().args(args).submissionModel(new AnalysisSubmissionModel()).build();
    }
}
