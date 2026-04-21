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

        List<CodeBlockEffort> codeBlockEfforts = calculateCodeBlockEfforts(
                request.getCodeBlockChanges(),
                request.getMethodScalerProd(), request.getMethodScalerTest(),
                request.getConstructorScalerProd(), request.getConstructorScalerTest(),
                methodCapQuantileProd, methodCapQuantileTest,
                constructorCapQuantileProd, constructorCapQuantileTest,
                args.getStatementsDensityCapMultiplier(), addMult, modifyMult, testMult);
        List<FileEffort> fileEfforts = groupByFile(codeBlockEfforts);

        double blockEffortSum = codeBlockEfforts.stream().mapToDouble(CodeBlockEffort::getEffort).sum();
        double volumeScore = Math.pow(blockEffortSum, args.getVolumeExponent());

        int filesChanged = changeSummary.getTotalFilesChanged();
        double filesScopeMultiplier = 1.0;
        if (filesChanged > 1) {
            double logBonus = Math.log(filesChanged) / Math.log(2) * args.getFilesScopeLogCoefficient();
            filesScopeMultiplier = 1.0 + Math.min(logBonus, args.getFilesScopeMaxBonus());
        }

        double totalVolumeScore = volumeScore * filesScopeMultiplier;
        double baseEffort = totalVolumeScore;

        int totalEffectiveStatements = codeBlockEfforts.stream().mapToInt(CodeBlockEffort::getCappedStatements).sum();

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
            double densityCapMultiplier,
            double addMult,
            double modifyMult,
            double testMult) {
        if (CollectionUtils.isEmpty(codeBlocks)) {
            return Lists.newArrayList();
        }
        int maxMethodProd = (int) (methodCapQuantileProd * densityCapMultiplier);
        int maxMethodTest = (int) (methodCapQuantileTest * densityCapMultiplier);
        int maxCtorProd = (int) (constructorCapQuantileProd * densityCapMultiplier);
        int maxCtorTest = (int) (constructorCapQuantileTest * densityCapMultiplier);
        boolean hasCap = BooleanUtils.or(new boolean[] { maxMethodProd > 0, maxMethodTest > 0, maxCtorProd > 0, maxCtorTest > 0 });

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
            if (block.isModify()) {
                int linesChanged = Math.min(block.getTotalLinesChanged(), block.getNonCommentCodeLines());
                changeRatio = computeChangeRatio(block);
                invocationsChanged = block.getEffectiveInvocationsChanged();
                driverScore = DriverScore.forModify(scaler, linesChanged, invocationsChanged);
                projectedLines = linesChanged;
                projectedNcss = 0.0;
                projectedInvocations = invocationsChanged * scaler.invocationsFactor();
            } else {
                driverScore = DriverScore.forNew(scaler, block.getNonCommentCodeLines(),
                        block.getNonCommentCodeStatements(), block.getDirectInvocationCount());
                projectedLines = block.getNonCommentCodeLines();
                projectedNcss = block.getNonCommentCodeStatements() * scaler.ncssFactor();
                projectedInvocations = block.getDirectInvocationCount() * scaler.invocationsFactor();
            }

            int cap = selectCap(block, maxMethodProd, maxMethodTest, maxCtorProd, maxCtorTest);
            boolean applyCap = hasCap && cap > 0;
            int cappedStatements = (int) Math.round(applyCap ? Math.min(driverScore, cap) : driverScore);
            double operationMult = block.isNew() ? addMult : modifyMult;
            double testWeight = block.isTest() ? testMult : 1.0;
            double effort = Precision.round(cappedStatements * operationMult * testWeight, ROUNDING_PRECISION);

            toReturn.add(new CodeBlockEffort(block.getFile(), block.getName(), block.getSignature(),
                    block.getOperation(), block.getNonCommentCodeStatements(), block.getDirectInvocationCount(),
                    invocationsChanged, block.getNonCommentCodeLines(), block.getCommentLines(), block.getTotalLinesChanged(),
                    Precision.round(changeRatio, ROUNDING_PRECISION),
                    Precision.round(projectedLines, ROUNDING_PRECISION),
                    Precision.round(projectedNcss, ROUNDING_PRECISION),
                    Precision.round(projectedInvocations, ROUNDING_PRECISION),
                    driverScore, cappedStatements, effort, block.isTest()));
        }
        return toReturn;
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
    private static int selectCap(
            CodeBlockChange block,
            int maxMethodProd,
            int maxMethodTest,
            int maxCtorProd,
            int maxCtorTest) {
        int primary;
        int fallback;
        if (block.isConstructor()) {
            primary = block.isTest() ? maxCtorTest : maxCtorProd;
            fallback = block.isTest() ? maxCtorProd : maxCtorTest;
        } else {
            primary = block.isTest() ? maxMethodTest : maxMethodProd;
            fallback = block.isTest() ? maxMethodProd : maxMethodTest;
        }
        return primary > 0 ? primary : fallback;
    }
    private static double computeChangeRatio(CodeBlockChange block) {
        if (block.getNonCommentCodeLines() <= 0) {
            return 0.0;
        }
        double ratio = (double) block.getTotalLinesChanged() / block.getNonCommentCodeLines();
        return Math.min(ratio, 1.0);
    }
    static List<FileEffort> groupByFile(List<CodeBlockEffort> blockEfforts) {
        if (CollectionUtils.isEmpty(blockEfforts)) {
            return Lists.newArrayList();
        }
        Map<String, List<CodeBlockEffort>> byFile = blockEfforts.stream()
                .collect(Collectors.groupingBy(CodeBlockEffort::getFile, Collectors.toList()));

        return byFile.entrySet().stream().map(entry -> {
            List<CodeBlockEffort> blocks = entry.getValue();
            double totalEffort = Precision.round(
                    blocks.stream().mapToDouble(CodeBlockEffort::getEffort).sum(), ROUNDING_PRECISION);
            boolean isTest = blocks.get(0).isTest();
            return new FileEffort(entry.getKey(), totalEffort, isTest, blocks);
        }).sorted(Comparator.comparingDouble(FileEffort::getTotalEffort).reversed()).collect(Collectors.toList());
    }

    @Value
    @Builder
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
        boolean isTest;
    }

    @Value
    public static class FileEffort {
        String file;
        double totalEffort;
        boolean isTest;
        List<CodeBlockEffort> codeBlockEfforts;
    }
}
