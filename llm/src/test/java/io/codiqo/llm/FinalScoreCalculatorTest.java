package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.ComplexityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;

class FinalScoreCalculatorTest {
    @Test
    void qualityMultiplierBelowMinIsClampedToMin() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(0.1).build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        assertEquals(args.getQualityMultiplierMin(), response.getQualityMultiplier().getFinalMultiplier(), 0.001,
                "LLM-returned quality multiplier below qualityMultiplierMin must be clamped up to the floor");
    }
    @Test
    void qualityMultiplierAboveMaxIsClampedToMax() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(5.0).build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        assertEquals(args.getQualityMultiplierMax(), response.getQualityMultiplier().getFinalMultiplier(), 0.001,
                "LLM-returned quality multiplier above qualityMultiplierMax must be clamped down to the ceiling");
    }
    @Test
    void qualityMultiplierWithinRangeIsPassedThrough() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(0.9).build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        assertEquals(0.9, response.getQualityMultiplier().getFinalMultiplier(), 0.001,
                "LLM-returned quality multiplier inside the clamp range must be preserved exactly");
    }
    @Test
    void missingQualityMultiplierDefaultsToNeutralClampedValue() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        assertNotNull(response.getQualityMultiplier(),
                "FinalScoreCalculator must synthesize a QualityMultiplier when the LLM omits it");
        assertEquals(1.0, response.getQualityMultiplier().getFinalMultiplier(), 0.001,
                "a missing LLM quality multiplier defaults to 1.0 (neutral) and remains within [min,max]");
    }
    @Test
    void complexityMultiplierFromLlmRescalesBaseEffort() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setEffortBreakdown(EffortBreakdown.builder()
                .complexityMultiplier(ComplexityMultiplier.builder().combinedMultiplier(1.2).build())
                .build());
        PreComputedScores preComputed = baseScores(/*volumeScore*/ 200.0);

        calculator.apply(response, preComputed);

        assertEquals(200.0 * 1.2, response.getEffortBreakdown().getBaseEffortScore(), 0.01,
                "when the LLM returns a complexity multiplier, baseEffort = volumeScore × combinedMultiplier");
    }
    @Test
    void complexityMultiplierZeroFromLlmDoesNotRescaleBaseEffort() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setEffortBreakdown(EffortBreakdown.builder()
                .complexityMultiplier(ComplexityMultiplier.builder().combinedMultiplier(0.0).build())
                .build());
        PreComputedScores preComputed = baseScores(200.0);

        calculator.apply(response, preComputed);

        assertEquals(preComputed.getBaseEffort(), response.getEffortBreakdown().getBaseEffortScore(), 0.01,
                "LLM-returned combinedMultiplier=0 is treated as 'not provided' — pre-computed baseEffort stands");
    }
    @Test
    void missingComplexityMultiplierKeepsPreComputedBaseEffort() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setEffortBreakdown(EffortBreakdown.builder().build());
        PreComputedScores preComputed = baseScores(150.0);

        calculator.apply(response, preComputed);

        assertEquals(preComputed.getBaseEffort(), response.getEffortBreakdown().getBaseEffortScore(), 0.01,
                "no complexity multiplier → baseEffort stays as pre-computed volume score (neutral 1.0×)");
    }
    @Test
    void architectureBonusFormulaIsImpactTimesBaseEffortTimesFactorTimesQualityFactor() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(1.0).build());
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(5)
                .qualityFactor(0.8)
                .build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        double expectedBonus = 5 * 100.0 * args.getArchitectureBonusFactor() * 0.8;
        assertEquals(expectedBonus, response.getArchitectureEffortBonus().getBonusPoints(), 0.01,
                "architectureBonus = impactScore × baseEffort × architectureBonusFactor × qualityFactor");
    }
    @Test
    void architectureBonusQualityFactorIsClampedToZeroOneRange() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(5)
                .qualityFactor(2.0)
                .build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        assertEquals(1.0, response.getArchitectureEffortBonus().getQualityFactor(), 0.001,
                "qualityFactor > 1.0 must be clamped to 1.0 before use in the architecture bonus formula");
    }
    @Test
    void zeroArchitectureImpactProducesZeroBonus() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(0)
                .qualityFactor(1.0)
                .build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        assertEquals(0.0, response.getArchitectureEffortBonus().getBonusPoints(), 0.001,
                "zero architecture impact should produce zero bonus regardless of other factors");
    }
    @Test
    void finalScoreCombinesBaseEffortQualityAndArchitectureBonus() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(1.0).build());
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(3)
                .qualityFactor(1.0)
                .build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        double archBonus = 3 * 100.0 * args.getArchitectureBonusFactor() * 1.0;
        double expected = Math.round(100.0 * 1.0 + archBonus);
        assertEquals(expected, response.getScore(), 0.001,
                "finalScore = round(baseEffort × qualityMultiplier + architectureBonus)");
    }
    @Test
    void finalScoreIntegratesComplexityMultiplierFromLlm() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setEffortBreakdown(EffortBreakdown.builder()
                .complexityMultiplier(ComplexityMultiplier.builder().combinedMultiplier(1.3).build())
                .build());
        response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(1.1).build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        double expectedBase = 100.0 * 1.3;
        double expectedFinal = Math.round(expectedBase * 1.1);
        assertTrue(Math.abs(response.getScore() - expectedFinal) <= 1.0,
                "finalScore should reflect both the LLM complexity multiplier and the LLM quality multiplier");
    }

    private static PreComputedScores baseScores(double volumeAndBaseEffort) {
        return PreComputedScores.builder()
                .volumeScore(volumeAndBaseEffort)
                .baseEffort(volumeAndBaseEffort)
                .build();
    }
}
