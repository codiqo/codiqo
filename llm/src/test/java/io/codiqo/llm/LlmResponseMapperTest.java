package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.Lists;

import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.BlastRadiusAnalysisModel;
import io.codiqo.client.model.BugModel;
import io.codiqo.client.model.ChangeMagnitudeModel;
import io.codiqo.client.model.LlmAnalysisModel;
import io.codiqo.client.model.ModifyImpactEstimateModel;
import io.codiqo.client.model.RiskAssessmentModel;
import io.codiqo.client.model.SignatureChangesModel;
import io.codiqo.client.model.StaticAnalysisFindingModel;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.BlastRadiusAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.BreakingChangeType;
import io.codiqo.llm.schema.LlmScoringResponse.Bug;
import io.codiqo.llm.schema.LlmScoringResponse.BugSource;
import io.codiqo.llm.schema.LlmScoringResponse.BugType;
import io.codiqo.llm.schema.LlmScoringResponse.Bugs;
import io.codiqo.llm.schema.LlmScoringResponse.ChangeClassification;
import io.codiqo.llm.schema.LlmScoringResponse.Confidence;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.ExternalImpact;
import io.codiqo.llm.schema.LlmScoringResponse.FindingSeverity;
import io.codiqo.llm.schema.LlmScoringResponse.ModuleType;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.RiskAssessment;
import io.codiqo.llm.schema.LlmScoringResponse.RiskLevel;
import io.codiqo.llm.schema.LlmScoringResponse.SignatureChanges;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisFinding;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisReview;
import io.codiqo.llm.schema.LlmScoringResponse.Tags;
import io.codiqo.llm.schema.LlmScoringResponse.VolumeScore;

class LlmResponseMapperTest {
    private final LlmResponseMapper mapper = new LlmResponseMapper();

    @Test
    void emptyResponseMapsToDefaultsWithoutNullProduction() {
        LlmScoringResponse response = new LlmScoringResponse();
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(AnalysisResultModel.ChangeClassificationEnum.MEDIUM, result.getChangeClassification(),
                "missing classification must default to MEDIUM");
        assertNull(result.getRiskAssessment(), "absent riskAssessment is skipped, not stubbed");
        assertNull(result.getBugs(), "absent bugs is skipped, not stubbed");
        assertNull(result.getTags(), "absent tags is skipped, not stubbed");
        assertNotNull(result.getAssessment(), "assessment is always produced");
        assertNull(result.getAssessment().getEffortBreakdown());
        assertNull(result.getAssessment().getQualityMultiplier());
        assertNull(result.getAssessment().getBlastRadiusAnalysis());
        assertTrue(result.getModifyImpactEstimates().isEmpty(), "absent estimates map to an empty list, never null");
    }

