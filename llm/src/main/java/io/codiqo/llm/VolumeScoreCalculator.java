package io.codiqo.llm;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.math3.util.Precision;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.api.metrics.DriverScore;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import io.codiqo.llm.schema.LlmScoringRequest.DiagnosticInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo.CloneDetail;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;

@RequiredArgsConstructor
public class VolumeScoreCalculator {
    private static final int ROUNDING_PRECISION = 2;
    private static final int CPD_ROUNDING_PRECISION = 1;

    private final RunArgs args;

    public PreComputedScores calculate(
            LlmScoringRequest request,
            long projectTotalStatements,
            int projectTotalMethods,
            int methodCapQuantileProd,
            int methodCapQuantileTest,
            int constructorCapQuantileProd,
            int constructorCapQuantileTest) {
        ChangeSummary changeSummary = request.getChangeSummary();
        double testMult = args.getTestCodeScoreMultiplier();
        double sizeFactor = Math.cbrt(projectTotalStatements) / args.getSizeFactorDivisor();
        double modifyMult = 1.0 + Math.min(sizeFactor * args.getModifyMultiplierScale(), args.getModifyMultiplierCap());
        double addMult = 1.0 + args.getAddMultiplierScale() / (1.0 + sizeFactor);

        double maxDeviation = args.getDriverFactorMaxDeviation();
        List<CodeBlockEffort> initialEfforts = calculateCodeBlockEfforts(
                request.getCodeBlockChanges(),
                request.getMethodScalerProd(), request.getMethodScalerTest(),
                request.getConstructorScalerProd(), request.getConstructorScalerTest(),
                methodCapQuantileProd, methodCapQuantileTest,
                constructorCapQuantileProd, constructorCapQuantileTest,
                addMult, modifyMult, testMult, maxDeviation);
        initialEfforts.addAll(calculateConfigFileEfforts(request.getFileChanges(), modifyMult, args.getConfigFileScoreMultiplier()));

        double totalEffortRaw = initialEfforts.stream().mapToDouble(CodeBlockEffort::getEffort).sum();
        double totalBaseline = initialEfforts.stream().mapToDouble(CodeBlockEffort::getBucketBaseline).sum();
        double globalCap = totalBaseline * args.getDriverScoreCapMultiplier();
        boolean globalCapApplied = totalBaseline > 0 && totalEffortRaw > globalCap;
        boolean capDryRun = args.isDriverScoreCapDryRun();
        double blockEffortSum = globalCapApplied && !capDryRun ? globalCap : totalEffortRaw;
        double volumeScore = Math.pow(blockEffortSum, args.getVolumeExponent());

        List<CodeBlockEffort> codeBlockEfforts = applyAbuseSignals(initialEfforts, totalEffortRaw, globalCapApplied, maxDeviation);
        List<FileEffort> fileEfforts = groupByFile(codeBlockEfforts, maxDeviation);

        int filesChanged = changeSummary.getTotalFilesChanged();
        double filesScopeMultiplier = 1.0;
        if (filesChanged > 1) {
            double logBonus = Math.log(filesChanged) / Math.log(2) * args.getFilesScopeLogCoefficient();
            filesScopeMultiplier = 1.0 + Math.min(logBonus, args.getFilesScopeMaxBonus());
        }

        double totalVolumeScore = volumeScore * filesScopeMultiplier;
        double baseEffort = totalVolumeScore;

        int totalEffectiveStatements = (int) Math.round(codeBlockEfforts.stream()
                .filter(cbe -> !cbe.isConfig())
                .mapToDouble(CodeBlockEffort::getDriverScore).sum());

        CpdPreComputed cpd = calculateCpdPenalty(request);
        StaticAnalysisPreComputed sa = calculateStaticAnalysisPenalty(request);

        return PreComputedScores.builder()
                .projectTotalStatements(projectTotalStatements)
                .projectTotalMethods(projectTotalMethods)
                .methodCapQuantileProd(methodCapQuantileProd)
                .methodCapQuantileTest(methodCapQuantileTest)
                .constructorCapQuantileProd(constructorCapQuantileProd)
                .constructorCapQuantileTest(constructorCapQuantileTest)
                .sizeFactor(Precision.round(sizeFactor, ROUNDING_PRECISION))
                .modifyMult(Precision.round(modifyMult, ROUNDING_PRECISION))
                .addMult(Precision.round(addMult, ROUNDING_PRECISION))
                .linesChanged(changeSummary.getTotalLinesChanged())
                .totalEffectiveStatements(totalEffectiveStatements)
                .filesChanged(filesChanged)
                .filesScopeMultiplier(Precision.round(filesScopeMultiplier, ROUNDING_PRECISION))
                .blockEffortSum(Precision.round(blockEffortSum, ROUNDING_PRECISION))
                .totalEffortRaw(Precision.round(totalEffortRaw, ROUNDING_PRECISION))
                .totalBaseline(Precision.round(totalBaseline, ROUNDING_PRECISION))
                .globalCap(Precision.round(globalCap, ROUNDING_PRECISION))
                .globalCapApplied(globalCapApplied)
                .globalCapDryRun(capDryRun)
                .codeBlocksModified(changeSummary.getCodeBlocksModified())
                .codeBlocksAdded(changeSummary.getCodeBlocksAdded())
                .classesModified(changeSummary.getClassesModified())
                .classesAdded(changeSummary.getClassesAdded())
                .testCodeScoreMultiplier(testMult)
                .testLinesChanged(changeSummary.getTestLinesChanged())
                .testCodeBlocksModified(changeSummary.getTestCodeBlocksModified())
                .testCodeBlocksAdded(changeSummary.getTestCodeBlocksAdded())
                .testClassesModified(changeSummary.getTestClassesModified())
                .testClassesAdded(changeSummary.getTestClassesAdded())
                .testFilesChanged(changeSummary.getTestFilesChanged())
                .volumeScore(Precision.round(totalVolumeScore, ROUNDING_PRECISION))
                .volumeExponent(args.getVolumeExponent())
                .baseEffort(Precision.round(baseEffort, ROUNDING_PRECISION))
                .cpdEffectivePenalty(cpd.getEffectivePenalty())
                .cpdCategory(cpd.getCategory())
                .cpdRecommendedImpact(cpd.getRecommendedImpact())
                .cpdTotalClones(cpd.getTotalClones())
                .cpdIntroducedClones(cpd.getIntroducedClones())
                .cpdTestOnlyClones(cpd.getTestOnlyClones())
                .staticAnalysisErrorCount(sa.getErrorCount())
                .staticAnalysisIntroducedCount(sa.getIntroducedCount())
                .staticAnalysisPreExistingCount(sa.getPreExistingCount())
                .staticAnalysisCategory(sa.getCategory())
                .staticAnalysisRecommendedImpact(sa.getRecommendedImpact())
                .codeBlockEfforts(codeBlockEfforts)
                .fileEfforts(fileEfforts)
                .build();
    }
    public PreComputedScores recompute(PreComputedScores original, Map<String, Double> perFileEffectiveLineFactor) {
        double maxDeviation = args.getDriverFactorMaxDeviation();

        List<CodeBlockEffort> rescaled = Lists.newArrayListWithCapacity(original.getCodeBlockEfforts().size());
        for (CodeBlockEffort cbe : original.getCodeBlockEfforts()) {
            double factor = perFileEffectiveLineFactor.getOrDefault(cbe.getFile(), 1.0);
            double scaledEffort = cbe.getEffort() * factor;
            double scaledDriverScore = cbe.getDriverScore() * factor;
            rescaled.add(new CodeBlockEffort(cbe.getFile(), cbe.getName(), cbe.getSignature(),
                    cbe.getOperation(), cbe.getNonCommentCodeStatements(), cbe.getDirectInvocationCount(),
                    cbe.getEffectiveInvocationsChanged(), cbe.getNonCommentCodeLines(), cbe.getCommentLines(),
                    cbe.getEffectiveLinesChanged(), cbe.getChangeRatio(),
                    cbe.getScaledLines(), cbe.getScaledNcss(), cbe.getScaledInvocations(),
                    scaledDriverScore, cbe.getCappedStatements(), scaledEffort, cbe.getBucketBaseline(), cbe.isTest(),
                    cbe.getBlockRatioDeviationNcss(), cbe.getBlockRatioDeviationInvocations(), cbe.isBlockRatioOutlier(),
                    0.0, false,
                    cbe.getBodyStartLine(), cbe.getBodyEndLine(), cbe.getBodyCodeLines(), cbe.isConfig()));
        }

        double totalEffortRaw = rescaled.stream().mapToDouble(CodeBlockEffort::getEffort).sum();
        double totalBaseline = original.getTotalBaseline();
        double globalCap = original.getGlobalCap();
        boolean globalCapApplied = totalBaseline > 0 && totalEffortRaw > globalCap;
        boolean capDryRun = original.isGlobalCapDryRun();
        double blockEffortSum = globalCapApplied && !capDryRun ? globalCap : totalEffortRaw;
        double volumeScore = Math.pow(blockEffortSum, args.getVolumeExponent());

        List<CodeBlockEffort> codeBlockEfforts = applyAbuseSignals(rescaled, totalEffortRaw, globalCapApplied, maxDeviation);
        List<FileEffort> fileEfforts = groupByFile(codeBlockEfforts, maxDeviation);

        double totalVolumeScore = volumeScore * original.getFilesScopeMultiplier();
        double baseEffort = totalVolumeScore;

        int totalEffectiveStatements = (int) Math.round(codeBlockEfforts.stream()
                .filter(cbe -> !cbe.isConfig())
                .mapToDouble(CodeBlockEffort::getDriverScore).sum());

        return original.toBuilder()
                .blockEffortSum(Precision.round(blockEffortSum, ROUNDING_PRECISION))
                .totalEffortRaw(Precision.round(totalEffortRaw, ROUNDING_PRECISION))
                .globalCapApplied(globalCapApplied)
                .volumeScore(Precision.round(totalVolumeScore, ROUNDING_PRECISION))
                .baseEffort(Precision.round(baseEffort, ROUNDING_PRECISION))
                .totalEffectiveStatements(totalEffectiveStatements)
                .codeBlockEfforts(codeBlockEfforts)
                .fileEfforts(fileEfforts)
                .build();
    }
    public CpdPreComputed calculateCpdPenalty(LlmScoringRequest request) {
        DuplicationInfo dup = request.getDuplication();
        if (Objects.isNull(dup) || CollectionUtils.isEmpty(dup.getCloneDetails())) {
            return new CpdPreComputed(0, CpdCategory.CLEAN, args.getCpdCleanBonus(), 0, 0, 0);
        }
        List<CloneDetail> clones = dup.getCloneDetails();
        int total = clones.size();
        int introduced = 0;
        int testOnly = 0;
        double effectivePenalty = 0;
        for (CloneDetail clone : clones) {
            if (clone.isAllTestCode()) {
                testOnly++;
            }
            if (clone.isIntroducedInCommit()) {
                introduced++;
                effectivePenalty += clone.isAllTestCode() ? args.getTestCodePenaltyWeight() : 1.0;
            }
        }
        effectivePenalty = Precision.round(effectivePenalty, CPD_ROUNDING_PRECISION);
        CpdCategory category;
        double impact;
        if (effectivePenalty <= args.getCpdCleanThreshold()) {
            category = CpdCategory.CLEAN;
            impact = args.getCpdCleanBonus();
        } else if (effectivePenalty <= args.getCpdAcceptableThreshold()) {
            category = CpdCategory.ACCEPTABLE;
            impact = 0;
        } else if (effectivePenalty <= args.getCpdModerateThreshold()) {
            category = CpdCategory.MODERATE;
            impact = args.getCpdModeratePenalty();
        } else if (effectivePenalty <= args.getCpdHighThreshold()) {
            category = CpdCategory.HIGH;
            impact = args.getCpdHighPenalty();
        } else {
            category = CpdCategory.SEVERE;
            impact = args.getCpdSeverePenalty();
        }
        return new CpdPreComputed(effectivePenalty, category, impact, total, introduced, testOnly);
    }
    public StaticAnalysisPreComputed calculateStaticAnalysisPenalty(LlmScoringRequest request) {
        Set<String> introducedErrorRules = Sets.newHashSet();
        Set<String> preExistingErrorRules = Sets.newHashSet();
        if (Objects.nonNull(request.getCodeBlockChanges())) {
            for (CodeBlockChange codeBlock : request.getCodeBlockChanges()) {
                if (Objects.nonNull(codeBlock.getDiagnostics())) {
                    for (DiagnosticInfo diag : codeBlock.getDiagnostics()) {
                        if (BooleanUtils
                                .and(new boolean[] { diag.getSeverity() == LlmScoringRequest.DiagnosticSeverity.ERROR, Objects.nonNull(diag.getRuleId()) })) {
                            if (diag.isIntroducedInCommit()) {
                                introducedErrorRules.add(diag.getRuleId());
                            } else {
                                preExistingErrorRules.add(diag.getRuleId());
                            }
                        }
                    }
                }
            }
        }
        preExistingErrorRules.removeAll(introducedErrorRules);
        int introducedCount = introducedErrorRules.size();
        int preExistingCount = preExistingErrorRules.size();
        int totalCount = introducedCount + preExistingCount;
        StaticAnalysisCategory category;
        double impact;
        if (introducedCount == 0) {
            category = StaticAnalysisCategory.CLEAN;
            impact = args.getStaticAnalysisCleanBonus();
        } else {
            category = StaticAnalysisCategory.HAS_VIOLATIONS;
            impact = Math.max(-args.getStaticAnalysisPenaltyCap(),
                    args.getStaticAnalysisIntroducedPenalty() * introducedCount +
                            args.getStaticAnalysisPreExistingPenalty() * preExistingCount);
        }
        return new StaticAnalysisPreComputed(totalCount, introducedCount, preExistingCount, category, Precision.round(impact, ROUNDING_PRECISION));
    }
    static List<CodeBlockEffort> calculateCodeBlockEfforts(
            List<CodeBlockChange> codeBlocks,
            DriverScaler methodScalerProd,
            DriverScaler methodScalerTest,
            DriverScaler constructorScalerProd,
            DriverScaler constructorScalerTest,
            int methodCapQuantileProd,
            int methodCapQuantileTest,
            int constructorCapQuantileProd,
            int constructorCapQuantileTest,
            double addMult,
            double modifyMult,
            double testMult,
            double maxDeviation) {
        if (CollectionUtils.isEmpty(codeBlocks)) {
            return Lists.newArrayList();
        }

        List<CodeBlockEffort> toReturn = Lists.newArrayList();
        for (CodeBlockChange block : codeBlocks) {
            if (block.isDelete()) {
                continue;
            }

            DriverScaler scaler = selectScaler(block, methodScalerProd, methodScalerTest, constructorScalerProd, constructorScalerTest);

            double changeRatio = 1.0;
            int invocationsChanged = 0;
            double driverScore;
            double projectedLines;
            double projectedNcss;
            double projectedInvocations;
            double deviationNcss;
            double deviationInvocations;
            int blockLines = block.getBodyCodeLines();
            if (block.isModify()) {
                int linesChanged = Math.min(block.getTotalLinesChanged(), blockLines);
                changeRatio = computeChangeRatio(block);
                invocationsChanged = block.getEffectiveInvocationsChanged();
                driverScore = DriverScore.forModify(scaler, linesChanged, invocationsChanged);
                projectedLines = linesChanged;
                projectedNcss = 0.0;
                projectedInvocations = invocationsChanged * scaler.invocationsFactor();
                deviationNcss = 0.0;
                deviationInvocations = 0.0;
            } else {
                driverScore = DriverScore.forNew(scaler, blockLines,
                        block.getNonCommentCodeStatements(), block.getDirectInvocationCount());
                projectedLines = blockLines;
                projectedNcss = block.getNonCommentCodeStatements() * scaler.ncssFactor();
                projectedInvocations = block.getDirectInvocationCount() * scaler.invocationsFactor();
                deviationNcss = relativeDeviation(block.getNonCommentCodeStatements(), blockLines, bucketRatio(scaler.ncss(), scaler.lines()));
                deviationInvocations = relativeDeviation(block.getDirectInvocationCount(), blockLines, bucketRatio(scaler.invocations(), scaler.lines()));
            }

            int bucketQuantile = selectBucketQuantile(block, methodCapQuantileProd, methodCapQuantileTest, constructorCapQuantileProd, constructorCapQuantileTest);
            int cappedStatements = (int) Math.round(driverScore);
            double operationMult = block.isNew() ? addMult : modifyMult;
            double testWeight = block.isTest() ? testMult : 1.0;
            double effort = driverScore * operationMult * testWeight;
            double bucketBaseline = bucketQuantile * operationMult * testWeight;

            boolean ratioOutlier = deviationNcss > maxDeviation || deviationInvocations > maxDeviation;

            toReturn.add(new CodeBlockEffort(block.getFile(), block.getName(), block.getSignature(),
                    block.getOperation(), block.getNonCommentCodeStatements(), block.getDirectInvocationCount(),
                    invocationsChanged, block.getNonCommentCodeLines(), block.getCommentLines(), block.getTotalLinesChanged(),
                    Precision.round(changeRatio, ROUNDING_PRECISION),
                    Precision.round(projectedLines, ROUNDING_PRECISION),
                    Precision.round(projectedNcss, ROUNDING_PRECISION),
                    Precision.round(projectedInvocations, ROUNDING_PRECISION),
                    driverScore, cappedStatements, effort, bucketBaseline, block.isTest(),
                    Precision.round(deviationNcss, ROUNDING_PRECISION),
                    Precision.round(deviationInvocations, ROUNDING_PRECISION),
                    ratioOutlier, 0.0, false,
                    block.getBodyStartLine(), block.getBodyEndLine(), block.getBodyCodeLines(), false));
        }
        return toReturn;
    }
    // non-code text files (pom.xml, proto) have no code blocks, so their effort is computed from line
    // count alone: one synthetic block per file weighted by the config multiplier. The LLM diff
    // classification later rescales these in recompute, collapsing cosmetic and in-place churn.
    static List<CodeBlockEffort> calculateConfigFileEfforts(List<FileChange> fileChanges, double modifyMult, double configMult) {
        if (CollectionUtils.isEmpty(fileChanges)) {
            return Lists.newArrayList();
        }

        List<CodeBlockEffort> toReturn = Lists.newArrayList();
        for (FileChange fc : fileChanges) {
            if (!fc.isConfig()) {
                continue;
            }
            // seed from added + deleted (like a Java block's totalLinesChanged) so the diff
            // classification factor effectiveLines/(added+deleted) collapses each delete+add pair
            // exactly once in recompute — seeding with max() would collapse the pair a second time
            int rawLines = fc.getLinesAdded() + fc.getLinesDeleted();
            if (rawLines <= 0) {
                continue;
            }

            double driverScore = rawLines;
            double effort = driverScore * modifyMult * configMult;
            // bucketBaseline 0 keeps config out of the global-cap baseline; isConfig excludes it from
            // the effective-statements metric
            toReturn.add(new CodeBlockEffort(fc.getPath(), fc.getPath(), null,
                    LlmScoringRequest.Operation.MODIFY, 0, 0,
                    0, 0, 0, rawLines, 0.0,
                    0.0, 0.0, 0.0,
                    driverScore, (int) Math.round(driverScore), effort, 0.0, false,
                    0.0, 0.0, false, 0.0, false,
                    0, 0, 0, true));
        }
        return toReturn;
    }
    static List<CodeBlockEffort> applyAbuseSignals(List<CodeBlockEffort> initialEfforts, double totalEffortRaw, boolean globalCapApplied, double maxDeviation) {
        if (CollectionUtils.isEmpty(initialEfforts)) {
            return initialEfforts;
        }
        List<CodeBlockEffort> toReturn = Lists.newArrayListWithCapacity(initialEfforts.size());
        for (CodeBlockEffort cbe : initialEfforts) {
            double effortShare = totalEffortRaw > 0 ? cbe.getEffort() / totalEffortRaw : 0.0;
            boolean globalCapDriver = globalCapApplied && effortShare > maxDeviation;
            toReturn.add(new CodeBlockEffort(cbe.getFile(), cbe.getName(), cbe.getSignature(),
                    cbe.getOperation(), cbe.getNonCommentCodeStatements(), cbe.getDirectInvocationCount(),
                    cbe.getEffectiveInvocationsChanged(), cbe.getNonCommentCodeLines(), cbe.getCommentLines(),
                    cbe.getEffectiveLinesChanged(), cbe.getChangeRatio(),
                    cbe.getScaledLines(), cbe.getScaledNcss(), cbe.getScaledInvocations(),
                    cbe.getDriverScore(), cbe.getCappedStatements(), cbe.getEffort(), cbe.getBucketBaseline(), cbe.isTest(),
                    cbe.getBlockRatioDeviationNcss(), cbe.getBlockRatioDeviationInvocations(), cbe.isBlockRatioOutlier(),
                    effortShare, globalCapDriver,
                    cbe.getBodyStartLine(), cbe.getBodyEndLine(), cbe.getBodyCodeLines(), cbe.isConfig()));
        }
        return toReturn;
    }
    private static double bucketRatio(DriverScaler.DimensionStats numerator, DriverScaler.DimensionStats denominator) {
        if (denominator.p50() <= 0.0) {
            return 0.0;
        }
        return numerator.p50() / denominator.p50();
    }
    private static double relativeDeviation(int blockNumerator, int blockDenominator, double bucketRatio) {
        if (blockDenominator <= 0 || bucketRatio <= 0.0) {
            return 0.0;
        }
        double blockRatio = (double) blockNumerator / blockDenominator;
        return Math.abs(blockRatio - bucketRatio) / bucketRatio;
    }
    private static DriverScaler selectScaler(
            CodeBlockChange block,
            DriverScaler methodScalerProd,
            DriverScaler methodScalerTest,
            DriverScaler constructorScalerProd,
            DriverScaler constructorScalerTest) {
        if (block.isConstructor()) {
            return block.isTest() ? constructorScalerTest : constructorScalerProd;
        }
        return block.isTest() ? methodScalerTest : methodScalerProd;
    }
    private static int selectBucketQuantile(
            CodeBlockChange block,
            int methodCapQuantileProd,
            int methodCapQuantileTest,
            int constructorCapQuantileProd,
            int constructorCapQuantileTest) {
        int primary;
        int fallback;
        if (block.isConstructor()) {
            primary = block.isTest() ? constructorCapQuantileTest : constructorCapQuantileProd;
            fallback = block.isTest() ? constructorCapQuantileProd : constructorCapQuantileTest;
        } else {
            primary = block.isTest() ? methodCapQuantileTest : methodCapQuantileProd;
            fallback = block.isTest() ? methodCapQuantileProd : methodCapQuantileTest;
        }
        return primary > 0 ? primary : fallback;
    }
    private static double computeChangeRatio(CodeBlockChange block) {
        if (block.getBodyCodeLines() <= 0) {
            return 0.0;
        }
        double ratio = (double) block.getTotalLinesChanged() / block.getBodyCodeLines();
        return Math.min(ratio, 1.0);
    }
    static List<FileEffort> groupByFile(List<CodeBlockEffort> blockEfforts, double maxDeviation) {
        if (CollectionUtils.isEmpty(blockEfforts)) {
            return Lists.newArrayList();
        }
        Map<String, List<CodeBlockEffort>> byFile = blockEfforts.stream()
                .collect(Collectors.groupingBy(CodeBlockEffort::getFile, Collectors.toList()));

        return byFile.entrySet().stream().map(entry -> {
            List<CodeBlockEffort> blocks = entry.getValue();
            double totalEffort = blocks.stream().mapToDouble(CodeBlockEffort::getEffort).sum();
            boolean isTest = blocks.get(0).isTest();

            int outliers = (int) blocks.stream().filter(CodeBlockEffort::isBlockRatioOutlier).count();
            int capDrivers = (int) blocks.stream().filter(CodeBlockEffort::isGlobalCapDriver).count();
            double maxDevNcss = blocks.stream().mapToDouble(CodeBlockEffort::getBlockRatioDeviationNcss).max().orElse(0.0);
            double maxDevInvocs = blocks.stream().mapToDouble(CodeBlockEffort::getBlockRatioDeviationInvocations).max().orElse(0.0);
            boolean abusive = outliers * 2 > blocks.size();

            return new FileEffort(entry.getKey(), totalEffort, isTest, blocks,
                    outliers, capDrivers, maxDevNcss, maxDevInvocs, abusive);
        }).sorted(Comparator.comparingDouble(FileEffort::getTotalEffort).reversed()).collect(Collectors.toList());
    }

