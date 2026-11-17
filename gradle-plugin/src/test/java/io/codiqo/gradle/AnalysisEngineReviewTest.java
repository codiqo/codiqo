package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.gradle.model.AnalysisRequest;

/** local mode on Gradle reads the same settings as on Maven */
class AnalysisEngineReviewTest {
    @Test
    void theReviewSettingsReachTheRunAndTheTraceGoesBesideTheOutput() {
        RunArgs args = AnalysisEngine.runArgs(requestWith(Map.of(
                "codiqo.outputDirectory", "/work/build/codiqo",
                "codiqo.review.baseUrl", "http://127.0.0.1:11434/v1",
                "codiqo.review.timeoutMinutes", "45",
                "codiqo.review.relayRetries", "5",
                "codiqo.review.temperature", "0.6")));

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
    void aTraceDirectoryGivenWinsOverTheOutputDirectory() {
        RunArgs args = AnalysisEngine.runArgs(requestWith(Map.of(
                "codiqo.outputDirectory", "/work/build/codiqo",
                "codiqo.review.traceDirectory", "/logs/review-trace")));

        assertEquals(new File("/logs/review-trace"), args.getReviewTraceDirectory());
    }
    private static AnalysisRequest requestWith(Map<String, String> settings) {
        AnalysisRequest toReturn = new AnalysisRequest();
        toReturn.setSettings(settings);
        return toReturn;
    }
}
