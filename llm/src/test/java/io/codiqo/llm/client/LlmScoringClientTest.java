package io.codiqo.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ModifyImpactEstimate;

class LlmScoringClientTest {
    private static final String PROD_SIGNATURE = "io/example/Service.handle()V";
    private static final String TEST_SIGNATURE = "io/example/ServiceTest.handle()V";

    @Test
    void removesEstimatesForTestCodeUnitsAndKeepsProductionOnes() {
        LlmScoringRequest request = LlmScoringRequest.builder()
                .codeBlockChanges(Lists.newArrayList(
                        CodeBlockChange.builder().signature(PROD_SIGNATURE).isTest(false).build(),
                        CodeBlockChange.builder().signature(TEST_SIGNATURE).isTest(true).build()))
                .build();

        LlmScoringResponse response = LlmScoringResponse.builder()
                .modifyImpactEstimates(Lists.newArrayList(
                        ModifyImpactEstimate.builder().signature(PROD_SIGNATURE).build(),
                        ModifyImpactEstimate.builder().signature(TEST_SIGNATURE).build()))
                .build();

        LlmScoringClient.removeTestCodeEstimates(response, request);

        assertEquals(1, response.getModifyImpactEstimates().size());
        assertEquals(PROD_SIGNATURE, response.getModifyImpactEstimates().get(0).getSignature());
    }

    @Test
    void toleratesAbsentEstimatesFromTheLlm() {
        LlmScoringRequest request = LlmScoringRequest.builder()
                .codeBlockChanges(Lists.newArrayList(
                        CodeBlockChange.builder().signature(TEST_SIGNATURE).isTest(true).build()))
                .build();

        LlmScoringResponse response = LlmScoringResponse.builder()
                .modifyImpactEstimates(null)
                .build();

        LlmScoringClient.removeTestCodeEstimates(response, request);

        assertNull(response.getModifyImpactEstimates());
    }
}
