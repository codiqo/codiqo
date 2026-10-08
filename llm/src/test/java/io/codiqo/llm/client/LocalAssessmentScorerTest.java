package io.codiqo.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;

class LocalAssessmentScorerTest {
    private final RunArgs args = new RunArgs();
    private final LocalAssessmentScorer scorer = new LocalAssessmentScorer(args, NoopLog.INSTANCE);

    @Test
    void scoresTheLocalAssessmentByRules() {
        ScoringClient.ScoringResult result = scorer.score(request(85.0), PromptContext.builder().args(args).build(), assessment(4), LlmScoringResponse.Bugs.builder().build());

        LlmScoringResponse response = result.getResponse();
        assertEquals("moves the retry policy into the client", response.getSummary());
        assertEquals(4, response.getArchitectureEffortBonus().getArchitectureImpactScore());
        assertEquals(0.95 * 3.5 / 4, response.getArchitectureEffortBonus().getQualityFactor(), 1e-3);
        double clean = result.getPreComputedScores().getCpdRecommendedImpact() + result.getPreComputedScores().getStaticAnalysisRecommendedImpact();
        assertEquals(1.0 + clean + args.getCoverageGoodBonus(), response.getQualityMultiplier().getFinalMultiplier(), 1e-9);
        assertNotNull(response.getRiskAssessment());
        assertNull(response.getBugs(), "the review's bugs are stored with the submission, never again from the response");
        assertNull(result.getPromptScore(), "no prompt was scored");
        assertEquals(0, result.getTotalTokens());
    }
    @Test
    void aBlockingReviewBugCutsTheBonus() {
        LlmScoringResponse.Bugs bugs = LlmScoringResponse.Bugs.builder()
                .blocking(Lists.newArrayList(List.of(LlmScoringResponse.Bug.builder().title("lost update").build())))
                .build();
        ScoringClient.ScoringResult result = scorer.score(request(85.0), PromptContext.builder().args(args).build(), assessment(6), bugs);

        assertEquals(0.4 * 5.5 / 6, result.getResponse().getArchitectureEffortBonus().getQualityFactor(), 1e-3);
    }
    /** the review names the finding, the rules price it; its senior-review verdict and blast-radius judgment are recorded */
    @Test
    void theReviewsArchitectureFindingsArePricedByRules() {
        LlmScoringResponse assessment = assessment(4);
        assessment.setQualityMultiplier(LlmScoringResponse.QualityMultiplier.builder()
                .architectureAnalysis(LlmScoringResponse.ArchitectureAnalysis.builder().solidViolations(List.of("SRP: the client also parses config")).build())
                .build());
        assessment.setBlastRadiusAnalysis(LlmScoringResponse.BlastRadiusAnalysis.builder().moduleType(LlmScoringResponse.ModuleType.CORE_LIBRARY).build());
        assessment.setRequiresSeniorReview(5);
        assessment.setSeniorReviewReasons(List.of("changes the retry contract"));

        LlmScoringResponse clean = scorer.score(request(85.0), PromptContext.builder().args(args).build(), assessment(4), LlmScoringResponse.Bugs.builder().build()).getResponse();
        LlmScoringResponse response = scorer.score(request(85.0), PromptContext.builder().args(args).build(), assessment, LlmScoringResponse.Bugs.builder().build()).getResponse();

        assertEquals(args.getArchitectureSolidPenalty(), response.getQualityMultiplier().getArchitectureAnalysis().getPenaltyImpact(), 1e-9);
        assertEquals(clean.getQualityMultiplier().getFinalMultiplier() + args.getArchitectureSolidPenalty(), response.getQualityMultiplier().getFinalMultiplier(), 1e-9);
        assertEquals(LlmScoringResponse.ModuleType.CORE_LIBRARY, response.getBlastRadiusAnalysis().getModuleType());
        assertEquals(5, response.getRequiresSeniorReview());
    }
    private static LlmScoringRequest request(double coverage) {
        return LlmScoringRequest.builder()
                .changeSummary(LlmScoringRequest.ChangeSummary.builder().build())
                .codeBlockChanges(Lists.newArrayList(List.of(LlmScoringRequest.CodeBlockChange.builder()
                        .signature("io/example/Service.handle()V")
                        .file("io/example/Service.java")
                        .operation(LlmScoringRequest.Operation.MODIFY)
                        .build())))
                .coverage(LlmScoringRequest.CoverageInfo.builder().changedLineCoverage(coverage).build())
                .build();
    }
    private static LlmScoringResponse assessment(int architectureImpact) {
        return LlmScoringResponse.builder()
                .summary("moves the retry policy into the client")
                .qualityDimensions(LlmScoringResponse.QualityDimensions.builder()
                        .architectureImpact(LlmScoringResponse.DimensionScore.builder().score(architectureImpact).build())
                        .build())
                .build();
    }
}
