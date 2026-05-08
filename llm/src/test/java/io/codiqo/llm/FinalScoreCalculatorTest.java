package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.common.collect.Lists;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.CodeBlockEffort;
import io.codiqo.llm.VolumeScoreCalculator.FileEffort;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.Operation;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.ComplexityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.DiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.FileDiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.LineGroups;
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

    @Test
    void diffClassificationAbsentLeavesVolumeScoreUntouched() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setEffortBreakdown(EffortBreakdown.builder().build());
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", /*blockEffort*/ 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 0);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "no diffClassification → blockEffortSum equals pre-computed");
        assertEquals(preComputed.getVolumeScore(), vs.getTotalVolumeScore(), 0.01,
                "no diffClassification → totalVolumeScore equals pre-computed");
        assertEquals(0, vs.getCosmeticLinesDropped(), "mirror counters stay zero when classification not provided");
        assertEquals(0, vs.getInPlaceLinesCollapsed());
    }
    @Test
    void allCosmeticClassificationCollapsesFileBlockEffortToZero() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", /*blockEffort*/ 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 3, /*deleted*/ 3);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 3, 3, /*cosmetic*/ 6, /*inPlace*/ 0, /*trueDeleteAdd*/ 0));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(0.0, vs.getBlockEffortSum(), 0.01,
                "all cosmetic → MODIFY block effort scaled to 0 → blockEffortSum is 0");
        assertEquals(0.0, vs.getTotalVolumeScore(), 0.01, "totalVolumeScore must collapse along with block effort");
        assertEquals(6, vs.getLinesChangedRaw(), "raw lines = added + deleted = 6");
        assertEquals(0, vs.getLinesChangedAdjusted(), "all 6 lines were cosmetic → adjusted 0");
        assertEquals(6, vs.getCosmeticLinesDropped());
        assertEquals(0, vs.getInPlaceLinesCollapsed());
    }
    @Test
    void allInPlaceClassificationHalvesFileBlockEffort() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", /*blockEffort*/ 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 4, /*deleted*/ 4);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 4, 4, /*cosmetic*/ 0, /*inPlace*/ 8, /*trueDeleteAdd*/ 0));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(50.0, vs.getBlockEffortSum(), 0.01,
                "all in-place → file effort collapses 2 lines to 1 → factor 0.5 → block effort halves");
        assertEquals(8, vs.getLinesChangedRaw());
        assertEquals(4, vs.getLinesChangedAdjusted(), "8 raw lines → 4 effective after pair collapse");
        assertEquals(0, vs.getCosmeticLinesDropped());
        assertEquals(4, vs.getInPlaceLinesCollapsed(), "4 pairs collapsed (8 / 2)");
    }
    @Test
    void diffClassificationCountsThatDoNotSumFallBackToPreComputedScore() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 5);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 5, 5, /*cosmetic*/ 1, /*inPlace*/ 1, /*trueDeleteAdd*/ 1));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "malformed counts (1+1+1=3 ≠ added+deleted=10) → fall back to pre-computed");
        assertEquals(preComputed.getVolumeScore(), vs.getTotalVolumeScore(), 0.01);
        assertEquals(10, vs.getLinesChangedRaw(), "fallback path still records raw lines from the LLM-classified arrays");
        assertEquals(1, vs.getCosmeticLinesDropped(),
                "fallback path still records LLM's per-file scalars even when global scaling is not applied");
    }
    @Test
    void onlyUnknownFilesInClassificationFallsBackToPreComputedScore() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", 3, 3);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Bar.java", 3, 3, /*cosmetic*/ 6, /*inPlace*/ 0, /*trueDeleteAdd*/ 0));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "Bar.java is not in the request → entry skipped, perFileFactor empty → fall back");
        assertEquals(0, vs.getCosmeticLinesDropped(), "no reduction was applied");
    }
    @Test
    void nonEligibleFileEntryIsSkippedWhileEligibleEntriesStillApply() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChanges(
                fileChange("Foo.java", /*added*/ 5, /*deleted*/ 5, /*justify*/ true),
                fileChange("pom.xml", /*added*/ 6, /*deleted*/ 4, /*justify*/ false));
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 5, 5, /*cosmetic*/ 0, /*inPlace*/ 4, /*trueDeleteAdd*/ 6),
                fileClassification("pom.xml", 6, 4, /*cosmetic*/ 0, /*inPlace*/ 0, /*trueDeleteAdd*/ 10));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(80.0, vs.getBlockEffortSum(), 0.01,
                "pom.xml entry skipped (linesJustificationRequired=false); Foo.java factor = (4/2 + 6) / 10 = 0.8 → 80.0");
        assertEquals(10, vs.getLinesChangedRaw(), "raw lines counted only for eligible Foo.java entry");
        assertEquals(8, vs.getLinesChangedAdjusted());
        assertEquals(2, vs.getInPlaceLinesCollapsed());
    }
    @Test
    void unknownFileEntryIsSkippedWhileValidEntriesStillApply() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 5);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 5, 5, /*cosmetic*/ 0, /*inPlace*/ 4, /*trueDeleteAdd*/ 6),
                fileClassification("Bar.java", 1, 0, /*cosmetic*/ 1, /*inPlace*/ 0, /*trueDeleteAdd*/ 0));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(80.0, vs.getBlockEffortSum(), 0.01,
                "Foo.java factor = (4/2 + 6) / 10 = 0.8 → 100 × 0.8 = 80; Bar.java unknown entry was skipped, not fatal");
        assertEquals(10, vs.getLinesChangedRaw(), "raw lines counted only for valid Foo.java entry");
        assertEquals(8, vs.getLinesChangedAdjusted(), "adjusted = 4/2 + 6 = 8 for Foo.java");
        assertEquals(2, vs.getInPlaceLinesCollapsed(), "Foo.java collapsed 2 pairs");
    }

    @Test
    void cosmeticAddedLinesScaleNewBlockEffort() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", /*blockEffort*/ 100.0, Operation.NEW);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 10, /*deleted*/ 0);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 10, 0, /*cosmetic*/ 4, /*inPlace*/ 0, /*trueDeleteAdd*/ 6));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(60.0, vs.getBlockEffortSum(), 0.01,
                "pure-addition NEW block with 4/10 cosmetic lines → factor 0.6 → 60% of effort");
        assertEquals(10, vs.getLinesChangedRaw());
        assertEquals(6, vs.getLinesChangedAdjusted());
        assertEquals(4, vs.getCosmeticLinesDropped());
        assertEquals(0, vs.getInPlaceLinesCollapsed());
    }
    @Test
    void inPlaceModifyLinesExceedingMaxPairsFallsBack() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 1);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 5, 1, /*cosmetic*/ 0, /*inPlace*/ 4, /*trueDeleteAdd*/ 2));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "inPlace=4 > 2×min(5,1)=2 → bogus pairing → fall back to pre-computed");
    }
    @Test
    void oddInPlaceModifyLinesFallsBack() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", 4, 4);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 4, 4, /*cosmetic*/ 0, /*inPlace*/ 3, /*trueDeleteAdd*/ 5));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "inPlace=3 is odd (each pair contributes 2) → fall back to pre-computed");
    }

    @Test
    void serverDerivesCountFieldsFromLineGroups() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 4, /*deleted*/ 4);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .added(LineGroups.builder()
                        .cosmetic(Lists.newArrayList(12))
                        .inPlaceModify(Lists.newArrayList(10, 11))
                        .trueDeleteAdd(Lists.newArrayList(13))
                        .build())
                .deleted(LineGroups.builder()
                        .cosmetic(Lists.newArrayList(7))
                        .inPlaceModify(Lists.newArrayList(8, 9))
                        .trueDeleteAdd(Lists.newArrayList(6))
                        .build())
                .cosmeticLines(999)
                .inPlaceModifyLines(999)
                .trueDeleteAddLines(999)
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        FileDiffClassification mutated = response.getEffortBreakdown().getDiffClassification().getPerFile().get(0);
        assertEquals(2, mutated.getCosmeticLines(), "server overwrites LLM-supplied cosmeticLines with derived count (1 added + 1 deleted)");
        assertEquals(4, mutated.getInPlaceModifyLines(), "server overwrites with derived count (2 added + 2 deleted)");
        assertEquals(2, mutated.getTrueDeleteAddLines(), "server overwrites with derived count (1 added + 1 deleted)");

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(50.0, vs.getBlockEffortSum(), 0.01,
                "factor = (4/2 + 2) / 8 = 0.5 → blockEffortSum 100 × 0.5 = 50");
        assertEquals(2, vs.getInPlaceLinesCollapsed());
    }
    @Test
    void mismatchedAddedArraySizeFallsBackToPreComputed() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 5);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .added(LineGroups.builder().trueDeleteAdd(Lists.newArrayList(1)).build())
                .deleted(LineGroups.builder().trueDeleteAdd(Lists.newArrayList(1, 2, 3, 4, 5)).build())
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "added line groups sum to 1 but linesAdded=5 → fallback to pre-computed");
    }
    @Test
    void inPlacePairFullyInsideBlockRangeCollapsesEffectiveLinesChanged() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithBlockBody("Foo.java", /*blockEffort*/ 100.0,
                /*effLinesChanged*/ 2, /*bodyStart*/ 55, /*bodyEnd*/ 67, /*bodyCodeLines*/ 13);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 1, /*deleted*/ 1);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .added(LineGroups.builder().inPlaceModify(Lists.newArrayList(58)).build())
                .deleted(LineGroups.builder().inPlaceModify(Lists.newArrayList(58)).build())
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.CodeBlockEffortView block = response.getEffortBreakdown().getFileEfforts().get(0).getCodeBlockEfforts().get(0);
        assertEquals(1, block.getEffectiveLinesChanged(),
                "1 in-place pair fully inside block range collapses effectiveLinesChanged 2 → 1");
        assertEquals(0.08, block.getChangeRatio(), 0.01,
                "changeRatio recomputed from collapsed effectiveLinesChanged: 1/13 ≈ 0.08");
    }
    @Test
    void inPlacePairWithDeletedSideOutsideBlockRangeDoesNotCollapse() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithBlockBody("Foo.java", /*blockEffort*/ 100.0,
                /*effLinesChanged*/ 2, /*bodyStart*/ 55, /*bodyEnd*/ 67, /*bodyCodeLines*/ 13);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 1, /*deleted*/ 1);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .added(LineGroups.builder().inPlaceModify(Lists.newArrayList(58)).build())
                .deleted(LineGroups.builder().inPlaceModify(Lists.newArrayList(120)).build())
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.CodeBlockEffortView block = response.getEffortBreakdown().getFileEfforts().get(0).getCodeBlockEfforts().get(0);
        assertEquals(2, block.getEffectiveLinesChanged(),
                "deleted side outside block range → pair not fully in block → no collapse");
    }
    @Test
    void perFileScalarsArePopulatedEvenWhenGlobalAdjustmentFallsBack() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 5);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .added(LineGroups.builder()
                        .cosmetic(Lists.newArrayList(10))
                        .inPlaceModify(Lists.newArrayList(11, 12))
                        .trueDeleteAdd(Lists.newArrayList(13))
                        .build())
                .deleted(LineGroups.builder()
                        .cosmetic(Lists.newArrayList(20))
                        .inPlaceModify(Lists.newArrayList(21))
                        .trueDeleteAdd(Lists.newArrayList(22, 23, 24))
                        .build())
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        FileDiffClassification mutated = response.getEffortBreakdown().getDiffClassification().getPerFile().get(0);
        assertEquals(2, mutated.getCosmeticLines(),
                "per-file cosmeticLines populated from arrays even when global fallback fires (1 added + 1 deleted)");
        assertEquals(3, mutated.getInPlaceModifyLines(),
                "per-file inPlaceModifyLines populated from arrays even when global fallback fires (2 added + 1 deleted)");
        assertEquals(4, mutated.getTrueDeleteAddLines(),
                "per-file trueDeleteAddLines populated from arrays even when global fallback fires (1 added + 3 deleted)");
        assertEquals(2, response.getEffortBreakdown().getDiffClassification().getCosmeticLines(),
                "aggregate cosmeticLines mirrors the sum of per-file scalars even on fallback");
        assertEquals(3, response.getEffortBreakdown().getDiffClassification().getInPlaceModifyLines(),
                "aggregate inPlaceModifyLines mirrors the sum of per-file scalars even on fallback");
        assertEquals(4, response.getEffortBreakdown().getDiffClassification().getTrueDeleteAddLines(),
                "aggregate trueDeleteAddLines mirrors the sum of per-file scalars even on fallback");
    }
    @Test
    void volumeScoreLineCountsArePopulatedEvenWhenGlobalAdjustmentFallsBack() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 5);
        LlmScoringResponse response = responseWithClassification(
                fileClassification("Foo.java", 5, 5, /*cosmetic*/ 1, /*inPlace*/ 1, /*trueDeleteAdd*/ 1));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "global scaling did not apply (counts don't sum) — block effort stays at pre-computed");
        assertEquals(10, vs.getLinesChangedRaw(),
                "linesChangedRaw populated from request fileChanges even when global fallback fires");
        assertEquals(0, vs.getInPlaceLinesCollapsed(),
                "no valid pairs (addedInPlace=1, deletedInPlace=0) → 0 collapsed pairs");
    }

    @Test
    void unbalancedInPlaceArraysFallBackToPreComputed() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 3, /*deleted*/ 3);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .added(LineGroups.builder()
                        .inPlaceModify(Lists.newArrayList(1, 2))
                        .trueDeleteAdd(Lists.newArrayList(3))
                        .build())
                .deleted(LineGroups.builder()
                        .inPlaceModify(Lists.newArrayList(1))
                        .trueDeleteAdd(Lists.newArrayList(2, 3))
                        .build())
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "added side has 2 IN_PLACE_MODIFY but deleted side has 1 → unbalanced pairs → fallback");
    }

    private static PreComputedScores baseScores(double volumeAndBaseEffort) {
        return PreComputedScores.builder()
                .volumeScore(volumeAndBaseEffort)
                .baseEffort(volumeAndBaseEffort)
                .build();
    }
    private static PreComputedScores scoresWithFileEffort(String file, double blockEffort) {
        return scoresWithFileEffort(file, blockEffort, Operation.MODIFY);
    }
    private static PreComputedScores scoresWithFileEffort(String file, double blockEffort, Operation operation) {
        return scoresWithBlock(file, blockEffort, operation,
                /*effLinesChanged*/ 6, /*bodyStart*/ 1, /*bodyEnd*/ 20, /*bodyCodeLines*/ 20);
    }
    private static PreComputedScores scoresWithBlockBody(String file, double blockEffort,
            int effLinesChanged, int bodyStart, int bodyEnd, int bodyCodeLines) {
        return scoresWithBlock(file, blockEffort, Operation.MODIFY, effLinesChanged, bodyStart, bodyEnd, bodyCodeLines);
    }
    private static PreComputedScores scoresWithBlock(String file, double blockEffort, Operation operation,
            int effLinesChanged, int bodyStart, int bodyEnd, int bodyCodeLines) {
        CodeBlockEffort cbe = new CodeBlockEffort(file, "doStuff", "doStuff()",
                operation,
                /*ncss*/ 10, /*invocations*/ 5, /*effInvocsChanged*/ 0,
                /*nonCommentCodeLines*/ 20, /*commentLines*/ 0, effLinesChanged,
                /*changeRatio*/ 0.3, /*scaledLines*/ 6.0, /*scaledNcss*/ 0.0, /*scaledInvocations*/ 0.0,
                /*driverScore*/ blockEffort, /*cappedStatements*/ (int) blockEffort,
                /*effort*/ blockEffort, /*bucketBaseline*/ 1000.0, /*isTest*/ false,
                /*deviationNcss*/ 0.0, /*deviationInvocations*/ 0.0, /*ratioOutlier*/ false,
                /*effortShare*/ 1.0, /*globalCapDriver*/ false,
                bodyStart, bodyEnd, bodyCodeLines);
        FileEffort fileEffort = new FileEffort(file, blockEffort, false, List.of(cbe), 0, 0, 0.0, 0.0, false);

        double volumeExponent = 1.0;
        double filesScopeMultiplier = 1.0;
        double volumeScore = Math.pow(blockEffort, volumeExponent) * filesScopeMultiplier;
        return PreComputedScores.builder()
                .blockEffortSum(blockEffort)
                .totalEffortRaw(blockEffort)
                .totalBaseline(1000.0)
                .globalCap(10000.0)
                .filesScopeMultiplier(filesScopeMultiplier)
                .volumeScore(volumeScore)
                .baseEffort(volumeScore)
                .codeBlockEfforts(Lists.newArrayList(cbe))
                .fileEfforts(Lists.newArrayList(fileEffort))
                .build();
    }
    private static LlmScoringRequest requestWithFileChange(String file, int linesAdded, int linesDeleted) {
        return requestWithFileChange(file, linesAdded, linesDeleted, /*linesJustificationRequired*/ true);
    }
    private static LlmScoringRequest requestWithFileChange(String file, int linesAdded, int linesDeleted, boolean linesJustificationRequired) {
        FileChange fileChange = FileChange.builder()
                .path(file)
                .linesAdded(linesAdded)
                .linesDeleted(linesDeleted)
                .linesJustificationRequired(linesJustificationRequired)
                .build();
        return LlmScoringRequest.builder().fileChanges(Lists.newArrayList(fileChange)).build();
    }
    private static LlmScoringRequest requestWithFileChanges(FileChange... fileChanges) {
        return LlmScoringRequest.builder().fileChanges(Lists.newArrayList(fileChanges)).build();
    }
    private static FileChange fileChange(String file, int linesAdded, int linesDeleted, boolean linesJustificationRequired) {
        return FileChange.builder()
                .path(file)
                .linesAdded(linesAdded)
                .linesDeleted(linesDeleted)
                .linesJustificationRequired(linesJustificationRequired)
                .build();
    }
    private static FileDiffClassification fileClassification(String file, int linesAdded, int linesDeleted, int cosmetic, int inPlace, int trueDeleteAdd) {
        int addedInPlace = Math.min((inPlace + 1) / 2, linesAdded);
        int deletedInPlace = Math.min(inPlace - addedInPlace, linesDeleted);

        int addedSlots = Math.max(0, linesAdded - addedInPlace);
        int deletedSlots = Math.max(0, linesDeleted - deletedInPlace);

        int addedCosmetic = Math.min(cosmetic, addedSlots);
        int deletedCosmetic = Math.min(cosmetic - addedCosmetic, deletedSlots);
        addedSlots -= addedCosmetic;
        deletedSlots -= deletedCosmetic;

        int addedTrue = Math.min(trueDeleteAdd, addedSlots);
        int deletedTrue = Math.min(trueDeleteAdd - addedTrue, deletedSlots);

        return FileDiffClassification.builder()
                .file(file)
                .added(buildLineGroups(addedInPlace, addedCosmetic, addedTrue, /*startLine*/ 1))
                .deleted(buildLineGroups(deletedInPlace, deletedCosmetic, deletedTrue, /*startLine*/ 101))
                .build();
    }
    private static LineGroups buildLineGroups(int inPlaceCount, int cosmeticCount, int trueCount, int startLine) {
        List<Integer> inPlace = Lists.newArrayListWithExpectedSize(inPlaceCount);
        List<Integer> cosmetic = Lists.newArrayListWithExpectedSize(cosmeticCount);
        List<Integer> trueLines = Lists.newArrayListWithExpectedSize(trueCount);
        int line = startLine;
        for (int i = 0; i < inPlaceCount; i++) {
            inPlace.add(line++);
        }
        for (int i = 0; i < cosmeticCount; i++) {
            cosmetic.add(line++);
        }
        for (int i = 0; i < trueCount; i++) {
            trueLines.add(line++);
        }
        return LineGroups.builder()
                .inPlaceModify(inPlace)
                .cosmetic(cosmetic)
                .trueDeleteAdd(trueLines)
                .build();
    }
    private static LlmScoringResponse responseWithClassification(FileDiffClassification... perFile) {
        DiffClassification classification = DiffClassification.builder()
                .perFile(Lists.newArrayList(perFile))
                .build();
        LlmScoringResponse response = new LlmScoringResponse();
        response.setEffortBreakdown(EffortBreakdown.builder().diffClassification(classification).build());
        return response;
    }
}
