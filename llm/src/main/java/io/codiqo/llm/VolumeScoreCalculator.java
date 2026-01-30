package io.codiqo.llm;

import java.math.BigDecimal;
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
import io.codiqo.llm.schema.LlmScoringRequest.MethodChange;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;

@RequiredArgsConstructor
public class VolumeScoreCalculator {
    private static final int ROUNDING_PRECISION = 2;
    private static final int CPD_ROUNDING_PRECISION = 1;

    private final RunArgs args;

    public PreComputedScores calculate(LlmScoringRequest request, long projectTotalLines) {
        ChangeSummary changeSummary = request.getChangeSummary();
        double sizeFactor = Math.cbrt(projectTotalLines) / args.getSizeFactorDivisor();
        double modifyMult = 1.0 + Math.min(sizeFactor * args.getModifyMultiplierScale(), args.getModifyMultiplierCap());
        double addMult = 1.0 + args.getAddMultiplierScale() / (1.0 + sizeFactor);
        int linesChanged = changeSummary.getTotalLinesChanged();
        double relativeAdj = 1.0;
        if (linesChanged > 0 && projectTotalLines > 0) {
            relativeAdj = 1.0 + args.getRelativeAdjustmentFactor() * Math.log((double) projectTotalLines / linesChanged);
            relativeAdj = Math.max(args.getRelativeAdjustmentMin(), Math.min(relativeAdj, args.getRelativeAdjustmentMax()));
        }
        double linesScore = logScore(linesChanged, args.getLinesLogFactor(), relativeAdj);
        double methodsModifiedScore = logScore(changeSummary.getMethodsModified(), args.getMethodsModifiedLogFactor(), modifyMult);
        double methodsAddedScore = logScore(changeSummary.getMethodsAdded(), args.getMethodsAddedLogFactor(), addMult);
        double classesModifiedScore = logScore(changeSummary.getClassesModified(), args.getClassesModifiedLogFactor(), modifyMult);
        double classesAddedScore = logScore(changeSummary.getClassesAdded(), args.getClassesAddedLogFactor(), addMult);
        double totalVolumeScore = linesScore + methodsModifiedScore + methodsAddedScore + classesModifiedScore + classesAddedScore;
        double baseEffort = totalVolumeScore * args.getDefaultComplexityMultiplier();

        CpdPreComputed cpd = calculateCpdPenalty(request);
        StaticAnalysisPreComputed sa = calculateStaticAnalysisPenalty(request);

        return PreComputedScores.builder()
                .sizeFactor(Precision.round(sizeFactor, ROUNDING_PRECISION))
                .modifyMult(Precision.round(modifyMult, ROUNDING_PRECISION))
                .addMult(Precision.round(addMult, ROUNDING_PRECISION))
                .relativeAdj(Precision.round(relativeAdj, ROUNDING_PRECISION))
                .linesChanged(linesChanged)
                .linesScore(Precision.round(linesScore, ROUNDING_PRECISION))
                .methodsModified(changeSummary.getMethodsModified())
                .methodsModifiedScore(Precision.round(methodsModifiedScore, ROUNDING_PRECISION))
                .methodsAdded(changeSummary.getMethodsAdded())
                .methodsAddedScore(Precision.round(methodsAddedScore, ROUNDING_PRECISION))
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
        if (Objects.nonNull(request.getMethodChanges())) {
            for (MethodChange method : request.getMethodChanges()) {
                if (Objects.nonNull(method.getDiagnostics())) {
                    for (DiagnosticInfo diag : method.getDiagnostics()) {
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
    private static double logScore(int count, double logFactor, double multiplier) {
        if (count > 0) {
            return Math.log(1.0 + count) * logFactor * multiplier;
        }
        return BigDecimal.ZERO.doubleValue();
    }

    @Value
    @Builder
    public static class PreComputedScores {
        double sizeFactor;
        double modifyMult;
        double addMult;
        double relativeAdj;
        int linesChanged;
        double linesScore;
        int methodsModified;
        double methodsModifiedScore;
        int methodsAdded;
        double methodsAddedScore;
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
