package io.codiqo.llm.schema;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response structure from LLM contribution scoring.
 * Maps to the JSON output defined in the system prompt template.
 *
 * Used for developer velocity tracking and fair productivity measurement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmScoringResponse {

    /** LLM's contribution score (0 to configured max) */
    @JsonProperty("llm_contribution_score")
    private int llmContributionScore;

    /** LLM's thinking/analysis in markdown format */
    private String thinking;

    /** Effort dimension scores */
    @JsonProperty("effort_assessment")
    private EffortAssessment effortAssessment;

    /** Quality dimension scores */
    @JsonProperty("quality_assessment")
    private QualityAssessment qualityAssessment;

    /** Complexity analysis with NEW vs MODIFY distinction */
    @JsonProperty("complexity_analysis")
    private ComplexityAnalysis complexityAnalysis;

    /** Impact analysis based on callers */
    @JsonProperty("impact_analysis")
    private ImpactAnalysis impactAnalysis;

    /** Quality penalties applied */
    @JsonProperty("quality_penalties")
    private QualityPenalties qualityPenalties;

    /** Static analysis validation */
    @JsonProperty("static_analysis_review")
    private StaticAnalysisReview staticAnalysisReview;

    /** Bugs discovered by LLM */
    @JsonProperty("bugs_found")
    private List<BugFinding> bugsFound;

    /** Executive summary */
    private String summary;

    /** Tags for categorization */
    private Tags tags;

    /** Velocity tracking metrics */
    @JsonProperty("velocity_metrics")
    private VelocityMetrics velocityMetrics;

    // === EFFORT ASSESSMENT ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EffortAssessment {
        @JsonProperty("scope_of_work")
        private DimensionScore scopeOfWork;

        @JsonProperty("technical_difficulty")
        private DimensionScore technicalDifficulty;

        @JsonProperty("impact_breadth")
        private DimensionScore impactBreadth;

        public double getAverageScore() {
            int count = 0;
            int sum = 0;
            if (scopeOfWork != null && scopeOfWork.getScore() != null) {
                sum += scopeOfWork.getScore();
                count++;
            }
            if (technicalDifficulty != null && technicalDifficulty.getScore() != null) {
                sum += technicalDifficulty.getScore();
                count++;
            }
            if (impactBreadth != null && impactBreadth.getScore() != null) {
                sum += impactBreadth.getScore();
                count++;
            }
            return count > 0 ? (double) sum / count : 0;
        }
    }

    // === QUALITY ASSESSMENT ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityAssessment {
        @JsonProperty("code_design")
        private DimensionScore codeDesign;

        private DimensionScore completeness;

        private DimensionScore originality;

        public double getAverageScore() {
            int count = 0;
            int sum = 0;
            if (codeDesign != null && codeDesign.getScore() != null) {
                sum += codeDesign.getScore();
                count++;
            }
            if (completeness != null && completeness.getScore() != null) {
                sum += completeness.getScore();
                count++;
            }
            if (originality != null && originality.getScore() != null) {
                sum += originality.getScore();
                count++;
            }
            return count > 0 ? (double) sum / count : 0;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionScore {
        private Integer score; // 0-10
        private String rationale;
    }

    // === COMPLEXITY ANALYSIS ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplexityAnalysis {
        @JsonProperty("methods_modified")
        private List<MethodComplexityEntry> methodsModified;

        @JsonProperty("methods_added")
        private List<MethodComplexityEntry> methodsAdded;

        @JsonProperty("complexity_bonus")
        private double complexityBonus; // Can be negative (penalty)

        @JsonProperty("complexity_summary")
        private String complexitySummary;

        public int getModifiedMethodCount() {
            return methodsModified != null ? methodsModified.size() : 0;
        }

        public int getAddedMethodCount() {
            return methodsAdded != null ? methodsAdded.size() : 0;
        }

        public boolean hasNewComplexMethods() {
            if (methodsAdded == null) {
                return false;
            }
            return methodsAdded.stream()
                    .anyMatch(m -> "complex".equals(m.getComplexityLevel())
                            || "highly_complex".equals(m.getComplexityLevel()));
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MethodComplexityEntry {
        private String method; // class.methodName
        private String operation; // "MODIFY" or "NEW"

        @JsonProperty("existing_complexity")
        private Integer existingComplexity; // For MODIFY

        @JsonProperty("new_complexity")
        private Integer newComplexity; // For NEW

        @JsonProperty("complexity_level")
        private String complexityLevel; // trivial, moderate, complex, highly_complex

        @JsonProperty("multiplier_applied")
        private double multiplierApplied;

        private String rationale;
    }

    // === IMPACT ANALYSIS ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactAnalysis {
        @JsonProperty("total_callers")
        private int totalCallers;

        @JsonProperty("production_callers")
        private int productionCallers;

        @JsonProperty("test_callers")
        private int testCallers;

        @JsonProperty("effective_callers")
        private double effectiveCallers;

        @JsonProperty("impact_level")
        private String impactLevel; // low, medium, high, critical

        @JsonProperty("critical_callers")
        private List<CriticalCaller> criticalCallers;

        @JsonProperty("impact_score")
        private double impactScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CriticalCaller {
        private String caller; // class.method
        private String reason;
    }

    // === QUALITY PENALTIES ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityPenalties {
        @JsonProperty("copy_paste_percent")
        private double copyPastePercent;

        @JsonProperty("copy_paste_penalty")
        private double copyPastePenalty;

        @JsonProperty("coverage_percent")
        private double coveragePercent;

        @JsonProperty("coverage_penalty")
        private double coveragePenalty;

        @JsonProperty("static_analysis_penalty")
        private double staticAnalysisPenalty;

        @JsonProperty("total_penalty")
        private double totalPenalty;

        public boolean hasSignificantPenalties() {
            return totalPenalty > 5;
        }
    }

    // === STATIC ANALYSIS REVIEW ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaticAnalysisReview {
        @JsonProperty("pmd_validated")
        private int pmdValidated;

        @JsonProperty("pmd_false_positives")
        private int pmdFalsePositives;

        @JsonProperty("spotbugs_validated")
        private int spotbugsValidated;

        @JsonProperty("spotbugs_false_positives")
        private int spotbugsFalsePositives;

        @JsonProperty("critical_bugs")
        private List<String> criticalBugs;

        public int getTotalValidatedIssues() {
            return pmdValidated + spotbugsValidated;
        }

        public int getTotalFalsePositives() {
            return pmdFalsePositives + spotbugsFalsePositives;
        }
    }

    // === BUG FINDING ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BugFinding {
        private String severity; // critical, major, minor
        private String type; // logic, concurrency, resource, security, integration
        private String title;
        private String description;
        private String file;

        @JsonProperty("line_hint")
        private String lineHint;

        private String confidence; // high, medium, low

        @JsonProperty("suggested_fix")
        private String suggestedFix;

        public boolean isCritical() {
            return "critical".equalsIgnoreCase(severity);
        }

        public boolean isHighConfidence() {
            return "high".equalsIgnoreCase(confidence);
        }
    }

    // === TAGS ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tags {
        private List<String> technical;
        private List<String> functional;
    }

    // === VELOCITY METRICS ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VelocityMetrics {
        @JsonProperty("estimated_hours")
        private double estimatedHours;

        @JsonProperty("contribution_category")
        private String contributionCategory; // trivial, small, medium, large, major

        private List<String> flags; // Concerns for velocity tracking

        public boolean isTrivial() {
            return "trivial".equalsIgnoreCase(contributionCategory);
        }

        public boolean isMajor() {
            return "major".equalsIgnoreCase(contributionCategory);
        }

        public boolean hasFlags() {
            return flags != null && !flags.isEmpty();
        }
    }

    // === UTILITY METHODS ===

    /**
     * Calculate total contribution score (static + LLM)
     */
    public double getTotalContributionScore(double staticScore) {
        return staticScore + llmContributionScore;
    }

    /**
     * Check if this commit has quality concerns
     */
    public boolean hasQualityConcerns() {
        return qualityPenalties != null && qualityPenalties.hasSignificantPenalties()
                || bugsFound != null && bugsFound.stream().anyMatch(BugFinding::isCritical)
                || complexityAnalysis != null && complexityAnalysis.hasNewComplexMethods();
    }

    /**
     * Check if this is a high-value contribution
     */
    public boolean isHighValueContribution() {
        return velocityMetrics != null
                && ("large".equals(velocityMetrics.getContributionCategory())
                        || "major".equals(velocityMetrics.getContributionCategory()));
    }

    /**
     * Get effective effort score (average of effort dimensions)
     */
    public double getEffortScore() {
        return effortAssessment != null ? effortAssessment.getAverageScore() : 0;
    }

    /**
     * Get effective quality score (average of quality dimensions)
     */
    public double getQualityScore() {
        return qualityAssessment != null ? qualityAssessment.getAverageScore() : 0;
    }

    /**
     * Calculate contribution grade based on total score
     */
    public String calculateGrade(double totalScore, int maxScore) {
        if (maxScore == 0) {
            return "A";
        }
        double percent = totalScore * 100.0 / maxScore;
        if (percent >= 85) {
            return "A";
        }
        if (percent >= 70) {
            return "B";
        }
        if (percent >= 55) {
            return "C";
        }
        if (percent >= 40) {
            return "D";
        }
        return "F";
    }

    /**
     * Get flags that should be reviewed
     */
    public List<String> getReviewFlags() {
        List<String> allFlags = new java.util.ArrayList<>();

        if (velocityMetrics != null && velocityMetrics.getFlags() != null) {
            allFlags.addAll(velocityMetrics.getFlags());
        }

        if (qualityPenalties != null) {
            if (qualityPenalties.getCopyPastePercent() > 30) {
                allFlags.add("high-copy-paste");
            }
            if (qualityPenalties.getCoveragePercent() < 50) {
                allFlags.add("low-coverage");
            }
        }

        if (complexityAnalysis != null && complexityAnalysis.hasNewComplexMethods()) {
            allFlags.add("new-complex-methods");
        }

        if (bugsFound != null && bugsFound.stream().anyMatch(BugFinding::isCritical)) {
            allFlags.add("critical-bugs");
        }

        return allFlags;
    }
}
