package io.codiqo.llm.schema;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for LLM contribution scoring.
 * Contains all information about a commit for velocity analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmScoringRequest {

    // === COMMIT METADATA ===
    private String commitHash;
    private String commitMessage;
    private String author;
    private String authorEmail;
    private String timestamp;
    private String repository;
    private String branch;

    // === CHANGE SUMMARY ===
    private ChangeSummary changeSummary;

    // === FILE CHANGES ===
    private List<FileChange> fileChanges;

    // === METHOD-LEVEL CHANGES (Critical for NEW vs MODIFY analysis) ===
    private List<MethodChange> methodChanges;

    // === PRE-COMPUTED STATIC SCORES ===
    private StaticScores staticScores;

    // === STATIC ANALYSIS FINDINGS ===
    private List<PmdFinding> pmdFindings;
    private List<SpotBugsFinding> spotBugsFindings;

    // === CALLER INFORMATION (from JDTLS) ===
    private List<CallerInfo> callers;

    // === QUALITY METRICS ===
    private CopyPasteAnalysis copyPasteAnalysis;
    private CoverageInfo coverage;
    private ComplexityMetrics complexity;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangeSummary {
        private int totalFilesChanged;
        private int totalLinesChanged;
        private int linesAdded;
        private int linesDeleted;
        private int linesModified;

        // Method/class level counts
        private int methodsAdded;
        private int methodsModified;
        private int methodsDeleted;
        private int classesAdded;
        private int classesModified;
        private int classesDeleted;

        private List<String> packagesAffected;
        private String changeType; // "feature", "bugfix", "refactor", "config", "test"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileChange {
        private String path;
        private String changeType; // "added", "modified", "deleted", "renamed"
        private int linesAdded;
        private int linesDeleted;
        private String diff;
        private String language;
        private boolean isTest;
        private boolean isConfig;
    }

    /**
     * Method-level change information - CRITICAL for scoring.
     * Distinguishes NEW methods from MODIFIED methods.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MethodChange {
        private String className;
        private String methodName;
        private String signature;
        private String fullyQualifiedName; // com.example.Service.methodName

        /** "NEW" = added in this commit, "MODIFY" = changed existing, "DELETE" = removed */
        private String operation;

        // Location
        private String file;
        private int startLine;
        private int endLine;

        // Change metrics
        private int linesAdded;
        private int linesDeleted;
        private int totalLinesChanged;

        // Complexity metrics (CRITICAL for scoring)
        private int cyclomaticComplexity;
        private int cognitiveComplexity;
        private int nestingDepth;
        private int parameterCount;

        /** For MODIFY: the complexity BEFORE the change */
        private Integer previousCyclomaticComplexity;
        private Integer previousCognitiveComplexity;

        /** Visibility: public, protected, package, private */
        private String visibility;

        /** Is this a constructor? */
        private boolean isConstructor;

        /** Annotations on this method */
        private List<String> annotations;

        /** Callers of THIS specific method (from JDTLS) */
        private List<CallerInfo> callers;

        // Helper methods
        public boolean isNew() {
            return "NEW".equalsIgnoreCase(operation);
        }

        public boolean isModify() {
            return "MODIFY".equalsIgnoreCase(operation);
        }

        public boolean isDelete() {
            return "DELETE".equalsIgnoreCase(operation);
        }

        public int getCallerCount() {
            return callers != null ? callers.size() : 0;
        }

        public long getProductionCallerCount() {
            return callers != null ? callers.stream().filter(c -> !c.isTestCaller()).count() : 0;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaticScores {
        // Base effort scores
        private double linesChangedScore;
        private double filesChangedScore;
        private double methodsChangedScore;

        // Complexity bonus/penalty
        private double complexityBonusScore;

        // Impact score
        private double impactScore;

        // Penalties
        private double copyPastePenalty;
        private double coveragePenalty;
        private double staticAnalysisPenalty;

        // Totals
        private double totalStaticScore;
        private int maxStaticScore;

        // Breakdown for transparency
        private Map<String, Double> scoreBreakdown;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PmdFinding {
        private String rule;
        private String ruleSet;
        private int priority; // 1-5
        private String message;
        private String file;
        private int beginLine;
        private int endLine;
        private String category;
        private boolean isBugIndicator;
        private String externalInfoUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpotBugsFinding {
        private String type;
        private String category; // "CORRECTNESS", "MT_CORRECTNESS", etc.
        private int rank; // 1-20
        private int priority; // 1-3
        private String message;
        private String longMessage;
        private String file;
        private int startLine;
        private int endLine;
        private String className;
        private String methodName;
        private boolean isRealBug;
    }

    /**
     * Caller information from JDTLS - detailed for impact analysis.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallerInfo {
        // Caller identification
        private String callerClass;
        private String callerMethod;
        private String callerSignature;
        private String callerPackage;
        private String callerFullyQualified;

        // Called method identification
        private String calledClass;
        private String calledMethod;
        private String calledSignature;

        // Location of the call
        private String file;
        private int line;
        private int column;

        // Classification (CRITICAL for impact scoring)
        private boolean isTestCaller;
        private boolean isProductionCaller;
        private boolean isPublicApi; // Entry point?
        private boolean isCriticalPath; // Business-critical flow?
        private boolean isScheduledJob; // @Scheduled
        private boolean isEventHandler; // Event listener
        private boolean isAsyncCaller; // @Async
        private boolean isTransactional; // @Transactional

        // Context
        private List<String> annotations;
        private String callContext; // Code snippet around call
        private String moduleOrComponent;

        // Caller's own complexity (for understanding propagation)
        private Integer callerComplexity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CopyPasteAnalysis {
        private double overallDuplicationPercentage;
        private int totalDuplicatedLines;
        private int totalDuplicatedBlocks;
        private List<DuplicationBlock> duplications;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class DuplicationBlock {
            private String sourceFile;
            private int sourceStartLine;
            private int sourceEndLine;
            private String targetFile; // Where it was copied from
            private int targetStartLine;
            private int targetEndLine;
            private int lineCount;
            private int tokenCount;
            private double similarity;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoverageInfo {
        // Overall coverage for changed code
        private double changedLineCoverage;
        private double changedBranchCoverage;

        // Overall project coverage (for context)
        private double projectLineCoverage;
        private double projectBranchCoverage;

        // Per-method coverage (for detailed analysis)
        private Map<String, MethodCoverage> methodCoverages;

        // Uncovered paths (for risk flagging)
        private List<UncoveredPath> uncoveredPaths;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class MethodCoverage {
            private String methodName;
            private double lineCoverage;
            private double branchCoverage;
            private int coveredLines;
            private int missedLines;
            private int coveredBranches;
            private int missedBranches;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class UncoveredPath {
            private String file;
            private String method;
            private int startLine;
            private int endLine;
            private String pathDescription;
            private String riskLevel; // "low", "medium", "high", "critical"
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplexityMetrics {
        // Aggregate metrics
        private int totalCyclomaticComplexity;
        private int totalCognitiveComplexity;
        private int maxMethodComplexity;
        private double averageMethodComplexity;

        // Change in complexity
        private int complexityDelta; // Positive = more complex overall
        private int newHighComplexityMethods; // New methods with high complexity (bad)
        private int modifiedHighComplexityMethods; // Modified complex methods (hard work)

        // Thresholds used
        private int complexityThreshold;
    }
}