    @Value
    @Builder(toBuilder = true)
    public static class PreComputedScores {
        long projectTotalStatements;
        int projectTotalMethods;
        int methodCapQuantileProd;
        int methodCapQuantileTest;
        int constructorCapQuantileProd;
        int constructorCapQuantileTest;
        double sizeFactor;
        double modifyMult;
        double addMult;
        int linesChanged;
        int totalEffectiveStatements;
        int filesChanged;
        double filesScopeMultiplier;
        double blockEffortSum;
        double totalEffortRaw;
        double totalBaseline;
        double globalCap;
        boolean globalCapApplied;
        boolean globalCapDryRun;
        int codeBlocksModified;
        int codeBlocksAdded;
        int classesModified;
        int classesAdded;
        double testCodeScoreMultiplier;
        int testLinesChanged;
        int testCodeBlocksModified;
        int testCodeBlocksAdded;
        int testClassesModified;
        int testClassesAdded;
        int testFilesChanged;
        double volumeScore;
        double volumeExponent;
        double baseEffort;
        double cpdEffectivePenalty;
        CpdCategory cpdCategory;
        double cpdRecommendedImpact;
        int cpdTotalClones;
        int cpdIntroducedClones;
        int cpdTestOnlyClones;
        int staticAnalysisErrorCount;
        int staticAnalysisIntroducedCount;
        int staticAnalysisPreExistingCount;
        StaticAnalysisCategory staticAnalysisCategory;
        double staticAnalysisRecommendedImpact;
        @Builder.Default
        List<CodeBlockEffort> codeBlockEfforts = Lists.newArrayList();
        @Builder.Default
        List<FileEffort> fileEfforts = Lists.newArrayList();
    }

