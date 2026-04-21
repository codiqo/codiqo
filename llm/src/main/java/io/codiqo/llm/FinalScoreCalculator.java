package io.codiqo.llm;

import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.math3.util.Precision;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.CodeBlockEffort;
import io.codiqo.llm.VolumeScoreCalculator.FileEffort;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockEffortView;
import io.codiqo.llm.schema.LlmScoringResponse.FileEffortView;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FinalScoreCalculator {
    private static final int ROUNDING_PRECISION = 2;

    private final RunArgs args;

    public void apply(LlmScoringResponse response, PreComputedScores preComputed) {
        double baseEffort = preComputed.getBaseEffort();
        if (Objects.nonNull(response.getEffortBreakdown()) && Objects.nonNull(response.getEffortBreakdown().getComplexityMultiplier())) {
            double llmComplexity = response.getEffortBreakdown().getComplexityMultiplier().getCombinedMultiplier();
            if (llmComplexity > 0) {
                baseEffort = preComputed.getVolumeScore() * llmComplexity;
            }
        }
        if (Objects.nonNull(response.getEffortBreakdown())) {
            response.getEffortBreakdown().setBaseEffortScore(Precision.round(baseEffort, ROUNDING_PRECISION));
            response.getEffortBreakdown().setFileEfforts(
                    preComputed.getFileEfforts().stream()
                            .map(FinalScoreCalculator::toFileEffortView)
                            .collect(Collectors.toList()));
        }

        double rawQualityMultiplier = 1.0;
        if (Objects.nonNull(response.getQualityMultiplier())) {
            rawQualityMultiplier = response.getQualityMultiplier().getFinalMultiplier();
        }

        double clampedQualityMultiplier = Math.max(
                args.getQualityMultiplierMin(),
                Math.min(args.getQualityMultiplierMax(), rawQualityMultiplier));
        if (Objects.isNull(response.getQualityMultiplier())) {
            response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(clampedQualityMultiplier).build());
        } else {
            response.getQualityMultiplier().setFinalMultiplier(clampedQualityMultiplier);
        }

        int architectureImpactScore = 0;
        double qualityFactor = 1.0;
        if (Objects.nonNull(response.getArchitectureEffortBonus())) {
            architectureImpactScore = response.getArchitectureEffortBonus().getArchitectureImpactScore();
            qualityFactor = response.getArchitectureEffortBonus().getQualityFactor();
            qualityFactor = Math.max(0.0, Math.min(1.0, qualityFactor));
        }

        double architectureBonus = architectureImpactScore * baseEffort * args.getArchitectureBonusFactor() * qualityFactor;
        architectureBonus = Precision.round(architectureBonus, ROUNDING_PRECISION);
        String bonusCalculation = String.format(
                "Impact Score (%d/10) × Base Effort (%.2f) × Bonus Factor (%.3f) × Quality Factor (%.2f) = +%.2f",
                architectureImpactScore,
                baseEffort,
                args.getArchitectureBonusFactor(),
                qualityFactor,
                architectureBonus);

        if (Objects.isNull(response.getArchitectureEffortBonus())) {
            response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                    .architectureImpactScore(architectureImpactScore)
                    .qualityFactor(qualityFactor)
                    .baseEffort(Precision.round(baseEffort, ROUNDING_PRECISION))
                    .bonusCalculation(bonusCalculation)
                    .bonusPoints(architectureBonus)
                    .build());
        } else {
            ArchitectureEffortBonus bonus = response.getArchitectureEffortBonus();
            bonus.setQualityFactor(qualityFactor);
            bonus.setBaseEffort(Precision.round(baseEffort, ROUNDING_PRECISION));
            bonus.setBonusCalculation(bonusCalculation);
            bonus.setBonusPoints(architectureBonus);
        }

        double finalScore = baseEffort * clampedQualityMultiplier + architectureBonus;
        finalScore = Math.round(finalScore);
        String scoreCalculation = String.format("%.2f × %.2f + %.2f = %.2f ≈ %.0f",
                baseEffort,
                clampedQualityMultiplier,
                architectureBonus,
                baseEffort * clampedQualityMultiplier + architectureBonus,
                finalScore);
        response.setScore(finalScore);
        response.setScoreCalculation(scoreCalculation);
    }
    private static FileEffortView toFileEffortView(FileEffort fe) {
        return FileEffortView.builder()
                .file(fe.getFile())
                .totalEffort(fe.getTotalEffort())
                .isTest(fe.isTest())
                .codeBlockEfforts(fe.getCodeBlockEfforts().stream()
                        .map(FinalScoreCalculator::toCodeBlockEffortView)
                        .collect(Collectors.toList()))
                .build();
    }
    private static CodeBlockEffortView toCodeBlockEffortView(CodeBlockEffort cbe) {
        return CodeBlockEffortView.builder()
                .name(cbe.getName())
                .signature(cbe.getSignature())
                .operation(cbe.getOperation().name())
                .nonCommentCodeStatements(cbe.getNonCommentCodeStatements())
                .directInvocationCount(cbe.getDirectInvocationCount())
                .effectiveInvocationsChanged(cbe.getEffectiveInvocationsChanged())
                .nonCommentCodeLines(cbe.getNonCommentCodeLines())
                .commentLines(cbe.getCommentLines())
                .effectiveLinesChanged(cbe.getEffectiveLinesChanged())
                .changeRatio(cbe.getChangeRatio())
                .scaledLines(cbe.getScaledLines())
                .scaledNcss(cbe.getScaledNcss())
                .scaledInvocations(cbe.getScaledInvocations())
                .driverScore(cbe.getDriverScore())
                .cappedStatements(cbe.getCappedStatements())
                .effort(cbe.getEffort())
                .isTest(cbe.isTest())
                .build();
    }
}
