package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.gradle.model.AnalysisRequest;

/** local mode on Gradle reads the same settings as on Maven */
class AnalysisEngineReviewTest {
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
        }
