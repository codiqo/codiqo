package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.codiqo.llm.schema.LlmScoringRequest.Operation;

class VolumeScoreCalculatorTest {
    private static final double DENSITY_CAP_MULTIPLIER = 2.5;
    private static final double ADD_MULT = 1.0;
    private static final double MODIFY_MULT = 1.0;
    private static final double TEST_MULT = 0.4;

    @Test
    void prodMethodBlockIsCappedByProdCapNotTestCap() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prodBlock = newMethodBlock(80, 80, 80, /*isTest*/ false);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(prodBlock),
                scaler, scaler, scaler, scaler,
                /*methodCapQProd*/ 10, /*methodCapQTest*/ 100,
                /*ctorCapQProd*/ 0, /*ctorCapQTest*/ 0,
                DENSITY_CAP_MULTIPLIER, ADD_MULT, MODIFY_MULT, TEST_MULT);

        assertEquals(1, efforts.size());
        assertEquals((int) (10 * DENSITY_CAP_MULTIPLIER), efforts.get(0).getCappedStatements(),
                "prod block must use prod cap (10 × 2.5 = 25), not test cap (100 × 2.5 = 250)");
    }
    @Test
    void testMethodBlockIsCappedByTestCapNotProdCap() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange testBlock = newMethodBlock(80, 80, 80, /*isTest*/ true);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(testBlock),
                scaler, scaler, scaler, scaler,
                /*methodCapQProd*/ 10, /*methodCapQTest*/ 20,
                /*ctorCapQProd*/ 0, /*ctorCapQTest*/ 0,
                DENSITY_CAP_MULTIPLIER, ADD_MULT, MODIFY_MULT, TEST_MULT);

        assertEquals((int) (20 * DENSITY_CAP_MULTIPLIER), efforts.get(0).getCappedStatements(),
                "test block must clip at test cap (20 × 2.5 = 50), not prod cap (10 × 2.5 = 25)");
    }
    @Test
    void prodAndTestBlocksInSameCommitGetDifferentCaps() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prodBlock = newMethodBlock(80, 80, 80, false);
        CodeBlockChange testBlock = newMethodBlock(80, 80, 80, true);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(prodBlock, testBlock),
                scaler, scaler, scaler, scaler,
                10, 20, 0, 0,
                DENSITY_CAP_MULTIPLIER, ADD_MULT, MODIFY_MULT, TEST_MULT);

        CodeBlockEffort prodEffort = efforts.stream().filter(e -> !e.isTest()).findFirst().orElseThrow();
        CodeBlockEffort testEffort = efforts.stream().filter(CodeBlockEffort::isTest).findFirst().orElseThrow();

        assertEquals(25, prodEffort.getCappedStatements());
        assertEquals(50, testEffort.getCappedStatements());
        assertNotEquals(prodEffort.getCappedStatements(), testEffort.getCappedStatements(),
                "split caps must produce different values for prod vs test blocks with identical metrics");
    }
    @Test
    void constructorBlockPicksConstructorCapNotMethodCap() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prodCtor = CodeBlockChange.builder()
                .operation(Operation.NEW)
                .name("<init>")
                .file("Foo.java")
                .nonCommentCodeLines(40)
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
                DENSITY_CAP_MULTIPLIER, ADD_MULT, MODIFY_MULT, TEST_MULT);

        assertEquals((int) (5 * DENSITY_CAP_MULTIPLIER), efforts.get(0).getCappedStatements(),
                "constructor block must pick ctor/prod cap (5×2.5=12), not method/prod cap (100×2.5=250)");
    }
    @Test
    void emptyTestPopulationFallsBackToProdCapForTestBlocks() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange testBlock = newMethodBlock(80, 80, 80, /*isTest*/ true);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(testBlock),
                scaler, scaler, scaler, scaler,
                /*methodCapQProd*/ 10, /*methodCapQTest*/ 0,
                /*ctorCapQProd*/ 0, /*ctorCapQTest*/ 0,
                DENSITY_CAP_MULTIPLIER, ADD_MULT, MODIFY_MULT, TEST_MULT);

        assertEquals((int) (10 * DENSITY_CAP_MULTIPLIER), efforts.get(0).getCappedStatements(),
                "with no test samples, test block must fall back to prod cap (10×2.5=25), not zero-out");
    }
    @Test
    void allCapsZeroMeansNoCappingApplied() {
        DriverScaler scaler = uniformScaler(1, 100);
        CodeBlockChange prodBlock = newMethodBlock(80, 80, 80, false);

        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(
                List.of(prodBlock),
                scaler, scaler, scaler, scaler,
                0, 0, 0, 0,
                DENSITY_CAP_MULTIPLIER, ADD_MULT, MODIFY_MULT, TEST_MULT);

        CodeBlockEffort effort = efforts.get(0);
        assertTrue(effort.getCappedStatements() > 0);
        assertEquals((int) Math.round(effort.getDriverScore()), effort.getCappedStatements(),
                "when no caps are configured the block's driver score must pass through unchanged");
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
                DENSITY_CAP_MULTIPLIER, 1.0, 1.0, args.getTestCodeScoreMultiplier());

        double prodEffort = efforts.stream().filter(e -> !e.isTest()).findFirst().orElseThrow().getEffort();
        double testEffort = efforts.stream().filter(CodeBlockEffort::isTest).findFirst().orElseThrow().getEffort();

        assertEquals(prodEffort * args.getTestCodeScoreMultiplier(), testEffort, 0.01,
                "test block effort must equal prod effort × testCodeScoreMultiplier");
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

    private static CodeBlockChange newModifyBlock(int lines, int ncss, int invocations) {
        return CodeBlockChange.builder()
                .operation(Operation.MODIFY)
                .name("updateWork")
                .file("Foo.java")
                .nonCommentCodeLines(lines)
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
        LlmScoringRequest request = LlmScoringRequest.builder()
                .changeSummary(ChangeSummary.builder().totalFilesChanged(filesChanged).build())
                .codeBlockChanges(List.of(block))
                .methodScalerProd(scaler)
                .methodScalerTest(scaler)
                .constructorScalerProd(scaler)
                .constructorScalerTest(scaler)
                .build();
        return new VolumeScoreCalculator(args).calculate(request, projectStatements, 100, 0, 0, 0, 0);
    }
}
