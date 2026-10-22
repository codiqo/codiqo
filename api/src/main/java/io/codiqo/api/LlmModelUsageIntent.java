package io.codiqo.api;

/**
 * What Codiqo calls a model for, and the model each purpose uses: the same for every organization, which gives only its
 * provider and key. {@link #operation()} is the operation code a call's usage is recorded under.
 */
public enum LlmModelUsageIntent {
    /**
     * Plans the local review and writes its commit-level answer, through the LLM proxy. Nothing but the prompt holds that
     * answer, one JSON object of several thousand tokens, to JSON: OpenCode's prompt API takes text only, and ollama.com
     * ignored {@code format} and {@code response_format} for deepseek-v4.1-flash. The coordinator is therefore a non-flash
     * model: at the review's temperature of 1.0 ({@code RunArgs.reviewTemperature}) deepseek-v4.1-flash closed the
     * answer's last array with a brace in 2 of 83 reviews, against none of 392 at 0.0, and each of those reviews failed.
     * Putting deepseek-v4.1-flash back here at 1.0 fails reviews that way, and lowering the temperature to 0.0 instead
     * repeats tool calls. How often GLM-5.3 slips has not been measured.
     */
    LOCAL_REVIEW_COORDINATOR(LlmModel.GLM_5_3, "local_review"),
    /** reviews one part of the commit for the coordinator */
    LOCAL_REVIEW_SUB_AGENT(LlmModel.GLM_5_3_FLASH, "local_review"),
    SKIP_REQUEST_DETECTION(LlmModel.DEEPSEEK_V4_1_FLASH, "skip_request_detection"),
    TAG_CONSOLIDATION(LlmModel.DEEPSEEK_V4_1_FLASH, "tag_consolidation"),
    AWARD_RECAP(LlmModel.DEEPSEEK_V4_1_FLASH, "award_recap"),
    AWARD_CITATION(LlmModel.DEEPSEEK_V4_1_FLASH, "award_citation"),
    ORG_DIGEST(LlmModel.DEEPSEEK_V4_1_FLASH, "org_digest"),
    /**
     * Answers a member's questions in the dashboard's assistant, calling Codiqo's read tools over several steps before it
     * answers. Measured on the assistant's own system prompt and its 26 tools, nine typical questions asked twice, the
     * first step was the right tool with valid arguments in 15 of 18 calls for glm-5.3-flash, 14 for glm-5.3 and 12 for
     * deepseek-v4.1-flash; no call was malformed, and the misses looked a project up before naming it. glm-5.3 costs
     * about nine times as much per token on ollama.com for no better first step. Each extra step is another model call
     * the organization pays for, so a model not measured this way may cost more for the same answer.
     */
    ASSISTANT(LlmModel.GLM_5_3_FLASH, "assistant");

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
