package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.llm.VolumeScoreCalculator.CodeBlockEffort;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.Operation;

class VolumeScoreCalculatorTest {
    private static final double ADD_MULT = 1.0;
    private static final double MODIFY_MULT = 1.0;
    private static final double TEST_MULT = 0.4;
    private static final double NO_CLAMP = 1.0;

    @Test
    void prodMethodBlockBucketBaselineUsesProdQuantile() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prodBlock = newMethodBlock(80, 80, 80, /*isTest*/ false);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(prodBlock),
                scaler, scaler, scaler, scaler,
                /*methodCapQProd*/ 10, /*methodCapQTest*/ 100,
                /*ctorCapQProd*/ 0, /*ctorCapQTest*/ 0,
                ADD_MULT, MODIFY_MULT, TEST_MULT, NO_CLAMP);

        assertEquals(1, efforts.size());
        assertEquals(10.0, efforts.get(0).getBucketBaseline(), 0.01,
                "prod block must contribute prod quantile (10) × addMult (1.0) × testWeight (1.0) to the baseline budget");
    }
    @Test
    void testMethodBlockBucketBaselineUsesTestQuantileAndTestWeight() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange testBlock = newMethodBlock(80, 80, 80, /*isTest*/ true);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(testBlock),
                scaler, scaler, scaler, scaler,
                /*methodCapQProd*/ 10, /*methodCapQTest*/ 20,
                /*ctorCapQProd*/ 0, /*ctorCapQTest*/ 0,
                ADD_MULT, MODIFY_MULT, TEST_MULT, NO_CLAMP);

        assertEquals(20 * TEST_MULT, efforts.get(0).getBucketBaseline(), 0.01,
                "test block must contribute test quantile (20) × addMult × testCodeScoreMultiplier (0.4) to the baseline budget");
    }
    @Test
    void prodAndTestBlocksInSameCommitGetDifferentBaselines() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prodBlock = newMethodBlock(80, 80, 80, false);
        CodeBlockChange testBlock = newMethodBlock(80, 80, 80, true);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(prodBlock, testBlock),
                scaler, scaler, scaler, scaler,
                10, 20, 0, 0,
                ADD_MULT, MODIFY_MULT, TEST_MULT, NO_CLAMP);

        CodeBlockEffort prodEffort = efforts.stream().filter(e -> !e.isTest()).findFirst().orElseThrow();
        CodeBlockEffort testEffort = efforts.stream().filter(CodeBlockEffort::isTest).findFirst().orElseThrow();

        assertEquals(10.0, prodEffort.getBucketBaseline(), 0.01);
        assertEquals(20 * TEST_MULT, testEffort.getBucketBaseline(), 0.01);
        assertNotEquals(prodEffort.getBucketBaseline(), testEffort.getBucketBaseline(),
                "split bucket baselines must produce different values for prod vs test blocks with identical metrics");
    }
    @Test
    void constructorBlockBucketBaselineUsesConstructorQuantile() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prodCtor = CodeBlockChange.builder()
                .operation(Operation.NEW)
                .name("<init>")
                .file("Foo.java")
                .nonCommentCodeLines(40)
                .bodyCodeLines(40)
                .nonCommentCodeStatements(40)
                .directInvocationCount(40)
                .totalLinesChanged(40)
                .isConstructor(true)
                .isTest(false)
                .build();

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(prodCtor),
                scaler, scaler, scaler, scaler,
                /*methodCapQProd*/ 100, /*methodCapQTest*/ 100,
                /*ctorCapQProd*/ 5, /*ctorCapQTest*/ 100,
                ADD_MULT, MODIFY_MULT, TEST_MULT, NO_CLAMP);

        assertEquals(5.0, efforts.get(0).getBucketBaseline(), 0.01,
                "ctor block must use ctor/prod quantile (5), not method/prod (100)");
    }
    @Test
    void emptyTestPopulationFallsBackToProdQuantileForTestBlocks() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange testBlock = newMethodBlock(80, 80, 80, /*isTest*/ true);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(testBlock),
                scaler, scaler, scaler, scaler,
                /*methodCapQProd*/ 10, /*methodCapQTest*/ 0,
                /*ctorCapQProd*/ 0, /*ctorCapQTest*/ 0,
                ADD_MULT, MODIFY_MULT, TEST_MULT, NO_CLAMP);

        assertEquals(10 * TEST_MULT, efforts.get(0).getBucketBaseline(), 0.01,
                "with no test samples, test block must fall back to prod quantile (10) × testWeight (0.4)");
    }
    @Test
    void perBlockDriverScoreIsNotIndividuallyClipped() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prodBlock = newMethodBlock(80, 80, 80, false);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(prodBlock),
                scaler, scaler, scaler, scaler,
                /*tiny prod quantile to "cap" if the per-block cap were still in effect*/ 5, 0, 0, 0,
                ADD_MULT, MODIFY_MULT, TEST_MULT, NO_CLAMP);

        CodeBlockEffort effort = efforts.get(0);
        assertEquals((int) Math.round(effort.getDriverScore()), effort.getCappedStatements(),
                "cappedStatements must equal the rounded raw driver score regardless of bucket quantile (no per-block clip)");
        assertTrue(effort.getDriverScore() > 5,
                "driverScore must be free to exceed the bucket quantile when per-block cap is removed");
    }
    @Test
    void globalCapClipsTotalEffortWhenSumExceedsBudget() {
        RunArgs args = neutralMultiplierArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        // Neutral args (addMult = modifyMult = 1.0) so the math is exact:
        //   bucketQuantile = 5, one prod NEW block → totalBaseline = 5 × 1.0 × 1.0 = 5
        //   globalCap = 5 × 2.5 = 12.5
        //   driver score for an 80/80/80 block ≫ 12.5, so the cap binds.
        PreComputedScores scores = calculateWithQuantiles(args, scaler, newMethodBlock(80, 80, 80, false),
                /*projectStatements*/ 1000, /*filesChanged*/ 1, 5, 0, 0, 0);

        assertTrue(scores.isGlobalCapApplied(), "cap must bind when totalEffortRaw > globalCap");
        assertEquals(12.5, scores.getGlobalCap(), 0.01);
        assertEquals(scores.getGlobalCap(), scores.getBlockEffortSum(), 0.01,
                "blockEffortSum must equal globalCap when the cap binds");
        assertTrue(scores.getTotalEffortRaw() > scores.getGlobalCap(),
                "totalEffortRaw must record the un-clipped sum even when the cap binds");
    }
    @Test
    void globalCapDoesNotClipWhenTotalIsBelowBudget() {
        RunArgs args = neutralMultiplierArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        // bucketQuantile = 100 → per-block baseline = 100, globalCap = 250 (well above any 80/80/80 driver score).
        PreComputedScores scores = calculateWithQuantiles(args, scaler, newMethodBlock(80, 80, 80, false),
                1000, 1, 100, 0, 0, 0);

        assertFalse(scores.isGlobalCapApplied(), "cap must not bind when totalEffortRaw ≤ globalCap");
        assertEquals(scores.getTotalEffortRaw(), scores.getBlockEffortSum(), 0.01,
                "blockEffortSum must equal totalEffortRaw when the cap does not bind");
    }
    @Test
    void zeroQuantilesAcrossAllBucketsLeaveSumUncapped() {
        RunArgs args = neutralMultiplierArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        PreComputedScores scores = calculateWithQuantiles(args, scaler, newMethodBlock(80, 80, 80, false),
                1000, 1, 0, 0, 0, 0);

        assertFalse(scores.isGlobalCapApplied(), "with all bucket quantiles zero, totalBaseline=0 → cap is inactive");
        assertEquals(scores.getTotalEffortRaw(), scores.getBlockEffortSum(), 0.01);
    }
    @Test
    void dryRunFlagDefaultsToOffSoEnforcedBehaviorIsPreserved() {
        RunArgs args = new RunArgs();
        assertFalse(args.isDriverScoreCapDryRun(),
                "default must keep the cap enforced — dry-run is opt-in, never the default");
    }
    @Test
    void dryRunSkipsClippingButKeepsGlobalCapAppliedFlag() {
        RunArgs args = neutralMultiplierArgs();
        args.setDriverScoreCapDryRun(true);
        DriverScaler scaler = uniformScaler(1, 100);
        // Same shape as globalCapClipsTotalEffortWhenSumExceedsBudget — bucketQuantile=5 → globalCap=12.5,
        // but driverScore for 80/80/80 ≫ 12.5, so the cap WOULD bind under enforcement.
        PreComputedScores scores = calculateWithQuantiles(args, scaler, newMethodBlock(80, 80, 80, false),
                /*projectStatements*/ 1000, /*filesChanged*/ 1, 5, 0, 0, 0);

        assertTrue(scores.isGlobalCapApplied(),
                "globalCapApplied must still reflect 'cap would clip' under dry-run — abuse signals depend on it");
        assertTrue(scores.isGlobalCapDryRun(), "globalCapDryRun must mirror the args flag onto the scoring output");
        assertEquals(scores.getTotalEffortRaw(), scores.getBlockEffortSum(), 0.01,
                "blockEffortSum must equal totalEffortRaw under dry-run — cap is audited, not enforced");
        assertTrue(scores.getTotalEffortRaw() > scores.getGlobalCap(),
                "sanity check: totalEffortRaw must exceed globalCap or the cap test isn't actually meaningful");
    }
    @Test
    void dryRunDoesNotChangeOutputWhenCapWouldNotBind() {
        RunArgs args = neutralMultiplierArgs();
        args.setDriverScoreCapDryRun(true);
        DriverScaler scaler = uniformScaler(1, 100);
        // Generous quantile so the cap would never bind regardless of dry-run.
        PreComputedScores scores = calculateWithQuantiles(args, scaler, newMethodBlock(80, 80, 80, false),
                1000, 1, 100, 0, 0, 0);

        assertFalse(scores.isGlobalCapApplied(),
                "dry-run is irrelevant when the cap would not bind anyway");
        assertTrue(scores.isGlobalCapDryRun(), "globalCapDryRun mirrors the args flag whether the cap would bind or not");
        assertEquals(scores.getTotalEffortRaw(), scores.getBlockEffortSum(), 0.01);
    }
    @Test
    void dryRunStillFiresGlobalCapDriverAbuseSignal() {
        RunArgs args = neutralMultiplierArgs();
        args.setDriverScoreCapDryRun(true);
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange giant = newMethodBlock(200, 200, 200, false);
        CodeBlockChange tiny = newMethodBlock(5, 5, 5, false);

        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().totalFilesChanged(1).build())
                .codeBlockChanges(List.of(giant, tiny))
                .methodScalerProd(scaler).methodScalerTest(scaler)
                .constructorScalerProd(scaler).constructorScalerTest(scaler)
                .build();
        PreComputedScores scores = new VolumeScoreCalculator(args).calculate(request, 1000, 100,
                /*methodCapQProd*/ 5, 0, 0, 0);

        assertTrue(scores.isGlobalCapApplied(), "the cap would bind on the giant block");
        assertTrue(scores.isGlobalCapDryRun(), "dry-run mirrored onto the score");
        assertEquals(scores.getTotalEffortRaw(), scores.getBlockEffortSum(), 0.01,
                "dry-run leaves blockEffortSum untouched — the volume score is uncapped");
        CodeBlockEffort dominant = scores.getCodeBlockEfforts().stream()
                .max((a, b) -> Double.compare(a.getEffort(), b.getEffort())).orElseThrow();
        assertTrue(dominant.isGlobalCapDriver(),
                "globalCapDriver must still flag the dominant block under dry-run — that is the abuse signal we want in the DB");
    }
    @Test
    void enforcedCapClipsBlockEffortSumExactlyAtGlobalCap() {
        RunArgs args = neutralMultiplierArgs();
        // dry-run defaults to false — same as globalCapClipsTotalEffortWhenSumExceedsBudget but asserting strictly
        DriverScaler scaler = uniformScaler(1, 100);
        PreComputedScores scores = calculateWithQuantiles(args, scaler, newMethodBlock(80, 80, 80, false),
                1000, 1, 5, 0, 0, 0);

        assertFalse(scores.isGlobalCapDryRun(), "default is enforced mode");
        assertTrue(scores.isGlobalCapApplied());
        assertEquals(scores.getGlobalCap(), scores.getBlockEffortSum(), 0.01,
                "enforced mode must clip blockEffortSum to globalCap — anything else is a regression of the cap");
    }
    private static RunArgs neutralMultiplierArgs() {
        RunArgs args = new RunArgs();
        args.setAddMultiplierScale(0.0);
        args.setModifyMultiplierScale(0.0);
        args.setModifyMultiplierCap(0.0);
        return args;
    }

    private static DriverScaler uniformScaler(int min, int max) {
        List<DriverScaler.Sample> samples = Lists.newArrayList();
        for (int i = min; i <= max; i++) {
            samples.add(new DriverScaler.Sample(i, i, i));
        }
        return DriverScaler.of(samples);
    }
    private static CodeBlockChange newMethodBlock(int lines, int ncss, int invocations, boolean isTest) {
        return CodeBlockChange.builder()
                .operation(Operation.NEW)
                .name("doWork")
                .file("Foo.java")
                .nonCommentCodeLines(lines)
                .bodyCodeLines(lines)
                .nonCommentCodeStatements(ncss)
                .directInvocationCount(invocations)
                .totalLinesChanged(lines)
                .isConstructor(false)
                .isTest(isTest)
                .build();
    }

    @Test
    void testBlockEffortIsMultipliedByTestCodeScoreMultiplier() {
        RunArgs args = new RunArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prod = newMethodBlock(50, 50, 50, false);
        CodeBlockChange test = newMethodBlock(50, 50, 50, true);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(prod, test),
                scaler, scaler, scaler, scaler,
                0, 0, 0, 0,
                1.0, 1.0, args.getTestCodeScoreMultiplier(), args.getDriverFactorMaxDeviation());

        double prodEffort = efforts.stream().filter(e -> !e.isTest()).findFirst().orElseThrow().getEffort();
        double testEffort = efforts.stream().filter(CodeBlockEffort::isTest).findFirst().orElseThrow().getEffort();

        assertEquals(prodEffort * args.getTestCodeScoreMultiplier(), testEffort, 0.01,
                "test block effort must equal prod effort × testCodeScoreMultiplier");
    }
    @Test
    void configFileEffortIsDiscountedByConfigFileScoreMultiplier() {
        RunArgs args = neutralMultiplierArgs();
        FileChange pom = FileChange.builder().path("pom.xml").isConfig(true).linesAdded(10).linesDeleted(4).build();

        PreComputedScores scores = calculateConfigOnly(args, pom);

        CodeBlockEffort config = scores.getCodeBlockEfforts().stream()
                .filter(CodeBlockEffort::isConfig).findFirst().orElseThrow();
        assertEquals(14.0, config.getDriverScore(), 0.001, "driverScore = linesAdded + linesDeleted (the diff factor collapses pairs)");
        assertEquals(14.0 * args.getConfigFileScoreMultiplier(), config.getEffort(), 0.001,
                "config effort = rawLines × modifyMult × configFileScoreMultiplier");
    }
    @Test
    void configFileBucketBaselineDoesNotLoosenGlobalCap() {
        RunArgs args = neutralMultiplierArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange code = newMethodBlock(50, 50, 50, false);

        LlmScoringRequest withoutConfig = codeRequest(scaler, code, /*files*/ 1, null);
        LlmScoringRequest withConfig = codeRequest(scaler, code, /*files*/ 2,
                FileChange.builder().path("pom.xml").isConfig(true).linesAdded(500).linesDeleted(0).build());

        PreComputedScores a = new VolumeScoreCalculator(args).calculate(withoutConfig, 1000, 100, 5, 0, 0, 0);
        PreComputedScores b = new VolumeScoreCalculator(args).calculate(withConfig, 1000, 100, 5, 0, 0, 0);

        assertEquals(a.getTotalBaseline(), b.getTotalBaseline(), 0.001,
                "a huge config edit must not inflate the global-cap baseline");
        assertEquals(a.getGlobalCap(), b.getGlobalCap(), 0.001);
    }
    @Test
    void configFileDriverScoreExcludedFromEffectiveStatements() {
        RunArgs args = neutralMultiplierArgs();
        FileChange pom = FileChange.builder().path("pom.xml").isConfig(true).linesAdded(40).linesDeleted(0).build();

        PreComputedScores scores = calculateConfigOnly(args, pom);

        assertEquals(0, scores.getTotalEffectiveStatements(),
                "config lines are not statements and must not inflate effective statements");
    }
    private static PreComputedScores calculateConfigOnly(RunArgs args, FileChange config) {
        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().totalFilesChanged(1).build())
                .codeBlockChanges(List.of())
                .fileChanges(List.of(config))
                .build();
        return new VolumeScoreCalculator(args).calculate(request, 1000, 100, 0, 0, 0, 0);
    }
    private static LlmScoringRequest codeRequest(DriverScaler scaler, CodeBlockChange block, int filesChanged, FileChange config) {
        return LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().totalFilesChanged(filesChanged).build())
                .codeBlockChanges(List.of(block))
                .fileChanges(config == null ? List.of() : List.of(config))
                .methodScalerProd(scaler).methodScalerTest(scaler)
                .constructorScalerProd(scaler).constructorScalerTest(scaler)
                .build();
    }
    @Test
    void sizeFactorPushesModifyMultiplierUpAsProjectGrows() {
        RunArgs args = new RunArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange block = newModifyBlock(50, 50, 50);

        PreComputedScores tinyProject = calculateWith(args, scaler, block, /*projectStatements*/ 100);
        PreComputedScores hugeProject = calculateWith(args, scaler, block, /*projectStatements*/ 10_000_000);

        assertTrue(hugeProject.getModifyMult() > tinyProject.getModifyMult(),
                "a larger project should produce a larger modifyMult via sizeFactor");
        double cap = 1.0 + args.getModifyMultiplierCap();
        assertTrue(hugeProject.getModifyMult() <= cap + 0.01,
                "modifyMult must never exceed 1 + modifyMultiplierCap regardless of project size");
    }
    @Test
    void sizeFactorPushesAddMultiplierDownAsProjectGrows() {
        RunArgs args = new RunArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange block = newMethodBlock(50, 50, 50, false);

        PreComputedScores tinyProject = calculateWith(args, scaler, block, /*projectStatements*/ 100);
        PreComputedScores hugeProject = calculateWith(args, scaler, block, /*projectStatements*/ 10_000_000);

        double tinyAddMult = tinyProject.getAddMult();
        double hugeAddMult = hugeProject.getAddMult();

        assertTrue(tinyAddMult > hugeAddMult,
                "smaller projects should produce a larger addMult — add effort is dampened by size");
        double ceiling = 1.0 + args.getAddMultiplierScale();
        assertTrue(tinyAddMult <= ceiling + 0.01,
                "addMult must never exceed 1 + addMultiplierScale (the small-project limit)");
        assertTrue(hugeAddMult > 1.0,
                "addMult stays above 1.0 even for huge projects — the dampening never pushes it below neutral");
    }
    @Test
    void filesScopeMultiplierIsOneForSingleFile() {
        RunArgs args = new RunArgs();
        DriverScaler scaler = uniformScaler(1, 100);

        PreComputedScores scores = calculateWith(args, scaler, newMethodBlock(10, 10, 10, false), 1000, /*filesChanged*/ 1);

        assertEquals(1.0, scores.getFilesScopeMultiplier(), 0.001,
                "a single-file commit must not receive any files-scope bonus");
    }
    @Test
    void filesScopeMultiplierIsCappedAtFilesScopeMaxBonus() {
        RunArgs args = new RunArgs();
        DriverScaler scaler = uniformScaler(1, 100);

        PreComputedScores scores = calculateWith(args, scaler, newMethodBlock(10, 10, 10, false), 1000, /*filesChanged*/ 1024);

        double ceiling = 1.0 + args.getFilesScopeMaxBonus();
        assertTrue(scores.getFilesScopeMultiplier() <= ceiling + 0.001,
                "a many-files commit must not exceed 1 + filesScopeMaxBonus regardless of how many files");
        assertTrue(scores.getFilesScopeMultiplier() > 1.0,
                "multi-file commits still receive a positive (if bounded) bonus");
    }
    @Test
    void filesScopeMultiplierGrowsWithMoreFiles() {
        RunArgs args = new RunArgs();
        DriverScaler scaler = uniformScaler(1, 100);

        PreComputedScores twoFiles = calculateWith(args, scaler, newMethodBlock(10, 10, 10, false), 1000, 2);
        PreComputedScores eightFiles = calculateWith(args, scaler, newMethodBlock(10, 10, 10, false), 1000, 8);

        assertTrue(eightFiles.getFilesScopeMultiplier() > twoFiles.getFilesScopeMultiplier(),
                "more files should produce a larger (but bounded) files-scope multiplier");
    }
    @Test
    void volumeExponentDampensLargerBlockEffortSums() {
        RunArgs args = new RunArgs();
        assertTrue(args.getVolumeExponent() < 1.0,
                "the default volumeExponent should be < 1.0 so large commits are sub-linearly dampened");

        DriverScaler scaler = uniformScaler(1, 100);
        PreComputedScores scores = calculateWith(args, scaler, newMethodBlock(80, 80, 80, false), 1000);

        double expectedVolume = Math.pow(scores.getBlockEffortSum(), args.getVolumeExponent())
                * scores.getFilesScopeMultiplier();
        assertEquals(expectedVolume, scores.getVolumeScore(), 0.1,
                "volumeScore must equal blockEffortSum^volumeExponent × filesScopeMultiplier");
    }
    @Test
    void baseEffortEqualsVolumeScoreAfterComplexityGroupDeletion() {
        RunArgs args = new RunArgs();
        DriverScaler scaler = uniformScaler(1, 100);

        PreComputedScores scores = calculateWith(args, scaler, newMethodBlock(50, 50, 50, false), 1000);

        assertEquals(scores.getVolumeScore(), scores.getBaseEffort(), 0.001,
                "with the complexity-multiplier group removed, baseEffort == volumeScore pre-LLM. "
                        + "FinalScoreCalculator later multiplies by the LLM's chosen complexityMultiplier.");
    }

    @Test
    void blockMatchingBucketMedianHasZeroDeviationAndNoOutlierFlag() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange block = newMethodBlock(50, 50, 50, false);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(block), scaler, scaler, scaler, scaler,
                0, 0, 0, 0, ADD_MULT, MODIFY_MULT, TEST_MULT, 0.25);

        CodeBlockEffort cbe = efforts.get(0);
        assertEquals(0.0, cbe.getBlockRatioDeviationNcss(), 0.001,
                "block S/L matches bucket ncss.p50/lines.p50 → zero deviation");
        assertEquals(0.0, cbe.getBlockRatioDeviationInvocations(), 0.001);
        assertFalse(cbe.isBlockRatioOutlier());
    }
    @Test
    void blockRatioOutlierFiresWhenDeviationExceedsThreshold() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange ncssDense = newMethodBlock(10, 15, 10, false);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(ncssDense), scaler, scaler, scaler, scaler,
                0, 0, 0, 0, ADD_MULT, MODIFY_MULT, TEST_MULT, 0.25);

        CodeBlockEffort cbe = efforts.get(0);
        assertEquals(0.5, cbe.getBlockRatioDeviationNcss(), 0.01,
                "block S/L = 15/10 = 1.5; bucket ratio = 1.0 → deviation = |1.5-1.0|/1.0 = 0.5");
        assertTrue(cbe.isBlockRatioOutlier(),
                "deviation 0.5 > maxDeviation 0.25 → outlier flag fires");
    }
    @Test
    void modifyBlockSkipsBothNcssAndInvocationsDeviation() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange modify = CodeBlockChange.builder()
                .operation(Operation.MODIFY)
                .name("doStuff").file("Foo.java")
                .nonCommentCodeLines(10)
                .bodyCodeLines(10)
                .nonCommentCodeStatements(15)
                .directInvocationCount(15)
                .totalLinesChanged(10)
                .effectiveInvocationsChanged(15)
                .isConstructor(false).isTest(false).build();

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(modify), scaler, scaler, scaler, scaler,
                0, 0, 0, 0, ADD_MULT, MODIFY_MULT, TEST_MULT, 0.25);

        CodeBlockEffort cbe = efforts.get(0);
        assertEquals(0.0, cbe.getBlockRatioDeviationNcss(), 0.001,
                "MODIFY drops NCSS from the score → blockRatioDeviationNcss must be 0");
        assertEquals(0.0, cbe.getBlockRatioDeviationInvocations(), 0.001,
                "MODIFY effective invocs/lines is a diff-shape artifact, not block structure → deviation always 0");
        assertFalse(cbe.isBlockRatioOutlier(), "MODIFY blocks can never be ratio outliers — abuse signal lives in NEW blocks only");
    }
    @Test
    void globalCapDriverFiresOnlyWhenCapBindsAndShareExceedsThreshold() {
        RunArgs args = neutralMultiplierArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange giant = newMethodBlock(200, 200, 200, false);
        CodeBlockChange tiny = newMethodBlock(5, 5, 5, false);

        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().totalFilesChanged(1).build())
                .codeBlockChanges(List.of(giant, tiny))
                .methodScalerProd(scaler).methodScalerTest(scaler)
                .constructorScalerProd(scaler).constructorScalerTest(scaler)
                .build();
        PreComputedScores scores = new VolumeScoreCalculator(args).calculate(request, 1000, 100,
                /*methodCapQProd*/ 5, 0, 0, 0);

        assertTrue(scores.isGlobalCapApplied(), "tiny quantile vs giant block must trigger the global cap");
        CodeBlockEffort dominant = scores.getCodeBlockEfforts().stream()
                .max((a, b) -> Double.compare(a.getEffort(), b.getEffort())).orElseThrow();
        assertTrue(dominant.getEffortShare() > args.getDriverFactorMaxDeviation(),
                "the dominant block's effortShare should clearly exceed driverFactorMaxDeviation");
        assertTrue(dominant.isGlobalCapDriver(),
                "globalCapDriver must fire for the block whose effortShare > maxDeviation when cap binds");
    }
    @Test
    void globalCapDriverNeverFiresWhenCapDoesNotBind() {
        RunArgs args = neutralMultiplierArgs();
        DriverScaler scaler = uniformScaler(1, 100);
        PreComputedScores scores = calculateWithQuantiles(args, scaler, newMethodBlock(50, 50, 50, false),
                1000, 1, /*generous prod quantile*/ 1000, 0, 0, 0);

        assertFalse(scores.isGlobalCapApplied(), "generous quantile keeps the cap inactive");
        scores.getCodeBlockEfforts().forEach(cbe ->
                assertFalse(cbe.isGlobalCapDriver(), "globalCapDriver must never fire when the global cap is inactive"));
    }
    @Test
    void fileFlaggedAsAbusiveWhenMajorityOfBlocksAreOutliers() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange outlier1 = blockInFile("A.java", 10, 15, 10);
        CodeBlockChange outlier2 = blockInFile("A.java", 10, 18, 10);
        CodeBlockChange normal = blockInFile("A.java", 50, 50, 50);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(outlier1, outlier2, normal),
                scaler, scaler, scaler, scaler,
                0, 0, 0, 0, ADD_MULT, MODIFY_MULT, TEST_MULT, 0.25);

        VolumeScoreCalculator.FileEffort fileEffort = VolumeScoreCalculator.groupByFile(efforts, 0.25).get(0);
        assertEquals(2, fileEffort.getBlocksFlaggedAsRatioOutlier());
        assertTrue(fileEffort.isFileFlaggedAsAbusive(),
                "2 of 3 blocks are outliers → strict majority rule (count*2 > total) flags the file");
    }
    @Test
    void fileNotFlaggedWhenOutliersAreMinority() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange outlier = blockInFile("B.java", 10, 15, 10);
        CodeBlockChange normal1 = blockInFile("B.java", 50, 50, 50);
        CodeBlockChange normal2 = blockInFile("B.java", 50, 50, 50);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(outlier, normal1, normal2),
                scaler, scaler, scaler, scaler,
                0, 0, 0, 0, ADD_MULT, MODIFY_MULT, TEST_MULT, 0.25);

        VolumeScoreCalculator.FileEffort fileEffort = VolumeScoreCalculator.groupByFile(efforts, 0.25).get(0);
        assertEquals(1, fileEffort.getBlocksFlaggedAsRatioOutlier());
        assertFalse(fileEffort.isFileFlaggedAsAbusive(),
                "1 of 3 outliers → no strict majority → file not flagged");
    }
    private static CodeBlockChange blockInFile(String file, int lines, int ncss, int invocations) {
        return CodeBlockChange.builder()
                .operation(Operation.NEW)
                .name("m_" + lines + "_" + ncss).file(file)
                .nonCommentCodeLines(lines)
                .bodyCodeLines(lines)
                .nonCommentCodeStatements(ncss)
                .directInvocationCount(invocations)
                .totalLinesChanged(lines)
                .isConstructor(false).isTest(false).build();
    }

    private static CodeBlockChange newModifyBlock(int lines, int ncss, int invocations) {
        return CodeBlockChange.builder()
                .operation(Operation.MODIFY)
                .name("updateWork")
                .file("Foo.java")
                .nonCommentCodeLines(lines)
                .bodyCodeLines(lines)
                .nonCommentCodeStatements(ncss)
                .directInvocationCount(invocations)
                .totalLinesChanged(lines)
                .effectiveInvocationsChanged(invocations)
                .isConstructor(false)
                .isTest(false)
                .build();
    }
    private static PreComputedScores calculateWith(RunArgs args, DriverScaler scaler, CodeBlockChange block, long projectStatements) {
        return calculateWith(args, scaler, block, projectStatements, 1);
    }
    private static PreComputedScores calculateWith(RunArgs args, DriverScaler scaler, CodeBlockChange block, long projectStatements, int filesChanged) {
        return calculateWithQuantiles(args, scaler, block, projectStatements, filesChanged, 0, 0, 0, 0);
    }
    private static PreComputedScores calculateWithQuantiles(RunArgs args, DriverScaler scaler, CodeBlockChange block,
            long projectStatements, int filesChanged,
            int methodCapQuantileProd, int methodCapQuantileTest,
            int constructorCapQuantileProd, int constructorCapQuantileTest) {
        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().totalFilesChanged(filesChanged).build())
                .codeBlockChanges(List.of(block))
                .methodScalerProd(scaler)
                .methodScalerTest(scaler)
                .constructorScalerProd(scaler)
                .constructorScalerTest(scaler)
                .build();
        return new VolumeScoreCalculator(args).calculate(request, projectStatements, 100,
                methodCapQuantileProd, methodCapQuantileTest, constructorCapQuantileProd, constructorCapQuantileTest);
    }
}