    @Test
    void modifyImpactEstimateMapsAllEnumsAndFields() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setModifyImpactEstimates(Lists.newArrayList(
                LlmScoringResponse.ModifyImpactEstimate.builder()
                        .signature("com.example.Foo.bar()")
                        .file("Foo.java")
                        .coverageDirection(LlmScoringResponse.CoverageDirection.IMPROVED)
                        .coverageMagnitude(LlmScoringResponse.ChangeMagnitude.MODERATE)
                        .duplicationDirection(LlmScoringResponse.DuplicationDirection.REDUCED)
                        .duplicationMagnitude(LlmScoringResponse.ChangeMagnitude.SLIGHT)
                        .confidence(Confidence.HIGH)
                        .rationale("added tests and extracted a duplicated block")
                        .build()));
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        ModifyImpactEstimateModel mapped = result.getModifyImpactEstimates().get(0);
        assertEquals("com.example.Foo.bar()", mapped.getSignature());
        assertEquals("Foo.java", mapped.getFile());
        assertEquals(ModifyImpactEstimateModel.CoverageDirectionEnum.IMPROVED, mapped.getCoverageDirection());
        assertEquals(ChangeMagnitudeModel.MODERATE, mapped.getCoverageMagnitude());
        assertEquals(ModifyImpactEstimateModel.DuplicationDirectionEnum.REDUCED, mapped.getDuplicationDirection());
        assertEquals(ChangeMagnitudeModel.SLIGHT, mapped.getDuplicationMagnitude());
        assertEquals(ModifyImpactEstimateModel.ConfidenceEnum.HIGH, mapped.getConfidence());
        assertEquals("added tests and extracted a duplicated block", mapped.getRationale());
    }

    @Test
    void modifyImpactEstimateDefaultsApplyWhenEnumsOmitted() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setModifyImpactEstimates(Lists.newArrayList(
                LlmScoringResponse.ModifyImpactEstimate.builder().signature("com.example.Foo.bar()").build()));
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        ModifyImpactEstimateModel mapped = result.getModifyImpactEstimates().get(0);
        assertEquals(ModifyImpactEstimateModel.CoverageDirectionEnum.UNKNOWN, mapped.getCoverageDirection());
        assertEquals(ChangeMagnitudeModel.NONE, mapped.getCoverageMagnitude());
        assertEquals(ModifyImpactEstimateModel.DuplicationDirectionEnum.UNKNOWN, mapped.getDuplicationDirection());
        assertEquals(ChangeMagnitudeModel.NONE, mapped.getDuplicationMagnitude());
        assertEquals(ModifyImpactEstimateModel.ConfidenceEnum.LOW, mapped.getConfidence());
    }

    @Test
    void nullRiskLevelFallsBackToLow() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setRiskAssessment(RiskAssessment.builder().riskScore(7).build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(7, result.getRiskAssessment().getRiskScore());
        assertEquals(RiskAssessmentModel.RiskLevelEnum.LOW, result.getRiskAssessment().getRiskLevel(),
                "fail-fast policy at call site: missing LLM risk level falls back to LOW, not null");
    }

    @Test
    void bugFieldDefaultsApplyWhenEnumsOmitted() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBugs(Bugs.builder()
                .blocking(Lists.newArrayList(Bug.builder().title("blocker").build()))
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        BugModel mapped = result.getBugs().getBlocking().get(0);
        assertEquals(BugModel.TypeEnum.OTHER, mapped.getType());
        assertEquals(BugModel.ConfidenceEnum.MEDIUM, mapped.getConfidence());
        assertEquals(BugModel.SourceEnum.LLM, mapped.getSource());
        assertTrue(result.getBugs().getHasBlockingBugs());
        assertTrue(result.getBugs().getMajor().isEmpty(), "missing major list becomes empty");
        assertTrue(result.getBugs().getMinor().isEmpty(), "missing minor list becomes empty");
    }

    @Test
    void emptyBugsSetHasBlockingBugsFalse() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBugs(new Bugs());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertFalse(result.getBugs().getHasBlockingBugs());
    }

    @Test
    void tagsWithNullSubListsBecomeEmpty() {
        LlmScoringResponse response = new LlmScoringResponse();
        Tags tags = new Tags();
        tags.setTechnical(null);
        tags.setFunctional(null);
        response.setTags(tags);
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertTrue(result.getTags().getTechnical().isEmpty());
        assertTrue(result.getTags().getFunctional().isEmpty());
    }

    @Test
    void scoreAndSummaryArePassedThrough() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setScore(42.5);
        response.setScoreCalculation("0.5 * base");
        response.setSummary("looks fine");
        response.setRequiresSeniorReview(3);
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(42.5, result.getScore());
        assertEquals("0.5 * base", result.getScoreCalculation());
        assertEquals("looks fine", result.getLlmSummary());
        assertEquals(3, result.getRequiresSeniorReview());
    }

    @Test
    void effortBreakdownMapsVolumeAndComplexityWhenPresent() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setEffortBreakdown(EffortBreakdown.builder()
                .baseEffortScore(12.75)
                .volumeScore(VolumeScore.builder()
                        .linesChanged(100)
                        .filesChanged(5)
                        .totalVolumeScore(77.0)
                        .build())
                .complexityMultiplier(LlmScoringResponse.ComplexityMultiplier.builder()
                        .combinedMultiplier(1.6)
                        .build())
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(12.75, result.getAssessment().getEffortBreakdown().getBaseEffortScore());
        assertEquals(100, result.getAssessment().getEffortBreakdown().getVolumeScore().getLinesChanged());
        assertEquals(5, result.getAssessment().getEffortBreakdown().getVolumeScore().getFilesChanged());
        assertEquals(77.0, result.getAssessment().getEffortBreakdown().getVolumeScore().getTotalVolumeScore());
        assertEquals(1.6, result.getAssessment().getEffortBreakdown().getComplexityMultiplier().getCombinedMultiplier());
    }

    @Test
    void qualityMultiplierSkipsMissingSubAnalyses() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(0.8).build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(0.8, result.getAssessment().getQualityMultiplier().getFinalMultiplier());
        assertNull(result.getAssessment().getQualityMultiplier().getCpdAnalysis());
        assertNull(result.getAssessment().getQualityMultiplier().getStaticAnalysis());
        assertNull(result.getAssessment().getQualityMultiplier().getCoverageAnalysis());
        assertNull(result.getAssessment().getQualityMultiplier().getArchitectureAnalysis());
        assertNull(result.getAssessment().getQualityMultiplier().getQualityGateAnalysis());
    }

    @Test
    void qualityDimensionsMapsOnlyProvidedDimensions() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setQualityDimensions(QualityDimensions.builder()
                .architectureImpact(DimensionScore.builder().score(4).rationale("solid").qualityGateMet(true).build())
                .performance(DimensionScore.builder().score(2).rationale("ok").qualityGateMet(false).build())
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(4, result.getAssessment().getRiskDimensions().getArchitectureImpact().getScore());
        assertEquals("solid", result.getAssessment().getRiskDimensions().getArchitectureImpact().getRationale());
        assertTrue(result.getAssessment().getRiskDimensions().getArchitectureImpact().getQualityGateMet());
        assertEquals(2, result.getAssessment().getRiskDimensions().getPerformance().getScore());
        assertNull(result.getAssessment().getRiskDimensions().getConcurrencyRisk(), "dimensions not supplied stay null");
        assertNull(result.getAssessment().getRiskDimensions().getSecuritySensitivity());
    }

    @Test
    void staticAnalysisReviewDefaultsAllSixListsWhenInputLacksThem() {
        LlmScoringResponse response = new LlmScoringResponse();
        StaticAnalysisReview review = new StaticAnalysisReview();
        review.setPmdInChangedLines(null);
        review.setPmdPreExisting(null);
        review.setPmdFalsePositives(null);
        review.setSpotbugsInChangedLines(null);
        review.setSpotbugsPreExisting(null);
        review.setSpotbugsFalsePositives(null);
        response.setStaticAnalysisReview(review);
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertTrue(result.getAssessment().getStaticAnalysisReview().getPmdInChangedLines().isEmpty());
        assertTrue(result.getAssessment().getStaticAnalysisReview().getPmdPreExisting().isEmpty());
        assertTrue(result.getAssessment().getStaticAnalysisReview().getPmdFalsePositives().isEmpty());
        assertTrue(result.getAssessment().getStaticAnalysisReview().getSpotbugsInChangedLines().isEmpty());
        assertTrue(result.getAssessment().getStaticAnalysisReview().getSpotbugsPreExisting().isEmpty());
        assertTrue(result.getAssessment().getStaticAnalysisReview().getSpotbugsFalsePositives().isEmpty());
    }

    @Test
    void staticAnalysisFindingNullSeverityFallsBackToInfo() {
        LlmScoringResponse response = new LlmScoringResponse();
        StaticAnalysisReview review = new StaticAnalysisReview();
        review.setPmdInChangedLines(Lists.newArrayList(
                StaticAnalysisFinding.builder().rule("R1").file("F.java").line(10).assessment("tp").build()));
        response.setStaticAnalysisReview(review);
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        StaticAnalysisFindingModel finding = result.getAssessment().getStaticAnalysisReview().getPmdInChangedLines().get(0);
        assertEquals("R1", finding.getRule());
        assertEquals("F.java", finding.getFile());
        assertEquals(10, finding.getLine());
        assertEquals(StaticAnalysisFindingModel.SeverityEnum.INFO, finding.getSeverity());
    }

    @Test
    void architectureEffortBonusIsMappedEndToEnd() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(4)
                .qualityFactor(0.9)
                .baseEffort(20.0)
                .bonusCalculation("4 * 0.9 * 20")
                .bonusPoints(72.0)
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(4, result.getAssessment().getArchitectureEffortBonus().getArchitectureImpactScore());
        assertEquals(0.9, result.getAssessment().getArchitectureEffortBonus().getQualityFactor());
        assertEquals(20.0, result.getAssessment().getArchitectureEffortBonus().getBaseEffort());
        assertEquals("4 * 0.9 * 20", result.getAssessment().getArchitectureEffortBonus().getBonusCalculation());
        assertEquals(72.0, result.getAssessment().getArchitectureEffortBonus().getBonusPoints());
    }

    @Test
    void blastRadiusVeryHighCollapsesToHigh() {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBlastRadiusAnalysis(BlastRadiusAnalysis.builder()
                .totalCallers(12)
                .productionCallers(9)
                .testCallers(3)
                .riskLevel(RiskLevel.VERY_HIGH)
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(BlastRadiusAnalysisModel.RiskLevelEnum.HIGH, result.getAssessment().getBlastRadiusAnalysis().getRiskLevel(),
                "VERY_HIGH collapses into HIGH for the public blast-radius enum");
    }

    @ParameterizedTest
    @EnumSource(RiskLevel.class)
    void blastRadiusMapsEveryRiskLevelEnum(RiskLevel level) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBlastRadiusAnalysis(BlastRadiusAnalysis.builder().riskLevel(level).build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertNotNull(result.getAssessment().getBlastRadiusAnalysis().getRiskLevel(),
                "every input RiskLevel must map to some output enum");
    }

    @ParameterizedTest
    @EnumSource(ChangeClassification.class)
    void changeClassificationIsExhaustivelyMapped(ChangeClassification input) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setChangeClassification(input);
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(input.name(), result.getChangeClassification().name());
    }

    @ParameterizedTest
    @EnumSource(BugType.class)
    void bugTypeIsExhaustivelyMapped(BugType input) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBugs(Bugs.builder()
                .blocking(Lists.newArrayList(Bug.builder().type(input).build()))
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(input.name(), result.getBugs().getBlocking().get(0).getType().name());
    }

    @ParameterizedTest
    @EnumSource(Confidence.class)
    void bugConfidenceIsExhaustivelyMapped(Confidence input) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBugs(Bugs.builder()
                .blocking(Lists.newArrayList(Bug.builder().confidence(input).build()))
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(input.name(), result.getBugs().getBlocking().get(0).getConfidence().name());
    }

    @ParameterizedTest
    @EnumSource(BugSource.class)
    void bugSourceIsExhaustivelyMapped(BugSource input) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBugs(Bugs.builder()
                .blocking(Lists.newArrayList(Bug.builder().source(input).build()))
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(input.name(), result.getBugs().getBlocking().get(0).getSource().name());
    }

    @ParameterizedTest
    @EnumSource(FindingSeverity.class)
    void findingSeverityIsExhaustivelyMapped(FindingSeverity input) {
        LlmScoringResponse response = new LlmScoringResponse();
        StaticAnalysisReview review = new StaticAnalysisReview();
        review.setPmdInChangedLines(Lists.newArrayList(
                StaticAnalysisFinding.builder().rule("R").severity(input).build()));
        response.setStaticAnalysisReview(review);
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(input.name(),
                result.getAssessment().getStaticAnalysisReview().getPmdInChangedLines().get(0).getSeverity().name());
    }

    @ParameterizedTest
    @EnumSource(ModuleType.class)
    void moduleTypeIsExhaustivelyMapped(ModuleType input) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBlastRadiusAnalysis(BlastRadiusAnalysis.builder().moduleType(input).build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(input.name(), result.getAssessment().getBlastRadiusAnalysis().getModuleType().name());
    }

    @ParameterizedTest
    @EnumSource(ExternalImpact.class)
    void externalImpactIsExhaustivelyMapped(ExternalImpact input) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBlastRadiusAnalysis(BlastRadiusAnalysis.builder().externalImpactEstimate(input).build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertEquals(input.name(),
                result.getAssessment().getBlastRadiusAnalysis().getExternalImpactEstimate().name());
    }

    @ParameterizedTest
    @EnumSource(BreakingChangeType.class)
    void breakingChangeTypeIsExhaustivelyMapped(BreakingChangeType input) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setBlastRadiusAnalysis(BlastRadiusAnalysis.builder()
                .signatureChanges(SignatureChanges.builder()
                        .hasBreakingChanges(true)
                        .breakingChangeType(input)
                        .build())
                .build());
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        SignatureChangesModel mapped = result.getAssessment().getBlastRadiusAnalysis().getSignatureChanges();
        assertTrue(mapped.getHasBreakingChanges());
        assertEquals(input.name(), mapped.getBreakingChangeType().name());
    }

    @Test
    void blastRadiusCollectionsDefaultToEmptyWhenNull() {
        LlmScoringResponse response = new LlmScoringResponse();
        BlastRadiusAnalysis blast = new BlastRadiusAnalysis();
        blast.setCriticalCallers(null);
        response.setBlastRadiusAnalysis(blast);
        AnalysisResultModel result = new AnalysisResultModel();

        mapper.mapToAnalysisResult(response, result);

        assertTrue(result.getAssessment().getBlastRadiusAnalysis().getCriticalCallers().isEmpty());
        assertEquals(RiskAssessmentModel.RiskLevelEnum.LOW.name(),
                result.getAssessment().getBlastRadiusAnalysis().getRiskLevel().name(),
                "missing blast-radius riskLevel falls back to LOW");
    }

    @Test
    void mapLlmAnalysisReportsTokenTotalsAndSkipsToolUsageWhenAbsent() {
        ScoringResult scoring = ScoringResult.builder()
                .thinking("chain-of-thought")
                .promptLength(4096)
                .promptTokens(1000)
                .completionTokens(250)
                .build();

        LlmAnalysisModel analysis = LlmResponseMapper.mapLlmAnalysis(scoring, Duration.ofMillis(1500), "gpt-test");

        assertEquals("gpt-test", analysis.getModel());
        assertEquals(1500, analysis.getAnalysisTimeMs());
        assertEquals("chain-of-thought", analysis.getThinking());
        assertEquals(4096, analysis.getPromptLength());
        assertEquals(1000, analysis.getTokenUsage().getPromptTokens());
        assertEquals(250, analysis.getTokenUsage().getCompletionTokens());
        assertEquals(1250, analysis.getTokenUsage().getTotalTokens());
        assertNull(analysis.getToolUsage(), "toolUsage is only attached when tools were actually invoked");
    }

    @Test
    void mapLlmAnalysisAttachesToolUsageWhenToolsWereCalled() {
        ScoringResult scoring = ScoringResult.builder()
                .promptTokens(10)
                .completionTokens(5)
                .toolCallsMade(Lists.newArrayList("web_search", "web_search", "fetch"))
                .build();

        LlmAnalysisModel analysis = LlmResponseMapper.mapLlmAnalysis(scoring, Duration.ZERO, "gpt-test");

        assertNotNull(analysis.getToolUsage());
        assertEquals(3, analysis.getToolUsage().getToolCallsCount());
    }

}
