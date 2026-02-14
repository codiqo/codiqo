package io.codiqo.llm;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.math3.util.Precision;

import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.DiagnosticInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo.CloneDetail;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;

@RequiredArgsConstructor
public class VolumeScoreCalculator {
    private static final int ROUNDING_PRECISION = 2;
    private static final int CPD_ROUNDING_PRECISION = 1;
    private static final double FULL_DENSITY = 1.0;

    private final RunArgs args;

    public PreComputedScores calculate(LlmScoringRequest request, long projectTotalLines, int projectTotalMethods, int linesPerMethodQuantile) {
        ChangeSummary changeSummary = request.getChangeSummary();
        double exponent = args.getVolumeExponent();
        double sizeFactor = Math.cbrt(projectTotalLines) / args.getSizeFactorDivisor();
        double modifyMult = 1.0 + Math.min(sizeFactor * args.getModifyMultiplierScale(), args.getModifyMultiplierCap());
        double addMult = 1.0 + args.getAddMultiplierScale() / (1.0 + sizeFactor);
        int linesChanged = changeSummary.getTotalLinesChanged();
        int effectiveLines = applyLinesDensityCap(linesChanged, request.getCodeBlockChanges(), linesPerMethodQuantile);
        int filesChanged = changeSummary.getTotalFilesChanged();
        double linesScore = powerScore(effectiveLines, args.getLinesLogFactor(), 1.0, exponent);
        double codeBlocksModifiedScore = powerScore(changeSummary.getCodeBlocksModified(), args.getCodeBlocksModifiedLogFactor(), modifyMult, exponent);
        double codeBlocksAddedScore = powerScore(changeSummary.getCodeBlocksAdded(), args.getCodeBlocksAddedLogFactor(), addMult, exponent);
        double classesModifiedScore = powerScore(changeSummary.getClassesModified(), args.getClassesModifiedLogFactor(), modifyMult, exponent);
        double classesAddedScore = powerScore(changeSummary.getClassesAdded(), args.getClassesAddedLogFactor(), addMult, exponent);
        double contentScore = linesScore + codeBlocksModifiedScore + codeBlocksAddedScore + classesModifiedScore + classesAddedScore;
        double rawScopeBonus = powerScore(filesChanged, args.getFilesScopeFactor(), 1.0, exponent);
        double fileDensity = calculateFileDensity(linesChanged, filesChanged, args.getFileDensityThreshold());
        double filesScopeMultiplier = FULL_DENSITY + rawScopeBonus * fileDensity;
        double totalVolumeScore = contentScore * filesScopeMultiplier;
        double baseEffort = totalVolumeScore * args.getDefaultComplexityMultiplier();

        CpdPreComputed cpd = calculateCpdPenalty(request);
        StaticAnalysisPreComputed sa = calculateStaticAnalysisPenalty(request);

        return PreComputedScores.builder()
                .projectTotalLines(projectTotalLines)
                .projectTotalMethods(projectTotalMethods)
                .linesPerMethodQuantile(linesPerMethodQuantile)
                .sizeFactor(Precision.round(sizeFactor, ROUNDING_PRECISION))
                .modifyMult(Precision.round(modifyMult, ROUNDING_PRECISION))
                .addMult(Precision.round(addMult, ROUNDING_PRECISION))
                .linesChanged(linesChanged)
                .effectiveLines(effectiveLines)
                .linesScore(Precision.round(linesScore, ROUNDING_PRECISION))
                .filesChanged(filesChanged)
                .contentScore(Precision.round(contentScore, ROUNDING_PRECISION))
                .filesScopeMultiplier(Precision.round(filesScopeMultiplier, ROUNDING_PRECISION))
                .fileDensity(Precision.round(fileDensity, ROUNDING_PRECISION))
                .codeBlocksModified(changeSummary.getCodeBlocksModified())
                .codeBlocksModifiedScore(Precision.round(codeBlocksModifiedScore, ROUNDING_PRECISION))
                .codeBlocksAdded(changeSummary.getCodeBlocksAdded())
                .codeBlocksAddedScore(Precision.round(codeBlocksAddedScore, ROUNDING_PRECISION))
                .classesModified(changeSummary.getClassesModified())
                .classesModifiedScore(Precision.round(classesModifiedScore, ROUNDING_PRECISION))
                .classesAdded(changeSummary.getClassesAdded())
                .classesAddedScore(Precision.round(classesAddedScore, ROUNDING_PRECISION))
                .volumeScore(Precision.round(totalVolumeScore, ROUNDING_PRECISION))
                .defaultComplexityMultiplier(args.getDefaultComplexityMultiplier())
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
                        if (BooleanUtils.and(new boolean[]{diag.getSeverity() == LlmScoringRequest.DiagnosticSeverity.ERROR, Objects.nonNull(diag.getRuleId())})) {
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
    private int applyLinesDensityCap(int linesChanged, List<CodeBlockChange> codeBlocks, int linesPerMethodQuantile) {
        if (CollectionUtils.isEmpty(codeBlocks) || linesPerMethodQuantile <= 0) {
            return linesChanged;
        }

        int maxPerBlock = (int) (linesPerMethodQuantile * args.getLinesDensityCapMultiplier());
        int maxEffectiveLines = 0;
        for (CodeBlockChange block : codeBlocks) {
            if (block.isDelete()) {
                continue;
            }
            maxEffectiveLines += Math.min(block.getLinesOfCode(), maxPerBlock);
        }
        if (maxEffectiveLines <= 0) {
            return linesChanged;
        }
        return Math.min(linesChanged, maxEffectiveLines);
    }
    private static double calculateFileDensity(int linesChanged, int filesChanged, double threshold) {
        if (filesChanged <= 0) {
            return FULL_DENSITY;
        }
        double avgLinesPerFile = (double) linesChanged / filesChanged;
        return Math.min(FULL_DENSITY, avgLinesPerFile / threshold);
    }
    private static double powerScore(int count, double scoreFactor, double multiplier, double exponent) {
        if (count > 0) {
            return Math.pow(count, exponent) * scoreFactor * multiplier;
        }
        return 0.0;
    }

    @Value
    @Builder
    public static class PreComputedScores {
        long projectTotalLines;
        int projectTotalMethods;
        int linesPerMethodQuantile;
        double sizeFactor;
        double modifyMult;
        double addMult;
        int linesChanged;
        int effectiveLines;
        double linesScore;
        int filesChanged;
        double contentScore;
        double filesScopeMultiplier;
        double fileDensity;
        int codeBlocksModified;
        double codeBlocksModifiedScore;
        int codeBlocksAdded;
        double codeBlocksAddedScore;
        int classesModified;
        double classesModifiedScore;
        int classesAdded;
        double classesAddedScore;
        double volumeScore;
        double defaultComplexityMultiplier;
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
}