    @Value
    public static class CpdPreComputed {
        double effectivePenalty;
        CpdCategory category;
        double recommendedImpact;
        int totalClones;
        int introducedClones;
        int testOnlyClones;
    }

    public enum CpdCategory {
        CLEAN,
        ACCEPTABLE,
        MODERATE,
        HIGH,
        SEVERE
    }

    public enum StaticAnalysisCategory {
        CLEAN,
        HAS_VIOLATIONS
    }

    @Value
    public static class StaticAnalysisPreComputed {
        int errorCount;
        int introducedCount;
        int preExistingCount;
        StaticAnalysisCategory category;
        double recommendedImpact;
    }

    @Value
    public static class CodeBlockEffort {
        String file;
        String name;
        String signature;
        LlmScoringRequest.Operation operation;
        int nonCommentCodeStatements;
        int directInvocationCount;
        int effectiveInvocationsChanged;
        int nonCommentCodeLines;
        int commentLines;
        int effectiveLinesChanged;
        double changeRatio;
        double scaledLines;
        double scaledNcss;
        double scaledInvocations;
        double driverScore;
        int cappedStatements;
        double effort;
        double bucketBaseline;
        boolean isTest;
        double blockRatioDeviationNcss;
        double blockRatioDeviationInvocations;
        boolean blockRatioOutlier;
        double effortShare;
        boolean globalCapDriver;
        int bodyStartLine;
        int bodyEndLine;
        int bodyCodeLines;
        boolean isConfig;
    }

    @Value
    public static class FileEffort {
        String file;
        double totalEffort;
        boolean isTest;
        List<CodeBlockEffort> codeBlockEfforts;
        int blocksFlaggedAsRatioOutlier;
        int blocksFlaggedAsGlobalCapDriver;
        double maxBlockRatioDeviationNcss;
        double maxBlockRatioDeviationInvocations;
        boolean fileFlaggedAsAbusive;
    }
}
