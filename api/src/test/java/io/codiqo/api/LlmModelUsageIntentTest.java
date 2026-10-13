package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class LlmModelUsageIntentTest {
    @Test
    void theDefaultsAreTheIntentsModels() {
        RunArgs args = new RunArgs();

        assertEquals(LlmModelUsageIntent.LOCAL_REVIEW_COORDINATOR.model().id(), args.getReviewCoordinatorModel());
        assertEquals(LlmModelUsageIntent.LOCAL_REVIEW_SUB_AGENT.model().id(), args.getReviewReviewerModel());
    }
    @Test
    void aModelIsFoundByItsId() {
        for (LlmModel model : LlmModel.values()) {
            assertEquals(Optional.of(model), LlmModel.byId(model.id()));
        }
        assertEquals(Optional.empty(), LlmModel.byId("gpt-4o"));
    }
}
