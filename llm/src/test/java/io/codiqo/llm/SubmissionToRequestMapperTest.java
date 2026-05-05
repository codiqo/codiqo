package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CloneLocationModel;
import io.codiqo.client.model.CloneModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.CoverageModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.DimensionStatsModel;
import io.codiqo.client.model.DriverScalerModel;
import io.codiqo.client.model.DriverScalersModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FullProjectCoverageModel;
import io.codiqo.client.model.JavaInfoModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CoverageInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo.CloneDetail;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChangeType;

class SubmissionToRequestMapperTest {
    private static final String METHOD_DIFF =
            "--- a/Foo.java\n"
          + "+++ b/Foo.java\n"
          + "@@ -1,2 +1,6 @@\n"
          + " package com.example;\n"
          + "+import java.util.List;\n"
          + "+// a comment\n"
          + "+int realCode = 1;\n"
          + "+\n"
          + " class Foo {}\n";

    private final SubmissionToRequestMapper mapper = new SubmissionToRequestMapper(new RunArgs());

    @Test
    void happyPathCopiesCommitAndProjectIdentity() {
        AnalysisSubmissionModel submission = baseSubmission();

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals("abc123", request.getCommitHash());
        assertEquals("test commit", request.getCommitMessage());
        assertEquals("Jane", request.getAuthor());
        assertEquals("main", request.getBranch());
        assertEquals("proj-1", request.getRepository());
        assertFalse(request.isRevertCommit());
    }

    @Test
    void revertCommitFlagIsLiftedFromCommitModel() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getCommit().setIsRevert(true);
        submission.getCommit().setRevertedCommitId("deadbeef");

        LlmScoringRequest request = mapper.apply(submission);

