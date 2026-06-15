package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.IneffectiveLineProfile;
import io.codiqo.llm.VolumeScoreCalculator.CodeBlockEffort;
import io.codiqo.llm.VolumeScoreCalculator.FileEffort;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.Operation;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.ComplexityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.DiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.FileDiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.LinePair;
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
                "in-range LLM multiplier is preserved");
    }
    @Test
    void qualityMultiplierMissingDefaultsToOne() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        assertNotNull(response.getQualityMultiplier(), "missing qualityMultiplier replaced by default");
        assertEquals(1.0, response.getQualityMultiplier().getFinalMultiplier(), 0.001);
    }
    @Test
    void architectureBonusComputedFromImpactAndQualityFactor() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(7)
                .qualityFactor(0.5)
                .build());
        response.setEffortBreakdown(EffortBreakdown.builder()
                .volumeScore(LlmScoringResponse.VolumeScore.builder().build())
                .complexityMultiplier(ComplexityMultiplier.builder().combinedMultiplier(1.0).build())
                .build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        double expected = 7 * 100.0 * args.getArchitectureBonusFactor() * 0.5;
        assertEquals(expected, response.getArchitectureEffortBonus().getBonusPoints(), 0.05);
    }
    @Test
    void architectureImpactScoreIsClampedToZeroTenRange() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(50)
                .qualityFactor(1.0)
                .build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        double expected = 10 * 100.0 * args.getArchitectureBonusFactor() * 1.0;
        assertEquals(expected, response.getArchitectureEffortBonus().getBonusPoints(), 0.05,
                "hallucinated impact score 50 must be clamped to 10 before entering the bonus formula");
    }
    @Test
    void negativeArchitectureImpactScoreIsClampedToZero() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        LlmScoringResponse response = new LlmScoringResponse();
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(-3)
                .qualityFactor(1.0)
                .build());
        PreComputedScores preComputed = baseScores(100.0);

        calculator.apply(response, preComputed);

        assertEquals(0.0, response.getArchitectureEffortBonus().getBonusPoints(), 0.001,
                "negative impact score must be clamped to 0 → zero bonus");
    }

    @Test
    void allCosmeticClassificationDropsBlockEffortToZero() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 3, /*deleted*/ 3);
        LlmScoringResponse response = responseWithClassification(
                cosmeticOnly("Foo.java", /*addedLines*/ list(1, 2, 3), /*deletedLines*/ list(11, 12, 13)));

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(0.0, vs.getBlockEffortSum(), 0.01,
                "all cosmetic → file factor 0 → MODIFY block effort scaled to 0");
        assertEquals(0.0, vs.getTotalVolumeScore(), 0.01);
        assertEquals(6, vs.getLinesChangedRaw(), "raw lines = added + deleted = 6");
        assertEquals(0, vs.getLinesChangedAdjusted(), "all 6 lines were cosmetic → 0 effective");
        assertEquals(6, vs.getCosmeticLinesDropped());
        assertEquals(0, vs.getInPlaceLinesCollapsed());
    }
    @Test
    void allInPlacePairsHalveFileBlockEffort() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 4, /*deleted*/ 4);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .inPlaceModifyPairs(pairs(p(1, 11), p(2, 12), p(3, 13), p(4, 14)))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(50.0, vs.getBlockEffortSum(), 0.01,
                "4 in-place pairs (8 raw → 4 effective) → factor 0.5 → 50.0");
        assertEquals(8, vs.getLinesChangedRaw());
        assertEquals(4, vs.getLinesChangedAdjusted(), "4 pairs of effort each");
        assertEquals(0, vs.getCosmeticLinesDropped());
        assertEquals(4, vs.getInPlaceLinesCollapsed(), "4 pairs collapsed");
    }
    @Test
    void configFileInPlaceVersionBumpCollapsesAndStaysDiscounted() {
        RunArgs args = new RunArgs();
        FileChange pom = FileChange.builder()
                .path("pom.xml").isConfig(true).linesJustificationRequired(true)
                .lineProfile(IneffectiveLineProfile.XML).linesAdded(4).linesDeleted(4).build();
        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().totalFilesChanged(1).build())
                .codeBlockChanges(List.of())
                .fileChanges(List.of(pom))
                .build();
        PreComputedScores preComputed = new VolumeScoreCalculator(args).calculate(request, 1000, 100, 0, 0, 0, 0);

        // 4 in-place pairs: a <version> bump reads as 1 delete + 1 add but is one logical change
        LlmScoringResponse response = responseWithClassification(FileDiffClassification.builder()
                .file("pom.xml")
                .inPlaceModifyPairs(pairs(p(1, 11), p(2, 12), p(3, 13), p(4, 14)))
                .build());

        new FinalScoreCalculator(args).apply(response, preComputed, request);

        // 4 in-place pairs = 4 logical changed lines, each discounted by configFileScoreMultiplier.
        // The delete+add collapse must happen exactly once (not twice → would be 2 logical lines).
        double expectedFourLogicalLines = 4 * preComputed.getModifyMult() * args.getConfigFileScoreMultiplier();
        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(expectedFourLogicalLines, vs.getBlockEffortSum(), 0.01,
                "4 in-place pairs must score as 4 logical lines, not 2 — no double collapse");
        assertEquals(8, vs.getLinesChangedRaw());
        assertEquals(4, vs.getLinesChangedAdjusted());
        assertEquals(4, vs.getInPlaceLinesCollapsed());
    }
    @Test
    void trueModifyPairsCostSameAsInPlacePairs() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 4, /*deleted*/ 4);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .trueModifyPairs(pairs(p(1, 11), p(2, 12), p(3, 13), p(4, 14)))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(50.0, vs.getBlockEffortSum(), 0.01,
                "4 trueModify pairs cost 1 each (same as in-place) → factor 0.5 → 50.0");
        assertEquals(4, vs.getInPlaceLinesCollapsed(),
                "trueModify pairs count toward the same 'pairs collapsed' bookkeeping as in-place");
    }
    @Test
    void pureAddCountsOneEffortPerLineNoReduction() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0, Operation.NEW);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 10, /*deleted*/ 0);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .pureAdd(list(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(100.0, vs.getBlockEffortSum(), 0.01,
                "10 pureAdd / 10 raw = factor 1.0 → no reduction");
        assertEquals(10, vs.getLinesChangedAdjusted(), "all 10 lines kept");
        assertEquals(0, vs.getCosmeticLinesDropped());
        assertEquals(0, vs.getInPlaceLinesCollapsed());
    }
    @Test
    void pureAddWithCosmeticTrims() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0, Operation.NEW);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 10, /*deleted*/ 0);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticAdded(list(1, 2, 3, 4))
                .pureAdd(list(5, 6, 7, 8, 9, 10))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(60.0, vs.getBlockEffortSum(), 0.01,
                "4 cosmetic + 6 pureAdd / 10 raw → factor 0.6");
        assertEquals(6, vs.getLinesChangedAdjusted());
        assertEquals(4, vs.getCosmeticLinesDropped());
    }
    @Test
    void mixedAllFiveCategoriesComputesCorrectFactor() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 6, /*deleted*/ 4);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticAdded(list(1))
                .cosmeticDeleted(list(11))
                .inPlaceModifyPairs(pairs(p(12, 2), p(13, 3)))
                .trueModifyPairs(pairs(p(14, 4)))
                .pureAdd(list(5, 6))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        // added: cosmetic 1 + inPlace 2 + trueModify 1 + pureAdd 2 = 6 ✓
        // deleted: cosmetic 1 + inPlace 2 + trueModify 1 + pureDelete 0 = 4 ✓
        // effective = 2 + 1 + 2 + 0 = 5; raw = 6+4 = 10; factor = 0.5
        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(50.0, vs.getBlockEffortSum(), 0.01, "factor 5/10 = 0.5 → blockEffortSum 50");
        assertEquals(10, vs.getLinesChangedRaw());
        assertEquals(5, vs.getLinesChangedAdjusted());
        assertEquals(2, vs.getCosmeticLinesDropped(), "1 added + 1 deleted = 2 cosmetic");
        assertEquals(3, vs.getInPlaceLinesCollapsed(), "2 in-place + 1 trueModify = 3 collapsed pairs");
        assertTrue(vs.getLinesChangedAdjusted() <= vs.getLinesChangedRaw(),
                "invariant: adjusted ≤ raw must always hold on success");
    }
    @Test
    void serverDerivesCountFieldsFromArrays() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 4, /*deleted*/ 4);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticAdded(list(12))
                .cosmeticDeleted(list(7))
                .inPlaceModifyPairs(pairs(p(8, 10), p(9, 11)))
                .pureAdd(list(13))
                .pureDelete(list(6))
                .cosmeticLines(999)
                .pairsCollapsed(999)
                .pureAddDeleteLines(999)
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        FileDiffClassification mutated = response.getEffortBreakdown().getDiffClassification().getPerFile().get(0);
        assertEquals(2, mutated.getCosmeticLines(), "server overwrites with 1 cosmeticAdded + 1 cosmeticDeleted");
        assertEquals(2, mutated.getPairsCollapsed(), "2 in-place pairs (0 trueModify)");
        assertEquals(2, mutated.getPureAddDeleteLines(), "1 pureAdd + 1 pureDelete");
    }
    @Test
    void addedTotalMismatchFallsBack() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 5);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .pureAdd(list(1, 2, 3)) // 3 ≠ linesAdded=5
                .pureDelete(list(11, 12, 13, 14, 15))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "addedTotal (3) ≠ linesAdded (5) → fallback, no reduction applied");
        assertEquals(0, vs.getLinesChangedRaw(), "on fallback, bookkeeping is zero (no validated files)");
        assertEquals(0, vs.getLinesChangedAdjusted());
    }
    @Test
    void deletedTotalMismatchFallsBack() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 3, /*deleted*/ 3);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .pureAdd(list(1, 2, 3))
                .pureDelete(list(11)) // 1 ≠ linesDeleted=3
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "deletedTotal (1) ≠ linesDeleted (3) → fallback");
    }
    @Test
    void mismatchedTotalsSkipFileWithoutMutatingArrays() {
        // Totals can only mismatch the per-file targets when derivation was impossible (no diff
        // stored) or candidate filtering drifted. The file is skipped WITHOUT mutating any array —
        // the persisted classification stays the audit trail of what was produced.
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 2);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .trueModifyPairs(buildPairs(4))
                .pureAdd(list(1, 2, 3))
                .pureDelete(list(11))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "addedTotal (7) ≠ linesAdded (5) → file skipped → no reduction");
        FileDiffClassification persisted = response.getEffortBreakdown().getDiffClassification().getPerFile().get(0);
        assertEquals(3, persisted.getPureAdd().size(), "skipped file's pureAdd must stay untouched");
        assertEquals(1, persisted.getPureDelete().size(), "skipped file's pureDelete must stay untouched");
    }
    @Test
    void underCountStillFallsBackNotTrimmed() {
        // Target: linesAdded=5, linesDeleted=5 but only 2 added lines classified (no diff stored,
        // so derivation could not rebuild the entry). Hard reject — no reduction.
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 5, /*deleted*/ 5);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .pureAdd(list(1, 2))
                .pureDelete(list(11, 12, 13, 14, 15))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "addedTotal (2) < linesAdded (5) → hard reject, no reduction");
        assertEquals(0, vs.getLinesChangedRaw(), "on fallback, bookkeeping zero");
        assertEquals(0, vs.getLinesChangedAdjusted());
    }
    @Test
    void onlyUnknownFilesInClassificationFallsBack() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", 3, 3);
        FileDiffClassification unknown = FileDiffClassification.builder()
                .file("Bar.java")
                .cosmeticAdded(list(1, 2, 3))
                .cosmeticDeleted(list(11, 12, 13))
                .build();
        LlmScoringResponse response = responseWithClassification(unknown);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(preComputed.getBlockEffortSum(), vs.getBlockEffortSum(), 0.01,
                "Bar.java not in request → entry skipped, perFileFactor empty → no reduction");
    }
    @Test
    void nonEligibleFileEntryIsSkippedWhileEligibleEntriesStillApply() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChanges(
                fileChange("Foo.java", /*added*/ 5, /*deleted*/ 5, /*justify*/ true),
                fileChange("pom.xml", /*added*/ 6, /*deleted*/ 4, /*justify*/ false));
        FileDiffClassification foo = FileDiffClassification.builder()
                .file("Foo.java")
                .inPlaceModifyPairs(pairs(p(11, 1), p(12, 2)))
                .pureAdd(list(3, 4, 5))
                .pureDelete(list(13, 14, 15))
                .build();
        // pom.xml entry would not be eligible — server ignores it and still applies Foo.java
        FileDiffClassification pom = FileDiffClassification.builder()
                .file("pom.xml")
                .pureAdd(list(1, 2, 3, 4, 5, 6))
                .pureDelete(list(11, 12, 13, 14))
                .build();
        LlmScoringResponse response = responseWithClassification(foo, pom);

        calculator.apply(response, preComputed, request);

        // Foo.java: pairs=2, pureAdd=3, pureDelete=3 → effective = 2+3+3 = 8; raw = 10; factor = 0.8
        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertEquals(80.0, vs.getBlockEffortSum(), 0.01,
                "pom.xml skipped (not justified); Foo.java factor = 8/10 = 0.8");
        assertEquals(10, vs.getLinesChangedRaw());
        assertEquals(8, vs.getLinesChangedAdjusted());
        assertEquals(2, vs.getInPlaceLinesCollapsed());
    }

    @Test
    void inPlacePairFullyInsideBlockCollapsesEffectiveLinesChanged() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithBlockBody("Foo.java", /*blockEffort*/ 100.0,
                /*effLinesChanged*/ 2, /*bodyStart*/ 55, /*bodyEnd*/ 67, /*bodyCodeLines*/ 13);
        LlmScoringRequest request = requestWithFileChange("Foo.java", /*added*/ 1, /*deleted*/ 1);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .inPlaceModifyPairs(pairs(p(58, 58)))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.CodeBlockEffortView block = response.getEffortBreakdown().getFileEfforts().get(0).getCodeBlockEfforts().get(0);
        assertEquals(1, block.getEffectiveLinesChanged(),
                "1 in-place pair fully inside block collapses 2 → 1");
    }
    @Test
    void trueModifyPairFullyInsideBlockCollapsesEffectiveLinesChanged() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithBlockBody("Foo.java", 100.0, 2, 55, 67, 13);
        LlmScoringRequest request = requestWithFileChange("Foo.java", 1, 1);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .trueModifyPairs(pairs(p(60, 60)))
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.CodeBlockEffortView block = response.getEffortBreakdown().getFileEfforts().get(0).getCodeBlockEfforts().get(0);
        assertEquals(1, block.getEffectiveLinesChanged(),
                "trueModify pairs collapse effectiveLinesChanged the same way as in-place pairs");
    }
    @Test
    void pairWithSideOutsideBlockDoesNotCollapse() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithBlockBody("Foo.java", 100.0, 2, 55, 67, 13);
        LlmScoringRequest request = requestWithFileChange("Foo.java", 1, 1);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .inPlaceModifyPairs(pairs(p(120, 58))) // deleted side at 120 is outside [55..67]
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.CodeBlockEffortView block = response.getEffortBreakdown().getFileEfforts().get(0).getCodeBlockEfforts().get(0);
        assertEquals(2, block.getEffectiveLinesChanged(),
                "deleted side outside block range → pair not fully in block → no per-block collapse");
    }
    @Test
    void pairInsideNestedBlockCollapsesOnlyInInnermostBlock() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        CodeBlockEffort outer = blockEffort("Foo.java", "outer", /*effLinesChanged*/ 1, /*bodyStart*/ 1, /*bodyEnd*/ 50, /*bodyCodeLines*/ 50);
        CodeBlockEffort inner = blockEffort("Foo.java", "createInstance", /*effLinesChanged*/ 2, /*bodyStart*/ 10, /*bodyEnd*/ 20, /*bodyCodeLines*/ 11);
        FileEffort fileEffort = new FileEffort("Foo.java", 100.0, false, List.of(outer, inner), 0, 0, 0.0, 0.0, false);
        PreComputedScores preComputed = PreComputedScores.builder()
                .blockEffortSum(100.0)
                .totalEffortRaw(100.0)
                .totalBaseline(1000.0)
                .globalCap(10000.0)
                .filesScopeMultiplier(1.0)
                .volumeScore(100.0)
                .baseEffort(100.0)
                .codeBlockEfforts(Lists.newArrayList(outer, inner))
                .fileEfforts(Lists.newArrayList(fileEffort))
                .build();
        LlmScoringRequest request = requestWithFileChange("Foo.java", 1, 1);
        FileDiffClassification fileDiff = FileDiffClassification.builder()
                .file("Foo.java")
                .trueModifyPairs(pairs(p(15, 15))) // inside both [1..50] and the nested [10..20]
                .build();
        LlmScoringResponse response = responseWithClassification(fileDiff);

        calculator.apply(response, preComputed, request);

        List<LlmScoringResponse.CodeBlockEffortView> blocks = response.getEffortBreakdown().getFileEfforts().get(0).getCodeBlockEfforts();
        assertEquals(1, blocks.get(0).getEffectiveLinesChanged(),
                "pair belongs to the nested block — the enclosing block must not collapse it");
        assertEquals(1, blocks.get(1).getEffectiveLinesChanged(),
                "innermost block collapses the pair: 2 → 1");
    }

    @Test
    void bookkeepingInvariantAdjustedNeverExceedsRawAcrossMixedScenarios() {
        // Validated happy path: invariant holds tightly via construction.
        assertBookkeepingInvariantHolds(100.0,
                fc -> fc.file("Foo.java").inPlaceModifyPairs(pairs(p(11, 1), p(12, 2), p(13, 3))).pureAdd(list(4, 5)),
                requestWithFileChange("Foo.java", 5, 3));

        // Pure addition: factor=1.0; adjusted == raw exactly.
        assertBookkeepingInvariantHolds(100.0,
                fc -> fc.file("Foo.java").pureAdd(list(1, 2, 3, 4)),
                requestWithFileChange("Foo.java", 4, 0));

        // Fallback on duplicate: bookkeeping is zero → invariant holds (0 ≤ 0).
        assertBookkeepingInvariantHolds(100.0,
                fc -> fc.file("Foo.java")
                        .cosmeticAdded(list(1)).pureAdd(list(1))
                        .pureDelete(list(11, 12)),
                requestWithFileChange("Foo.java", 2, 2));
    }
    @Test
    void explicitNullPerFileFallsBackWithoutThrowing() {
        // Jackson calls setPerFile(null) for an explicit JSON "perFile": null, bypassing @Builder.Default
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args);
        PreComputedScores preComputed = scoresWithFileEffort("Foo.java", 100.0);
        LlmScoringRequest request = requestWithFileChange("Foo.java", 3, 3);
        LlmScoringResponse response = responseWithClassification();
        response.getEffortBreakdown().getDiffClassification().setPerFile(null);

        FinalScoreCalculator.ValidationReport report = FinalScoreCalculator.validate(response, request);
        assertFalse(report.hasFailures(), "null perFile → nothing to validate");

        calculator.apply(response, preComputed, request);
        assertEquals(preComputed.getBlockEffortSum(), response.getEffortBreakdown().getVolumeScore().getBlockEffortSum(), 0.01,
                "null perFile → no adjustment, no NPE");
    }
    @Test
    void validateAcceptsValidBlockKindsAndCosmetic() {
        LlmScoringRequest request = requestWithFileChangeAndDiff("Foo.java", 1, 2, VALIDATION_DIFF);
        LlmScoringResponse response = responseWithClassification(FileDiffClassification.builder()
                .file("Foo.java")
                .blockKinds(kinds("B1", "inPlace"))
                .cosmeticAdded(list(11))
                .cosmeticDeleted(list(13))
                .build());

        FinalScoreCalculator.ValidationReport report = FinalScoreCalculator.validate(response, request);

        assertFalse(report.hasFailures(), "block id and cosmetic numbers all exist in the diff");
    }
    @Test
    void validateReportsUnknownBlockId() {
        LlmScoringRequest request = requestWithFileChangeAndDiff("Foo.java", 1, 2, VALIDATION_DIFF);
        LlmScoringResponse response = responseWithClassification(FileDiffClassification.builder()
                .file("Foo.java")
                .blockKinds(kinds("B7", "inPlace"))
                .build());

        FinalScoreCalculator.ValidationReport report = FinalScoreCalculator.validate(response, request);

        assertTrue(report.hasFailures());
        FinalScoreCalculator.ValidationFailure failure = report.getFailures().stream()
                .filter(f -> f.getReason() == FinalScoreCalculator.FailureReason.UNKNOWN_BLOCK)
                .findFirst().orElseThrow();
        assertEquals(List.of("B7"), failure.getOffending(), "the nonexistent block id is reported");
        assertEquals(List.of("B1", "B2"), failure.getValid(), "the diff's real block ids are listed for the retry feedback");
    }
    @Test
    void validateReportsCosmeticAddedPointingAtContextLine() {
        // the a95206ac failure shape: a cited number lands on unchanged context
        LlmScoringRequest request = requestWithFileChangeAndDiff("Foo.java", 1, 2, VALIDATION_DIFF);
        LlmScoringResponse response = responseWithClassification(FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticAdded(list(12)) // new-file 12 is a context line, not a + line
                .build());

        FinalScoreCalculator.ValidationReport report = FinalScoreCalculator.validate(response, request);

        assertTrue(report.hasFailures());
        FinalScoreCalculator.ValidationFailure failure = report.getFailures().stream()
                .filter(f -> f.getReason() == FinalScoreCalculator.FailureReason.UNKNOWN_ADDED_LINE)
                .findFirst().orElseThrow();
        assertEquals(List.of("12"), failure.getOffending(), "the context-line number is reported as offending");
        assertEquals(List.of("11"), failure.getValid(), "the real candidate + numbers are listed for the retry feedback");
    }
    @Test
    void validateReportsCosmeticDeletedNotPresentInDiff() {
        LlmScoringRequest request = requestWithFileChangeAndDiff("Foo.java", 1, 2, VALIDATION_DIFF);
        LlmScoringResponse response = responseWithClassification(FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticDeleted(list(12)) // old-file 12 is a context line, not a - line
                .build());

        FinalScoreCalculator.ValidationReport report = FinalScoreCalculator.validate(response, request);

        assertTrue(report.hasFailures());
        FinalScoreCalculator.ValidationFailure failure = report.getFailures().stream()
                .filter(f -> f.getReason() == FinalScoreCalculator.FailureReason.UNKNOWN_DELETED_LINE)
                .findFirst().orElseThrow();
        assertEquals(List.of("12"), failure.getOffending());
        assertEquals(List.of("11", "13"), failure.getValid());
    }
    @Test
    void validateSkipsFileWhenDiffIsMissing() {
        // FileChange without a diff (e.g. older submissions) → nothing to validate against
        LlmScoringRequest request = requestWithFileChange("Foo.java", 1, 2);
        LlmScoringResponse response = responseWithClassification(FileDiffClassification.builder()
                .file("Foo.java")
                .blockKinds(kinds("B9", "inPlace"))
                .cosmeticAdded(list(999))
                .build());

        FinalScoreCalculator.ValidationReport report = FinalScoreCalculator.validate(response, request);

        assertFalse(report.hasFailures(), "no diff to check against → file skipped");
    }

    private static void assertBookkeepingInvariantHolds(double blockEffort,
            Function<FileDiffClassification.FileDiffClassificationBuilder, FileDiffClassification.FileDiffClassificationBuilder> shape,
            LlmScoringRequest request) {
        FinalScoreCalculator calculator = new FinalScoreCalculator(new RunArgs());
        PreComputedScores preComputed = scoresWithFileEffort(request.getFileChanges().get(0).getPath(), blockEffort);
        LlmScoringResponse response = responseWithClassification(shape.apply(FileDiffClassification.builder()).build());

        calculator.apply(response, preComputed, request);

        LlmScoringResponse.VolumeScore vs = response.getEffortBreakdown().getVolumeScore();
        assertTrue(vs.getLinesChangedAdjusted() <= vs.getLinesChangedRaw(),
                "bookkeeping invariant: adjusted (" + vs.getLinesChangedAdjusted()
                        + ") must be ≤ raw (" + vs.getLinesChangedRaw() + ")");
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
                bodyStart, bodyEnd, bodyCodeLines, /*isConfig*/ false);
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
    private static CodeBlockEffort blockEffort(String file, String name, int effLinesChanged, int bodyStart, int bodyEnd, int bodyCodeLines) {
        return new CodeBlockEffort(file, name, name + "()",
                Operation.MODIFY,
                /*ncss*/ 10, /*invocations*/ 5, /*effInvocsChanged*/ 0,
                /*nonCommentCodeLines*/ 20, /*commentLines*/ 0, effLinesChanged,
                /*changeRatio*/ 0.3, /*scaledLines*/ 6.0, /*scaledNcss*/ 0.0, /*scaledInvocations*/ 0.0,
                /*driverScore*/ 50.0, /*cappedStatements*/ 50,
                /*effort*/ 50.0, /*bucketBaseline*/ 1000.0, /*isTest*/ false,
                /*deviationNcss*/ 0.0, /*deviationInvocations*/ 0.0, /*ratioOutlier*/ false,
                /*effortShare*/ 0.5, /*globalCapDriver*/ false,
                bodyStart, bodyEnd, bodyCodeLines, /*isConfig*/ false);
    }
    // single hunk: deleted old-file lines {11, 13}, added new-file line {11}
    private static final String VALIDATION_DIFF = String.join("\n",
            "--- a/Foo.java",
            "+++ b/Foo.java",
            "@@ -10,5 +10,4 @@",
            " context",
            "-old line A",
            "+new line A",
            " context2",
            "-old line B",
            " context3");

    private static LlmScoringRequest requestWithFileChange(String file, int linesAdded, int linesDeleted) {
        return requestWithFileChange(file, linesAdded, linesDeleted, /*linesJustificationRequired*/ true);
    }
    private static LlmScoringRequest requestWithFileChangeAndDiff(String file, int linesAdded, int linesDeleted, String diff) {
        FileChange fc = fileChange(file, linesAdded, linesDeleted, /*linesJustificationRequired*/ true);
        fc.setDiff(diff);
        return requestWithFileChanges(fc);
    }
    private static LlmScoringRequest requestWithFileChange(String file, int linesAdded, int linesDeleted, boolean linesJustificationRequired) {
        return LlmScoringRequest.builder()
                .fileChanges(Lists.newArrayList(fileChange(file, linesAdded, linesDeleted, linesJustificationRequired)))
                .build();
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
    private static FileDiffClassification cosmeticOnly(String file, List<Integer> addedLines, List<Integer> deletedLines) {
        return FileDiffClassification.builder()
                .file(file)
                .cosmeticAdded(addedLines)
                .cosmeticDeleted(deletedLines)
                .build();
    }
    private static List<Integer> list(Integer... values) {
        return Lists.newArrayList(values);
    }
    private static Map<String, String> kinds(String blockId, String kind) {
        Map<String, String> toReturn = Maps.newHashMap();
        toReturn.put(blockId, kind);
        return toReturn;
    }
    private static LinePair p(int deleted, int added) {
        return LinePair.builder().deleted(deleted).added(added).build();
    }
    private static List<LinePair> pairs(LinePair... entries) {
        return Lists.newArrayList(entries);
    }
    private static List<LinePair> buildPairs(int count) {
        List<LinePair> toReturn = Lists.newArrayListWithCapacity(count);
        for (int i = 0; i < count; i++) {
            toReturn.add(p(3000 + i, 4000 + i));
        }
        return toReturn;
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
