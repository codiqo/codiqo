package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.HttpServer;

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
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String REVIEW_ERROR = "the coordinator answered nothing";

    @Test
    void theReviewSettingsReachTheRunAndTheTraceGoesBesideTheOutput() {
        AnalysisRequest request = new AnalysisRequest();
        request.setOutputDirectory("/work/build/codiqo");
        request.setReviewBaseUrl("http://127.0.0.1:11434/v1");
        request.setReviewTimeoutMinutes("45");
        request.setReviewRelayRetries("5");
        request.setReviewTemperature("0.6");
        RunArgs args = new RunArgs();

        AnalysisEngine.applyReviewSettings(request, args);

        assertTrue(args.isReviewEnabled(), "on by default");
        assertEquals("http://127.0.0.1:11434/v1", args.getReviewBaseUrl());
        assertEquals(Duration.ofMinutes(45), args.getReviewTimeout());
        assertEquals(5, args.getReviewRelayRetries());
        assertEquals(0.6, args.getReviewTemperature());
        assertEquals(new RunArgs().getReviewTopP(), args.getReviewTopP());
        assertEquals(new RunArgs().getReviewCoordinatorModel(), args.getReviewCoordinatorModel(), "an unset setting keeps its default");
        assertEquals(new File("/work/build/codiqo"), args.getReviewTraceDirectory());
    }
    @Test
    void aCommitWithoutAReviewIsSubmittedAsItIs() throws Exception {
        assertTrue(AnalysisEngine.judged(new AnalysisRequest(), new RunArgs(), Optional.empty(), context(), LOG));
    }
    @Test
    void aFailedReviewLeavesTheCommitUnsubmitted() throws Exception {
        List<String> calls = Lists.newCopyOnWriteArrayList();
        List<String> bodies = Lists.newCopyOnWriteArrayList();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/analyses", exchange -> {
            calls.add(exchange.getRequestMethod() + StringUtils.SPACE + exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        AnalysisRequest request = new AnalysisRequest();
        request.setApiUrl(String.format("http://127.0.0.1:%d", server.getAddress().getPort()));
        request.setApiKey("test-api-key");
        request.setConnectTimeoutSeconds(5);
        request.setReadTimeoutSeconds(5);

        try (BackgroundReview<LocalReviewer.ReviewOutcome> failing = BackgroundReview.start(() -> {
            throw new IOException(REVIEW_ERROR);
        })) {
            Optional<LocalReviewRun> review = Optional.of(LocalReviewRun.of(failing, DefaultLanguageProcessors.reviewLanguages(), LOGS));
            SubmissionContext ctx = context();

            assertFalse(AnalysisEngine.judged(request, ctx.getArgs(), review, ctx, LOG), "excluded, never submitted for the prompt to judge");
        } finally {
            server.stop(0);
        }

        assertEquals(List.of("PATCH /api/v1/analyses/commit/" + SHA + "/exclude"), calls, "the exclusion is reported to the backend");
        assertTrue(Strings.CS.contains(bodies.get(0), "LOCAL_REVIEW_FAILURE"), "as a failed review");
    }
    private static SubmissionContext context() {
        RunArgs args = new RunArgs();
        args.setCommitId(SHA);
        return SubmissionContext.builder().args(args).submissionModel(new AnalysisSubmissionModel()).build();
    }
}