        assertTrue(request.isRevertCommit());
        assertEquals("deadbeef", request.getRevertedCommitId());
    }

    @Test
    void nullIsRevertTreatedAsFalseViaBooleanTrueEquals() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getCommit().setIsRevert(null);

        LlmScoringRequest request = mapper.apply(submission);

        assertFalse(request.isRevertCommit(), "null isRevert must not NPE; Boolean.TRUE.equals policy");
    }

    @ParameterizedTest
    @EnumSource(value = FileChangeModel.ChangeTypeEnum.class, names = {"ADD", "MODIFY", "DELETE", "RENAME"})
    void fileChangeTypeIsExhaustivelyMappedForSupportedValues(FileChangeModel.ChangeTypeEnum input) {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).setChangeType(input);

        LlmScoringRequest request = mapper.apply(submission);

        FileChangeType mapped = request.getFileChanges().get(0).getChangeType();
        assertNotNull(mapped);
    }

    @Test
    void copyChangeTypeCurrentlyThrowsAndNeedsMapperSupport() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).setChangeType(FileChangeModel.ChangeTypeEnum.COPY);

        // NOTE: mapper's switch doesn't handle COPY — git copy detection currently crashes the pipeline.
        // This test pins the current behavior; flip to a positive assertion once COPY is added to the mapper.
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(submission));
    }

    @ParameterizedTest
    @EnumSource(CodeUnitModel.OperationEnum.class)
    void codeUnitOperationIsExhaustivelyMapped(CodeUnitModel.OperationEnum input) {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).getCodeUnits().get(0).setOperation(input);

        mapper.apply(submission);
    }

    @ParameterizedTest
    @EnumSource(DiagnosticModel.SeverityEnum.class)
    void diagnosticSeverityIsExhaustivelyMapped(DiagnosticModel.SeverityEnum input) {
        AnalysisSubmissionModel submission = baseSubmission();
        CodeUnitModel method = submission.getFiles().get(0).getCodeUnits().get(0);
        DiagnosticModel diag = new DiagnosticModel();
        diag.setRuleId("R1");
        diag.setSeverity(input);
        diag.setMessage("boom");
        diag.setLocation(location(3, 3));
        method.setDiagnostics(Lists.newArrayList(diag));

        LlmScoringRequest request = mapper.apply(submission);

        assertNotNull(request.getCodeBlockChanges().get(0).getDiagnostics().get(0).getSeverity());
    }

    @Test
    void devNullPathFallsBackToPreviousPath() {
        AnalysisSubmissionModel submission = baseSubmission();
        FileChangeModel file = submission.getFiles().get(0);
        file.setPath("/dev/null");
        file.setPreviousPath("OldFoo.java");

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals("OldFoo.java", request.getFileChanges().get(0).getPath(),
                "deleted file with /dev/null path must fall back to previousPath");
    }

    @Test
    void emptyPathFallsBackToPreviousPath() {
        AnalysisSubmissionModel submission = baseSubmission();
        FileChangeModel file = submission.getFiles().get(0);
        file.setPath("");
        file.setPreviousPath("Renamed.java");

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals("Renamed.java", request.getFileChanges().get(0).getPath());
    }

    @Test
    void trivialCodeUnitIsExcludedFromCodeBlockChanges() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).getCodeUnits().get(0).setIsTrivial(true);

        LlmScoringRequest request = mapper.apply(submission);

        assertTrue(request.getCodeBlockChanges().isEmpty(),
                "trivial methods are filtered out — LLM shouldn't see getters/setters");
    }

    @Test
    void testFileLinesAreCountedInTestBuckets() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).setIsTest(true);

        LlmScoringRequest request = mapper.apply(submission);

        assertTrue(request.getChangeSummary().getTestLinesChanged() > 0,
                "a test-flagged file must contribute to testLinesChanged");
        assertEquals(1, request.getChangeSummary().getTestCodeBlocksAdded());
        assertEquals(1, request.getChangeSummary().getTestFilesChanged());
        assertTrue(request.getCodeBlockChanges().get(0).isTest());
    }

    @Test
    void classOperationsAreCountedSeparatelyFromMethods() {
        AnalysisSubmissionModel submission = baseSubmission();
        CodeUnitModel clazz = new CodeUnitModel();
        clazz.setName("Foo");
        clazz.setSignature("com.example.Foo");
        clazz.setKind(SymbolKindModel.propertyClass);
        clazz.setOperation(CodeUnitModel.OperationEnum.NEW);
        clazz.setLocation(location(1, 50));
        submission.getFiles().get(0).getCodeUnits().add(clazz);

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals(1, request.getChangeSummary().getClassesAdded(),
                "SymbolKindModel.propertyClass with operation NEW increments classesAdded");
        assertEquals(1, request.getChangeSummary().getCodeBlocksAdded(),
                "method count must be unaffected by the class operation");
    }

    @Test
    void diffImportsAndCommentsAreExcludedFromEffectiveLineCounts() {
        AnalysisSubmissionModel submission = baseSubmission();

        LlmScoringRequest request = mapper.apply(submission);

        FileChange fileChange = request.getFileChanges().get(0);
        assertEquals(1, fileChange.getLinesAdded(),
                "of 4 added lines (import, comment, code, blank), only 'int realCode = 1;' counts");
        assertEquals(0, fileChange.getLinesDeleted());
    }
    @Test
    void diffStatsFiltersBlanksCommentsImportsFromDeletedLines() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).setDiff(
                "--- a/Foo.java\n"
              + "+++ b/Foo.java\n"
              + "@@ -1,6 +1,2 @@\n"
              + " package com.example;\n"
              + "-import java.util.List;\n"
              + "-// removed comment\n"
              + "-\n"
              + "-int removed = 1;\n"
              + " class Foo {}\n");

        LlmScoringRequest request = mapper.apply(submission);

        FileChange fileChange = request.getFileChanges().get(0);
        assertEquals(0, fileChange.getLinesAdded());
        assertEquals(1, fileChange.getLinesDeleted(),
                "of 4 deleted lines (import, comment, blank, code), only 'int removed = 1;' counts");
    }

    @Test
    void driverScalersMappedFromProjectMetrics() {
        AnalysisSubmissionModel submission = baseSubmission();
        ProjectMetricsModel metrics = new ProjectMetricsModel();
        DriverScalersModel scalers = new DriverScalersModel();
        scalers.setMethodScalerProd(driverScaler(10, 5, 50));
        metrics.setDriverScalers(scalers);
        submission.setProjectMetrics(metrics);

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals(10, request.getMethodScalerProd().population(),
                "driverScaler population must be copied from persisted scalers");
        assertEquals(5, request.getMethodScalerProd().lines().min());
        assertEquals(50, request.getMethodScalerProd().lines().max());
    }

    @Test
    void missingDriverScalersYieldEmptyScalers() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.setProjectMetrics(null);

        LlmScoringRequest request = mapper.apply(submission);

        assertTrue(request.getMethodScalerProd().isEmpty());
        assertTrue(request.getMethodScalerTest().isEmpty());
        assertTrue(request.getConstructorScalerProd().isEmpty());
        assertTrue(request.getConstructorScalerTest().isEmpty());
    }

    @Test
    void zeroPopulationScalerCollapsesToEmpty() {
        AnalysisSubmissionModel submission = baseSubmission();
        ProjectMetricsModel metrics = new ProjectMetricsModel();
        DriverScalersModel scalers = new DriverScalersModel();
        scalers.setMethodScalerProd(driverScaler(0, 0, 0));
        metrics.setDriverScalers(scalers);
        submission.setProjectMetrics(metrics);

        LlmScoringRequest request = mapper.apply(submission);

        assertTrue(request.getMethodScalerProd().isEmpty(),
                "a zero-population scaler record must collapse to DriverScaler.EMPTY");
    }

    @Test
    void fileCoverageAggregatedAcrossMethods() {
        AnalysisSubmissionModel submission = baseSubmission();
        CodeUnitModel method = submission.getFiles().get(0).getCodeUnits().get(0);
        CoverageModel coverage = new CoverageModel();
        coverage.setCoveredLines(8);
        coverage.setMissedLines(2);
        coverage.setCoveredBranches(3);
        coverage.setMissedBranches(1);
        coverage.setLinePercent(80.0);
        coverage.setBranchPercent(75.0);
        method.setCoverage(coverage);
        submission.setFullProjectCoverage(projectCoverage(65.0, 55.0));

        LlmScoringRequest request = mapper.apply(submission);

        CoverageInfo info = request.getCoverage();
        assertEquals(80.0, info.getChangedLineCoverage(), 0.001,
                "changedLineCoverage = totalCovered * 100 / totalLines");
        assertEquals(75.0, info.getChangedBranchCoverage(), 0.001);
        assertEquals(65.0, info.getProjectLineCoverage());
        assertEquals(55.0, info.getProjectBranchCoverage());
    }

    @Test
    void coverageBelowLowThresholdAddsUncoveredPath() {
        RunArgs args = new RunArgs();
        AnalysisSubmissionModel submission = baseSubmission();
        CodeUnitModel method = submission.getFiles().get(0).getCodeUnits().get(0);
        CoverageModel coverage = new CoverageModel();
        coverage.setCoveredLines(1);
        coverage.setMissedLines(9);
        coverage.setLinePercent(5.0);
        method.setCoverage(coverage);
        submission.setFullProjectCoverage(projectCoverage(0.0, 0.0));

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals(1, request.getCoverage().getUncoveredPaths().size());
        CoverageInfo.UncoveredPath path = request.getCoverage().getUncoveredPaths().get(0);
        assertEquals("Foo.java", path.getFile());
        assertEquals("doWork", path.getMethod());
        assertTrue(5.0 < args.getCoverageLowThreshold(),
                "this test only meaningful while 5% is below the configured low threshold");
    }

    @Test
    void coverageCriticalThresholdYieldsCriticalRiskLevel() {
        AnalysisSubmissionModel submission = baseSubmission();
        CodeUnitModel method = submission.getFiles().get(0).getCodeUnits().get(0);
        CoverageModel coverage = new CoverageModel();
        coverage.setCoveredLines(0);
        coverage.setMissedLines(10);
        coverage.setLinePercent(0.0);
        method.setCoverage(coverage);
        submission.setFullProjectCoverage(projectCoverage(0.0, 0.0));

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals(CoverageInfo.RiskLevel.CRITICAL,
                request.getCoverage().getUncoveredPaths().get(0).getRiskLevel());
    }

    @Test
    void highComplexityNewMethodIncrementsNewHighComplexityCount() {
        AnalysisSubmissionModel submission = baseSubmission();
        CodeUnitModel method = submission.getFiles().get(0).getCodeUnits().get(0);
        method.setOperation(CodeUnitModel.OperationEnum.NEW);
        method.getMetrics().setCyclomaticComplexity(new RunArgs().getHighComplexityThreshold() + 1);

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals(1, request.getComplexity().getNewHighComplexityMethods());
        assertEquals(0, request.getComplexity().getModifiedHighComplexityMethods());
    }

    @Test
    void duplicationCloneFlagsSelfDuplicationWhenAllLocationsShareSignature() {
        AnalysisSubmissionModel submission = baseSubmission();
        DuplicationReportModel dup = new DuplicationReportModel();
        dup.setDuplicatedPercentage(5.0);
        CloneModel clone = new CloneModel();
        clone.setTokenCount(50);
        clone.setLineCount(10);
        clone.setIsCrossFile(false);
        clone.setLocations(Lists.newArrayList(
                cloneLocation("Foo.java", 1, 10, "com.example.Foo.doWork()"),
                cloneLocation("Foo.java", 20, 30, "com.example.Foo.doWork()")));
        dup.setClones(Lists.newArrayList(clone));
        submission.setDuplication(dup);

        LlmScoringRequest request = mapper.apply(submission);

        CloneDetail detail = request.getDuplication().getCloneDetails().get(0);
        assertTrue(detail.isSelfDuplication(),
                "two locations with the same method signature mark the clone as self-duplication");
        assertFalse(detail.isCrossFile());
    }

    @Test
    void duplicationCloneAllTestCodeFollowsFileTestFlag() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).setIsTest(true);

        DuplicationReportModel dup = new DuplicationReportModel();
        CloneModel clone = new CloneModel();
        clone.setLocations(Lists.newArrayList(
                cloneLocation("Foo.java", 1, 5, "sigA"),
                cloneLocation("Foo.java", 10, 15, "sigB")));
        dup.setClones(Lists.newArrayList(clone));
        submission.setDuplication(dup);

        LlmScoringRequest request = mapper.apply(submission);

        CloneDetail detail = request.getDuplication().getCloneDetails().get(0);
        assertTrue(detail.isAllTestCode(),
                "when every clone location lands in a test file, the clone must be flagged allTestCode");
    }

    @Test
    void languageFallsBackToFileExtensionWhenLanguageEnumIsAbsent() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).setLanguage(null);

        LlmScoringRequest request = mapper.apply(submission);

        assertEquals("java", request.getFileChanges().get(0).getLanguage(),
                "with no LanguageEnum set, mapper derives language from file extension");
    }

    @Test
    void filesWithOnlyImportAndBlankAdditionsProduceNoCodeBlockChanges() {
        AnalysisSubmissionModel submission = baseSubmission();
        submission.getFiles().get(0).setDiff(
                "--- a/Foo.java\n"
              + "+++ b/Foo.java\n"
              + "@@ -1,1 +1,3 @@\n"
              + " package com.example;\n"
              + "+import java.util.List;\n"
              + "+\n");

        LlmScoringRequest request = mapper.apply(submission);

        assertTrue(request.getCodeBlockChanges().isEmpty(),
                "a diff with only imports and blank lines must be filtered by effectiveChanges()");
    }

    private static AnalysisSubmissionModel baseSubmission() {
        CodeUnitModel method = new CodeUnitModel();
        method.setName("doWork");
        method.setSignature("com.example.Foo.doWork()");
        method.setKind(SymbolKindModel.METHOD);
        method.setOperation(CodeUnitModel.OperationEnum.NEW);
        method.setLocation(location(1, 10));
        method.setMetrics(baseMetrics());
        method.setCallers(Lists.newArrayList());
        method.setDiagnostics(Lists.newArrayList());
        JavaInfoModel java = new JavaInfoModel();
        java.setPackageName("com.example");
        java.setClassName("Foo");
        method.setJavaInfo(java);

        FileChangeModel file = new FileChangeModel();
        file.setPath("Foo.java");
        file.setChangeType(FileChangeModel.ChangeTypeEnum.MODIFY);
        file.setLanguage(FileChangeModel.LanguageEnum.JAVA);
        file.setIsTest(false);
        file.setDiff(METHOD_DIFF);
        file.setCodeUnits(Lists.newArrayList(method));

        CommitModel commit = new CommitModel();
        commit.setSha("abc123");
        commit.setMessage("test commit");
        commit.setAuthor("Jane");
        commit.setTimestamp(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        commit.setBranches(Lists.newArrayList("main"));

        ProjectModel project = new ProjectModel();
        project.setCode("proj-1");

        AnalysisSubmissionModel submission = new AnalysisSubmissionModel();
        submission.setProject(project);
        submission.setCommit(commit);
        submission.setFiles(Lists.newArrayList(file));
        submission.setFullProjectCoverage(projectCoverage(0.0, 0.0));
        submission.setDuplication(new DuplicationReportModel());
        return submission;
    }

    private static MetricsModel baseMetrics() {
        MetricsModel metrics = new MetricsModel();
        metrics.setCyclomaticComplexity(3);
        metrics.setCognitiveComplexity(2);
        metrics.setCodeLines(5);
        metrics.setNonCommentCodeLines(5);
        metrics.setNonCommentCodeStatements(4);
        metrics.setDirectInvocationCount(1);
        metrics.setParameterCount(0);
        metrics.setFanOut(0);
        metrics.setNpath(1L);
        metrics.setCommentLines(0);
        return metrics;
    }

    private static LocationModel location(int startLine, int endLine) {
        LocationModel loc = new LocationModel();
        loc.setStartLine(startLine);
        loc.setEndLine(endLine);
        return loc;
    }

    private static DriverScalerModel driverScaler(int population, int min, int max) {
        DriverScalerModel model = new DriverScalerModel();
        model.setPopulation(population);
        model.setLines(dimStats(min, max));
        model.setNcss(dimStats(min, max));
        model.setInvocations(dimStats(min, max));
        return model;
    }

    private static DimensionStatsModel dimStats(int min, int max) {
        DimensionStatsModel stats = new DimensionStatsModel();
        stats.setMin(min);
        stats.setP50((min + max) / 2.0);
        stats.setP75((min + max) * 3.0 / 4);
        stats.setP90((min + max) * 9.0 / 10);
        stats.setP95((min + max) * new RunArgs().getStatsQuantile());
        stats.setMax(max);
        return stats;
    }

    private static FullProjectCoverageModel projectCoverage(double linePct, double branchPct) {
        FullProjectCoverageModel coverage = new FullProjectCoverageModel();
        coverage.setLinePercentage(linePct);
        coverage.setBranchPercentage(branchPct);
        return coverage;
    }

    private static CloneLocationModel cloneLocation(String path, int startLine, int endLine, String signature) {
        CloneLocationModel loc = new CloneLocationModel();
        loc.setPath(path);
        loc.setLocation(location(startLine, endLine));
        loc.setCodeUnitSignature(signature);
        return loc;
    }
}
