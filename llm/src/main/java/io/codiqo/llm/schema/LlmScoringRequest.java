package io.codiqo.llm.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;

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
public class LlmScoringRequest {
    private String commitHash;
    private String commitMessage;
    private String author;
    private String authorEmail;
    private String timestamp;
    private String repository;
    private String branch;
    private boolean revertCommit;
    private String revertedCommitId;

    private ChangeSummary changeSummary;
    private List<FileChange> fileChanges;
    private List<CodeBlockChange> codeBlockChanges;
    private CoverageInfo coverage;
    private ComplexityMetrics complexity;
    private DuplicationInfo duplication;
    @Builder.Default
    private List<WebSearchContext> webSearchContext = Collections.emptyList();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebSearchContext {
        private String query;
        @Builder.Default
        private List<WebSearchResultItem> results = Collections.emptyList();

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class WebSearchResultItem {
            private String title;
            private String url;
            private String content;
        }
    }

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

        private int codeBlocksAdded;
        private int codeBlocksModified;
        private int codeBlocksDeleted;
        private int classesAdded;
        private int classesModified;
        private int classesDeleted;

        private List<String> packagesAffected;
        private ChangeType changeType;
    }

    public enum ChangeType {
        FEATURE, BUGFIX, REFACTOR, CONFIG, TEST
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileChange {
        private String path;
        private FileChangeType changeType;
        private int linesAdded;
        private int linesDeleted;
        private String diff;
        private String language;
        private boolean isTest;
        private boolean isConfig;
    }

    public enum FileChangeType {
        ADDED, MODIFIED, DELETED, RENAMED
    }

    public enum Operation {
        NEW, MODIFY, DELETE
    }

    /**
     * Code block change information - CRITICAL for scoring.
     * Distinguishes NEW code blocks from MODIFIED code blocks.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeBlockChange {
        private String className;
        private String name;
        private String signature;
        private String fullyQualifiedName;

        private Operation operation;

        /**
         * location
         */
        private String file;
        private int startLine;
        private int endLine;

        /**
         * change metrics
         */
        private int linesAdded;
        private int linesDeleted;
        private int totalLinesChanged;

        /**
         * Complexity metrics (CRITICAL for scoring)
         */
        private int linesOfCode;
        private int cyclomaticComplexity;
        private int cognitiveComplexity;
        private int nestingDepth;
        private int parameterCount;
        private int fanOut;
        private long npath;

        /**
         * For MODIFY: the complexity BEFORE the change
         */
        private Integer previousCyclomaticComplexity;
        private Integer previousCognitiveComplexity;

        private String visibility;
        private boolean isConstructor;
        private List<String> annotations;

        @Builder.Default
        private List<CallerInfo> callers = Lists.newArrayList();

        /**
         * PMD and SpotBugs diagnostics for this method.
         * Used by LLM to assess code quality and identify issues.
         */
        @Builder.Default
        private List<DiagnosticInfo> diagnostics = Lists.newArrayList();

        public boolean isNew() {
            return operation == Operation.NEW;
        }
        public boolean isModify() {
            return operation == Operation.MODIFY;
        }
        public boolean isDelete() {
            return operation == Operation.DELETE;
        }
        public int getCallerCount() {
            return CollectionUtils.size(callers);
        }
        public long getProductionCallerCount() {
            return Objects.nonNull(callers) ? callers.stream().filter(c -> !c.isTestCaller()).count() : 0;
        }
    }

    /**
     * Caller information from JDTLS for blast radius analysis.
     * Provides rich context for LLM to assess impact of changes.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallerInfo {
        private String callerMethod;
        private String file;
        private int line;
        private boolean isTestCaller;

        /**
         * Java ASM binary format signature (e.g., "com/example/MyClass.myMethod(Ljava/lang/String;I)V").
         * Format: package/ClassName.methodName(parameterDescriptors)returnDescriptor
         * - L...;  = object type
         * - I/J/D  = int/long/double primitives
         * - [      = array prefix
         * - V      = void return
         */
        private String signature;

        /**
         * Symbol kind (method, constructor, lambda, etc.)
         */
        private String kind;

        /**
         * Fully qualified symbol identifier
         */
        private String symbol;

        private boolean isDeprecated;

        /**
         * Number of call sites from this caller to the target method.
         * Multiple calls from same caller indicate tighter coupling.
         */
        private int callSiteCount;

        /**
         * Source code body of the calling method/constructor.
         * Provides context for how the affected code block is used.
         */
        private String callerBody;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiagnosticInfo {
        private String tool;
        private String ruleId;
        private String message;
        private String category;
        private DiagnosticSeverity severity;
        private int startLine;
        private int endLine;
        private boolean introducedInCommit;
    }

    public enum DiagnosticSeverity {
        ERROR,
        WARNING,
        INFO,
        NOTE,
        NONE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoverageInfo {
        private double changedLineCoverage;
        private double changedBranchCoverage;

        private double projectLineCoverage;
        private double projectBranchCoverage;

        @Builder.Default
        private Map<String, MethodCoverage> methodCoverages = Maps.newHashMap();
        @Builder.Default
        private List<UncoveredPath> uncoveredPaths = Lists.newArrayList();

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
            private RiskLevel riskLevel;
        }

        public enum RiskLevel {
            LOW, MEDIUM, HIGH, CRITICAL
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplexityMetrics {
        private int totalCyclomaticComplexity;
        private int totalCognitiveComplexity;
        private int maxMethodComplexity;
        private double methodComplexityQuantile;

        private int complexityDelta; // Positive = more complex overall
        private int newHighComplexityMethods; // New methods with high complexity (bad)
        private int modifiedHighComplexityMethods; // Modified complex methods (hard work)

        private int complexityThreshold;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DuplicationInfo {
        private double duplicatedPercentage;
        private int totalDuplicatedLines;
        private int totalDuplicatedTokens;
        private int minimumTokens;

        @Builder.Default
        private List<CloneDetail> cloneDetails = Lists.newArrayList();
        @Builder.Default
        private List<CloneFromExisting> clonesFromExisting = Lists.newArrayList();
        @Builder.Default
        private List<NewCloneGroup> newClones = Lists.newArrayList();

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class CloneDetail {
            private int tokenCount;
            private int lineCount;
            private boolean crossFile;
            private boolean selfDuplication;
            private boolean allTestCode;
            private boolean introducedInCommit;
            @Builder.Default
            private List<CloneLocation> locations = Lists.newArrayList();
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class CloneLocation {
            private String file;
            private int startLine;
            private int endLine;
            private String methodSignature;
            private String sourceSlice;
            private boolean testCode;
            private boolean introducedInCommit;
            private int linesOverlappingDiff;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class CloneFromExisting {
            private String affectedSignature;
            @Builder.Default
            private List<String> sourceSignatures = Lists.newArrayList();
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class NewCloneGroup {
            @Builder.Default
            private List<String> memberSignatures = Lists.newArrayList();
        }
    }
}
