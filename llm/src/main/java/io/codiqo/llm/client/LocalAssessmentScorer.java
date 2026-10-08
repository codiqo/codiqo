package io.codiqo.llm.client;

import java.util.Locale;
import java.util.Objects;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.FinalScoreCalculator;
import io.codiqo.llm.LocalAssessment;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.VolumeScoreCalculator;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;

/**
 * Scores a commit from its local assessment alone, with no model call and no prompt built: the labels, dimensions
 * and findings are the local review's, and the volume, diff classification, quality multiplier, bonus and risk follow
 * from them by rules ({@link FinalScoreCalculator#applyRules}). The review's own tokens were spent, and recorded,
 * through the LLM proxy.
 */
public class LocalAssessmentScorer {
    /** the model name an analysis scored this way records */
    public static final String MODEL = "local-assessment";

    private final VolumeScoreCalculator volumeCalculator;
    private final FinalScoreCalculator finalScoreCalculator;
    private final Log log;

    public LocalAssessmentScorer(RunArgs args, Log log) {
        this.log = Objects.requireNonNull(log);
        this.volumeCalculator = new VolumeScoreCalculator(args);
        this.finalScoreCalculator = new FinalScoreCalculator(args, log);
    }
    /**
     * @param reviewBugs the local review's bugs, stored with the submission and therefore left off the response; they
     *                   decide the architecture quality factor
     */
    public ScoringClient.ScoringResult score(LlmScoringRequest request, PromptContext context, LlmScoringResponse localAssessment, LlmScoringResponse.Bugs reviewBugs) {
        PreComputedScores preComputedScores = volumeCalculator.calculate(
                request,
                context.getProjectTotalStatements(),
                context.getProjectTotalMethods(),
                context.getMethodCapQuantileProd(),
                context.getMethodCapQuantileTest(),
                context.getConstructorCapQuantileProd(),
                context.getConstructorCapQuantileTest());

        LlmScoringResponse response = new LlmScoringResponse();
        LocalAssessment.overlay(response, localAssessment);
        finalScoreCalculator.applyRules(response, preComputedScores, request, reviewBugs);
        LlmScoringClient.removeTestCodeEstimates(response, request);
        log.info(String.format(Locale.ROOT, "scored from the local assessment by rules: %.2f, no prompt sent", response.getScore()));

        return ScoringClient.ScoringResult.builder()
                .response(response)
                .localAssessmentScore(response.getScore())
                .preComputedScores(preComputedScores)
                .build();
    }
}
