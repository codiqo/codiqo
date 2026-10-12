package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class LlmModelUsageIntentTest {
    @Test
    void theDefaultsAreTheIntentsModels() {
        RunArgs args = new RunArgs();

        assertEquals(LlmModelUsageIntent.LOCAL_REVIEW_COORDINATOR.model().id(), args.getReviewCoordinatorModel());
        assertEquals(LlmModelUsageIntent.LOCAL_REVIEW_SUB_AGENT.model().id(), args.getReviewReviewerModel());
    }
    /** degraded scoring sends one prompt of the whole commit, so it keeps the model with the large window */
    @Test
    void commitScoringKeepsTheLargeWindow() {
        assertEquals(LlmModel.DEEPSEEK_V4_PRO, LlmModelUsageIntent.COMMIT_SCORING.model());
        assertTrue(LlmModelUsageIntent.COMMIT_SCORING.model().contextWindow() > RunArgs.DEFAULT_NUM_CTX);
    }
    @Test
    void aModelIsFoundByItsId() {
        for (LlmModel model : LlmModel.values()) {
            assertEquals(Optional.of(model), LlmModel.byId(model.id()));
        }
        assertEquals(Optional.empty(), LlmModel.byId("gpt-4o"));
    }
}
