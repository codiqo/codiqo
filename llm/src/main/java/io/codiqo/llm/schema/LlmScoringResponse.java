package io.codiqo.llm.schema;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmScoringResponse {
    private static final EnumSet<RiskLevel> HIGH_RISK_LEVELS = EnumSet.of(RiskLevel.HIGH, RiskLevel.VERY_HIGH, RiskLevel.CRITICAL);
    private static final EnumSet<ModuleType> SHARED_MODULE_TYPES = EnumSet.of(ModuleType.CORE_LIBRARY, ModuleType.SHARED_UTILITY);
    private static final EnumSet<ExternalImpact> SIGNIFICANT_EXTERNAL_IMPACTS = EnumSet.of(ExternalImpact.MEDIUM, ExternalImpact.HIGH);

    private double score;
    private String scoreCalculation;
    private EffortBreakdown effortBreakdown;
    private QualityMultiplier qualityMultiplier;
    private ArchitectureEffortBonus architectureEffortBonus;
    private QualityDimensions qualityDimensions;
    private RiskAssessment riskAssessment;
    private ChangeClassification changeClassification;

    @Builder.Default
    private List<TaskType> taskTypes = Lists.newArrayList();

    private Integer taskComplexity;
    private String taskComplexityRationale;
    private String thinking;
    private Bugs bugs;
    private StaticAnalysisReview staticAnalysisReview;
    private BlastRadiusAnalysis blastRadiusAnalysis;
    private int requiresSeniorReview;
    @Builder.Default
    private List<String> seniorReviewReasons = Lists.newArrayList();
    private String summary;
    private Tags tags;

    @Builder.Default
    private List<ModifyImpactEstimate> modifyImpactEstimates = Lists.newArrayList();

    public boolean hasBlockingBugs() {
        return Objects.nonNull(bugs) && CollectionUtils.isNotEmpty(bugs.getBlocking());
    }
    public boolean isSeniorReviewRequired(int threshold) {
        return requiresSeniorReview >= threshold;
    }
    public boolean isHighRisk() {
        if (Objects.isNull(riskAssessment) || Objects.isNull(riskAssessment.getRiskLevel())) {
            return false;
        }
        return HIGH_RISK_LEVELS.contains(riskAssessment.getRiskLevel());
    }
    public int getTotalBugCount() {
        if (Objects.isNull(bugs)) {
            return 0;
        }
        return CollectionUtils.size(bugs.getBlocking()) + CollectionUtils.size(bugs.getMajor()) + CollectionUtils.size(bugs.getMinor());
    }
    public boolean hasLibraryBreakingChanges() {
        if (Objects.isNull(blastRadiusAnalysis)) {
            return false;
        }
        boolean isCoreOrShared = SHARED_MODULE_TYPES.contains(blastRadiusAnalysis.getModuleType());
        boolean hasBreaking = Objects.nonNull(blastRadiusAnalysis.getSignatureChanges()) && blastRadiusAnalysis.getSignatureChanges().isHasBreakingChanges();
        return BooleanUtils.and(new boolean[]{isCoreOrShared, hasBreaking});
    }
    public boolean hasSignificantExternalImpact() {
        if (Objects.isNull(blastRadiusAnalysis) || Objects.isNull(blastRadiusAnalysis.getExternalImpactEstimate())) {
            return false;
        }
        return SIGNIFICANT_EXTERNAL_IMPACTS.contains(blastRadiusAnalysis.getExternalImpactEstimate());
    }

    public enum ChangeClassification {
        TRIVIAL,
        SMALL,
        MEDIUM,
        LARGE,
        HUGE
    }

    public enum TaskType {
        FEATURE,
        BUG_FIX,
        REFACTOR,
        TEST,
        DOCS,
        CHORE,
        INFRA,
        DEP_UPDATE,
        SECURITY_PATCH,
        PERFORMANCE,
        DEDUPLICATION,
        STYLE,
        DATA_MIGRATION
    }

    public enum RiskLevel {
        LOW,
        MODERATE,
        HIGH,
        VERY_HIGH,
        CRITICAL
    }

    public enum BugType {
        SECURITY,
        CONCURRENCY,
        DATA_CORRUPTION,
        LOGIC,
        RESOURCE_LEAK,
        NULL_POINTER,
        ARCHITECTURE,
        OTHER
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum BugSource {
        LLM,
        PMD,
        SPOTBUGS
    }

    public enum ModuleType {
        CORE_LIBRARY,
        SHARED_UTILITY,
        LEAF_APPLICATION
    }

    public enum ExternalImpact {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        UNKNOWN
    }

    public enum BreakingChangeType {
        PARAMETER_ADDED,
        PARAMETER_REMOVED,
        PARAMETER_CHANGED,
        RETURN_TYPE_CHANGED,
        EXCEPTION_ADDED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EffortBreakdown {
        private VolumeScore volumeScore;
        private ComplexityMultiplier complexityMultiplier;
        private double baseEffortScore;
        @Builder.Default
        private List<FileEffortView> fileEfforts = Lists.newArrayList();
        private DiffClassification diffClassification;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VolumeScore {
        private int linesChanged;
        private int filesChanged;
        private double filesScopeMultiplier;
        private int codeBlocksModified;
        private int codeBlocksAdded;
        private int classesModified;
        private int classesAdded;
        private double blockEffortSum;
        private double totalEffortRaw;
        private double totalBaseline;
        private double globalCap;
        private boolean globalCapApplied;
        private boolean globalCapDryRun;
        private double sizeFactor;
        private double modifyMultiplier;
        private double addMultiplier;
        private double totalVolumeScore;
        private int linesChangedRaw;
        private int linesChangedAdjusted;
        private int cosmeticLinesDropped;
        private int inPlaceLinesCollapsed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffClassification {
        private int totalLinesAddedRaw;
        private int totalLinesDeletedRaw;
        private int cosmeticLines;
        private int pairsCollapsed;
        private int pureAddDeleteLines;
        @Builder.Default
        private List<FileDiffClassification> perFile = Lists.newArrayList();
        private String rationale;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileDiffClassification {
        private String file;
        private int cosmeticLines;
        private int pairsCollapsed;
        private int pureAddDeleteLines;
        // LLM input: change-block id → "inPlace" | "trueModify". The pair/pure arrays below are
        // server-derived from the diff's change blocks; the LLM no longer emits them.
        @Builder.Default
        private Map<String, String> blockKinds = Maps.newHashMap();
        @Builder.Default
        private List<Integer> cosmeticAdded = Lists.newArrayList();
        @Builder.Default
        private List<Integer> cosmeticDeleted = Lists.newArrayList();
        @Builder.Default
        private List<LinePair> inPlaceModifyPairs = Lists.newArrayList();
        @Builder.Default
        private List<LinePair> trueModifyPairs = Lists.newArrayList();
        @Builder.Default
        private List<Integer> pureAdd = Lists.newArrayList();
        @Builder.Default
        private List<Integer> pureDelete = Lists.newArrayList();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinePair {
        private int deleted;
        private int added;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplexityMultiplier {
        private double combinedMultiplier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityMultiplier {
        private CpdAnalysis cpdAnalysis;
        private StaticAnalysisImpact staticAnalysis;
        private CoverageAnalysis coverageAnalysis;
        private ArchitectureAnalysis architectureAnalysis;
        private QualityGateAnalysis qualityGateAnalysis;
        private double finalMultiplier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CpdAnalysis {
        private double duplicationPercent;
        private double impact;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaticAnalysisImpact {
        private int pmdViolationsInChanges;
        private int spotbugsIssuesInChanges;
        private double impact;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoverageAnalysis {
        private double coveragePercent;
        private double impact;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArchitectureAnalysis {
        @Builder.Default
        private List<String> solidViolations = Lists.newArrayList();
        @Builder.Default
        private List<String> architectureIssues = Lists.newArrayList();
        private double penaltyImpact;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityGateAnalysis {
        @Builder.Default
        private List<String> failedGates = Lists.newArrayList();
        private double impact;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArchitectureEffortBonus {
        private int architectureImpactScore;
        private double qualityFactor;
        private double baseEffort;
        private String bonusCalculation;
        private double bonusPoints;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityDimensions {
        private DimensionScore architectureImpact;
        private DimensionScore concurrencyRisk;
        private DimensionScore integrationSurface;
        private DimensionScore dataIntegrity;
        private DimensionScore securitySensitivity;
        private DimensionScore scalabilityImpact;
        private DimensionScore observability;
        private DimensionScore resilience;
        private DimensionScore performance;
        private DimensionScore testingCoverage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionScore {
        private Integer score;
        private String rationale;
        private boolean qualityGateMet;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskAssessment {
        private int riskScore;
        private RiskLevel riskLevel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bugs {
        @Builder.Default
        private List<Bug> blocking = Lists.newArrayList();
        @Builder.Default
        private List<Bug> major = Lists.newArrayList();
        @Builder.Default
        private List<Bug> minor = Lists.newArrayList();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bug {
        private BugType type;
        private String title;
        private String description;
        private String file;
        private Integer line;
        private String suggestedFix;
        private String suggestedFileFix;
        private String suggestedBlockCode;
        private Confidence confidence;
        private BugSource source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaticAnalysisReview {
        @Builder.Default
        private List<StaticAnalysisFinding> pmdInChangedLines = Lists.newArrayList();
        @Builder.Default
        private List<StaticAnalysisFinding> pmdPreExisting = Lists.newArrayList();
        @Builder.Default
        private List<StaticAnalysisFinding> pmdFalsePositives = Lists.newArrayList();
        @Builder.Default
        private List<StaticAnalysisFinding> spotbugsInChangedLines = Lists.newArrayList();
        @Builder.Default
        private List<StaticAnalysisFinding> spotbugsPreExisting = Lists.newArrayList();
        @Builder.Default
        private List<StaticAnalysisFinding> spotbugsFalsePositives = Lists.newArrayList();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaticAnalysisFinding {
        private String rule;
        private String file;
        private Integer line;
        private String assessment;
        private FindingSeverity severity;
        private String suggestedFileFix;
        private String suggestedBlockCode;
    }

    public enum FindingSeverity {
        ERROR,
        WARNING,
        INFO
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlastRadiusAnalysis {
        private int totalCallers;
        private int productionCallers;
        private int testCallers;
        private RiskLevel riskLevel;
        @Builder.Default
        private List<String> criticalCallers = Lists.newArrayList();
        private ModuleType moduleType;
        private SignatureChanges signatureChanges;
        private ExternalImpact externalImpactEstimate;
        private String explanation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignatureChanges {
        private boolean hasBreakingChanges;
        @Builder.Default
        private List<String> changedSignatures = Lists.newArrayList();
        private BreakingChangeType breakingChangeType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tags {
        @Builder.Default
        private List<String> technical = Lists.newArrayList();
        @Builder.Default
        private List<String> functional = Lists.newArrayList();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileEffortView {
        private String file;
        private double totalEffort;
        private boolean isTest;
        @Builder.Default
        private List<CodeBlockEffortView> codeBlockEfforts = Lists.newArrayList();
        private int blocksFlaggedAsRatioOutlier;
        private int blocksFlaggedAsGlobalCapDriver;
        private double maxBlockRatioDeviationNcss;
        private double maxBlockRatioDeviationInvocations;
        private boolean fileFlaggedAsAbusive;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeBlockEffortView {
        private String name;
        private String signature;
        private String operation;
        private int nonCommentCodeStatements;
        private int directInvocationCount;
        private int effectiveInvocationsChanged;
        private int nonCommentCodeLines;
        private int commentLines;
        private int effectiveLinesChanged;
        private double changeRatio;
        private double scaledLines;
        private double scaledNcss;
        private double scaledInvocations;
        private double driverScore;
        private int cappedStatements;
        private double effort;
        private boolean isTest;
        private double blockRatioDeviationNcss;
        private double blockRatioDeviationInvocations;
        private boolean blockRatioOutlier;
        private double effortShare;
        private boolean globalCapDriver;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModifyImpactEstimate {
        private String signature;
        private String file;
        private CoverageDirection coverageDirection;
        private ChangeMagnitude coverageMagnitude;
        private DuplicationDirection duplicationDirection;
        private ChangeMagnitude duplicationMagnitude;
        private Confidence confidence;
        private String rationale;
    }

    public enum CoverageDirection {
        IMPROVED,
        UNCHANGED,
        REGRESSED,
        UNKNOWN
    }

    public enum DuplicationDirection {
        REDUCED,
        UNCHANGED,
        INCREASED,
        UNKNOWN
    }

    public enum ChangeMagnitude {
        NONE,
        SLIGHT,
        MODERATE,
        SIGNIFICANT
    }
}
