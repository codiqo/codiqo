package io.codiqo.api;

/**
 * What Codiqo calls a model for, and the model each purpose uses: the same for every organization, which gives only its
 * provider and key. {@link #operation()} is the operation code a call's usage is recorded under.
 */
public enum LlmModelUsageIntent {
    /** plans the local review and writes its commit-level answer, through the LLM proxy */
    LOCAL_REVIEW_COORDINATOR(LlmModel.DEEPSEEK_V4_1_FLASH, "local_review"),
    /** reviews one part of the commit for the coordinator */
    LOCAL_REVIEW_SUB_AGENT(LlmModel.GLM_5_3_FLASH, "local_review"),
    SKIP_REQUEST_DETECTION(LlmModel.DEEPSEEK_V4_1_FLASH, "skip_request_detection"),
    TAG_CONSOLIDATION(LlmModel.DEEPSEEK_V4_1_FLASH, "tag_consolidation"),
    AWARD_RECAP(LlmModel.DEEPSEEK_V4_1_FLASH, "award_recap"),
    AWARD_CITATION(LlmModel.DEEPSEEK_V4_1_FLASH, "award_citation"),
    ORG_DIGEST(LlmModel.DEEPSEEK_V4_1_FLASH, "org_digest");

    private final LlmModel model;
    private final String operation;

    LlmModelUsageIntent(LlmModel model, String operation) {
        this.model = model;
        this.operation = operation;
    }
    public LlmModel model() {
        return model;
    }
    public String operation() {
        return operation;
    }
}
