package io.codiqo.llm;

import lombok.Builder;
import lombok.Data;
import lombok.Value;

@Data
@Builder
public class ScoringConfig {
    /**
     * size_factor = CBRT(lines) / 100 → gives ~0.2-0.8 for typical projects
     */
    public static final double SIZE_FACTOR_DIVISOR = 100.0;

    /**
     * modify_mult = 1.0 + MIN(size_factor * 0.3, 0.2) → caps at 1.2
     */
    public static final double MODIFY_MULTIPLIER_SCALE = 0.3;
    public static final double MODIFY_MULTIPLIER_CAP = 0.2;

    /**
     * add_mult = 1.0 + (0.1 / (1 + size_factor)) → ~1.05-1.08 range
     */
    public static final double ADD_MULTIPLIER_SCALE = 0.1;

    /**
     * rel_adj = clamp(1 + 0.1 * LN(project_lines / changed_lines), 0.8, 1.3)
     */
    public static final double RELATIVE_ADJUSTMENT_FACTOR = 0.1;
    public static final double RELATIVE_ADJUSTMENT_MIN = 0.8;
    public static final double RELATIVE_ADJUSTMENT_MAX = 1.3;

    /**
     * Quality multiplier bounds
     */
    public static final double QUALITY_MULTIPLIER_MIN = 0.5;
    public static final double QUALITY_MULTIPLIER_MAX = 1.2;

    /**
     * Penalty caps prevent death by a thousand cuts
     */
    public static final double STATIC_ANALYSIS_PENALTY_CAP = 0.30;
    public static final double ARCHITECTURE_PENALTY_CAP = 0.20;
    public static final double QUALITY_GATE_PENALTY_CAP = 0.15;

    @Builder.Default
    private VolumeScoring volume = VolumeScoring.defaults();
    @Builder.Default
    private ComplexityScoring complexity = ComplexityScoring.defaults();
    @Builder.Default
    private QualityImpacts qualityImpacts = QualityImpacts.defaults();
    @Builder.Default
    private QualityDimensions qualityDimensions = QualityDimensions.defaults();

    public static ScoringConfig defaults() {
        return ScoringConfig.builder().build();
    }

    /**
     * Logarithmic volume scoring: score = LN(1 + count) × factor × multiplier
     *
     * Example: 100 lines → 23 points, 1000 lines → 35 points (10× input = 1.5× output)
     *
     * @see <a href="https://en.wikipedia.org/wiki/Logarithmic_scale">Logarithmic scale</a>
     */
    @Value
    @Builder
    public static class VolumeScoring {
        @Builder.Default
        double linesLogFactor = 5.0;
        @Builder.Default
        double methodsModifiedLogFactor = 6.0;
        @Builder.Default
        double methodsAddedLogFactor = 8.0;
        @Builder.Default
        double classesModifiedLogFactor = 5.0;
        @Builder.Default
        double classesAddedLogFactor = 8.0;

        public static VolumeScoring defaults() {
            return VolumeScoring.builder().build();
        }
    }

    /**
     * Complexity multipliers based on cyclomatic complexity.
     * MODIFY = bonus (reward tackling hard code), CREATE = penalty for high complexity.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Cyclomatic_complexity">Cyclomatic complexity</a>
     */
    @Value
    @Builder
    public static class ComplexityScoring {
        @Builder.Default
        int trivialMax = 5;
        @Builder.Default
        int moderateMax = 10;
        @Builder.Default
        int complexMax = 20;

        /**
         * MODIFY existing code: bonus for handling complexity
         */
        @Builder.Default
        double modifyTrivialMultiplier = 1.0;
        @Builder.Default
        double modifyModerateMultiplier = 1.1;
        @Builder.Default
        double modifyComplexMultiplier = 1.2;
        @Builder.Default
        double modifyHighlyComplexMultiplier = 1.3;

        /**
         * CREATE new code: penalty for unnecessary complexity
         */
        @Builder.Default
        double createTrivialMultiplier = 1.1; // bonus for clean code
        @Builder.Default
        double createModerateMultiplier = 1.0;
        @Builder.Default
        double createComplexMultiplier = 0.9;
        @Builder.Default
        double createHighlyComplexMultiplier = 0.7;

        public static ComplexityScoring defaults() {
            return ComplexityScoring.builder().build();
        }
    }

    /**
     * Quality multiplier = 1.0 + sum(impacts), clamped to [0.5, 1.2]
     */
    @Value
    @Builder
    public static class QualityImpacts {
        /**
         * CPD: 0-5% clean, 6-15% neutral, 16-25% moderate, 26-40% high, >40% severe
         */
        @Builder.Default
        double cpdCleanBonus = 0.05;
        @Builder.Default
        double cpdModeratePenalty = -0.10;
        @Builder.Default
        double cpdHighPenalty = -0.20;
        @Builder.Default
        double cpdSeverePenalty = -0.30;

        /**
         * PMD: per violation in changed lines
         */
        @Builder.Default
        double pmdPriority1Penalty = -0.05;
        @Builder.Default
        double pmdPriority2Penalty = -0.03;
        @Builder.Default
        double pmdPriority3Penalty = -0.01;

        /**
         * SpotBugs: per issue in changed lines
         */
        @Builder.Default
        double spotbugsScariestPenalty = -0.08; // rank 1-4
        @Builder.Default
        double spotbugsScaryPenalty = -0.04; // rank 5-9
        @Builder.Default
        double spotbugsTroublingPenalty = -0.02; // rank 10-14

        /**
         * Coverage of changed code
         */
        @Builder.Default
        double coverageExcellentBonus = 0.10; // ≥90%
        @Builder.Default
        double coverageGoodBonus = 0.05; // 80-89%
        @Builder.Default
        double coverageLowPenalty = -0.05; // 60-69%
        @Builder.Default
        double coveragePoorPenalty = -0.10; // 50-59%
        @Builder.Default
        double coverageTerriblePenalty = -0.15; // <50%

        /**
         * Architecture/SOLID
         */
        @Builder.Default
        double architectureMinorPenalty = -0.01;
        @Builder.Default
        double architectureSolidPenalty = -0.03;
        @Builder.Default
        double architectureMajorPenalty = -0.05;
        @Builder.Default
        double qualityGateFailurePenalty = -0.03;

        public static QualityImpacts defaults() {
            return QualityImpacts.builder().build();
        }
    }

    @Value
    @Builder
    public static class QualityDimensions {
        @Builder.Default
        int architectureImpactScoreThreshold = 7;
        @Builder.Default
        int architectureImpactCoverageRequired = 80;

        @Builder.Default
        int concurrencyRiskThreshold = 3;
        @Builder.Default
        int integrationSurfaceThreshold = 5;
        @Builder.Default
        int dataIntegrityThreshold = 5;
        @Builder.Default
        int securitySensitivityThreshold = 5;
        @Builder.Default
        int scalabilityImpactThreshold = 5;
        @Builder.Default
        int observabilityThreshold = 5;
        @Builder.Default
        int resilienceThreshold = 5;
        @Builder.Default
        int performanceThreshold = 5;

        public static QualityDimensions defaults() {
            return QualityDimensions.builder().build();
        }
    }
}