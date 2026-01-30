package io.codiqo.llm;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;

import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.ArchitectureAnalysisModel;
import io.codiqo.client.model.LlmAnalysisModel;
import io.codiqo.client.model.ArchitectureEffortBonusModel;
import io.codiqo.client.model.AssessmentModel;
import io.codiqo.client.model.BlastRadiusAnalysisModel;
import io.codiqo.client.model.BugModel;
import io.codiqo.client.model.BugsModel;
import io.codiqo.client.model.ComplexityMultiplierModel;
import io.codiqo.client.model.CoverageAnalysisModel;
import io.codiqo.client.model.CpdAnalysisModel;
import io.codiqo.client.model.DimensionScoreModel;
import io.codiqo.client.model.EffortBreakdownModel;
import io.codiqo.client.model.QualityGateAnalysisModel;
import io.codiqo.client.model.QualityMultiplierModel;
import io.codiqo.client.model.ReviewTagsModel;
import io.codiqo.client.model.RiskAssessmentModel;
import io.codiqo.client.model.RiskDimensionsModel;
import io.codiqo.client.model.SignatureChangesModel;
import io.codiqo.client.model.StaticAnalysisFindingModel;
import io.codiqo.client.model.StaticAnalysisImpactModel;
import io.codiqo.client.model.StaticAnalysisReviewModel;
import io.codiqo.client.model.TokenUsageModel;
import io.codiqo.client.model.ToolUsageModel;
import io.codiqo.client.model.VolumeScoreModel;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringResponse;

public class LlmResponseMapper {
    public void mapToAnalysisResult(LlmScoringResponse llmResponse, AnalysisResultModel result) {
        result.setChangeClassification(Optional.ofNullable(llmResponse.getChangeClassification())
                .map(LlmResponseMapper::mapChangeClassification)
                .orElse(AnalysisResultModel.ChangeClassificationEnum.MEDIUM));
        if (Objects.nonNull(llmResponse.getRiskAssessment())) {
            result.setRiskAssessment(mapRiskAssessment(llmResponse.getRiskAssessment()));
        }
        if (Objects.nonNull(llmResponse.getBugs())) {
            result.setBugs(mapBugs(llmResponse.getBugs()));
        }
        result.setRequiresSeniorReview(llmResponse.getRequiresSeniorReview());
        result.setSeniorReviewReasons(llmResponse.getSeniorReviewReasons());
        if (Objects.nonNull(llmResponse.getTags())) {
            result.setTags(mapTags(llmResponse.getTags()));
        }
        result.setAssessment(mapAssessment(llmResponse));
        result.setScore(llmResponse.getScore());
        result.setScoreCalculation(llmResponse.getScoreCalculation());
        result.setLlmSummary(llmResponse.getSummary());
    }
    public static LlmAnalysisModel mapLlmAnalysis(ScoringResult scoringResult, Duration duration, String model) {
        LlmAnalysisModel toReturn = new LlmAnalysisModel();
        toReturn.setModel(model);
        toReturn.setAnalysisTimeMs((int) duration.toMillis());
        toReturn.setThinking(scoringResult.getThinking());
        toReturn.setPromptLength(scoringResult.getPromptLength());

        TokenUsageModel tokenUsage = new TokenUsageModel();
        tokenUsage.setPromptTokens(scoringResult.getPromptTokens());
        tokenUsage.setCompletionTokens(scoringResult.getCompletionTokens());
        tokenUsage.setTotalTokens(scoringResult.getTotalTokens());
        toReturn.setTokenUsage(tokenUsage);

        if (scoringResult.usedTools()) {
            ToolUsageModel toolUsage = new ToolUsageModel();
            toolUsage.setToolCallsCount(scoringResult.getToolCallsMade().size());
            toReturn.setToolUsage(toolUsage);
        }
        return toReturn;
    }
    private static AnalysisResultModel.ChangeClassificationEnum mapChangeClassification(LlmScoringResponse.ChangeClassification classification) {
        switch (classification) {
            case TRIVIAL:
                return AnalysisResultModel.ChangeClassificationEnum.TRIVIAL;
            case SMALL:
                return AnalysisResultModel.ChangeClassificationEnum.SMALL;
            case MEDIUM:
                return AnalysisResultModel.ChangeClassificationEnum.MEDIUM;
            case LARGE:
                return AnalysisResultModel.ChangeClassificationEnum.LARGE;
            case HUGE:
                return AnalysisResultModel.ChangeClassificationEnum.HUGE;
            default:
                throw new IllegalArgumentException("Unknown change classification: " + classification);
        }
    }
    private static RiskAssessmentModel mapRiskAssessment(LlmScoringResponse.RiskAssessment riskAssessment) {
        RiskAssessmentModel toReturn = new RiskAssessmentModel();
        toReturn.setRiskScore(riskAssessment.getRiskScore());
        toReturn.setRiskLevel(Optional.ofNullable(riskAssessment.getRiskLevel())
                .map(LlmResponseMapper::mapRiskLevel)
                .orElse(RiskAssessmentModel.RiskLevelEnum.LOW));
        return toReturn;
    }
    private static RiskAssessmentModel.RiskLevelEnum mapRiskLevel(LlmScoringResponse.RiskLevel riskLevel) {
        switch (riskLevel) {
            case LOW:
                return RiskAssessmentModel.RiskLevelEnum.LOW;
            case MODERATE:
                return RiskAssessmentModel.RiskLevelEnum.MODERATE;
            case HIGH:
                return RiskAssessmentModel.RiskLevelEnum.HIGH;
            case VERY_HIGH:
                return RiskAssessmentModel.RiskLevelEnum.VERY_HIGH;
            case CRITICAL:
                return RiskAssessmentModel.RiskLevelEnum.CRITICAL;
            default:
                throw new IllegalArgumentException("Unknown risk level: " + riskLevel);
        }
    }
    private static BugsModel mapBugs(LlmScoringResponse.Bugs bugs) {
        BugsModel toReturn = new BugsModel();
        toReturn.setBlocking(Optional.ofNullable(bugs.getBlocking())
                .map(LlmResponseMapper::mapBugList).orElse(Collections.emptyList()));
        toReturn.setMajor(Optional.ofNullable(bugs.getMajor())
                .map(LlmResponseMapper::mapBugList).orElse(Collections.emptyList()));
        toReturn.setMinor(Optional.ofNullable(bugs.getMinor())
                .map(LlmResponseMapper::mapBugList).orElse(Collections.emptyList()));
        toReturn.setHasBlockingBugs(CollectionUtils.isNotEmpty(bugs.getBlocking()));
        return toReturn;
    }
    private static List<BugModel> mapBugList(List<LlmScoringResponse.Bug> bugs) {
        return bugs.stream()
                .map(LlmResponseMapper::mapBug)
                .collect(Collectors.toList());
    }
    private static BugModel mapBug(LlmScoringResponse.Bug bug) {
        BugModel toReturn = new BugModel();
        toReturn.setType(Optional.ofNullable(bug.getType())
                .map(LlmResponseMapper::mapBugType).orElse(BugModel.TypeEnum.OTHER));
        toReturn.setTitle(bug.getTitle());
        toReturn.setDescription(bug.getDescription());
        toReturn.setFile(bug.getFile());
        toReturn.setLine(bug.getLine());
        toReturn.setSuggestedFix(bug.getSuggestedFix());
        toReturn.setConfidence(Optional.ofNullable(bug.getConfidence())
                .map(LlmResponseMapper::mapConfidence).orElse(BugModel.ConfidenceEnum.MEDIUM));
        toReturn.setSource(Optional.ofNullable(bug.getSource())
                .map(LlmResponseMapper::mapBugSource).orElse(BugModel.SourceEnum.LLM));
        return toReturn;
    }
    private static BugModel.TypeEnum mapBugType(LlmScoringResponse.BugType type) {
        switch (type) {
            case SECURITY:
                return BugModel.TypeEnum.SECURITY;
            case CONCURRENCY:
                return BugModel.TypeEnum.CONCURRENCY;
            case DATA_CORRUPTION:
                return BugModel.TypeEnum.DATA_CORRUPTION;
            case LOGIC:
                return BugModel.TypeEnum.LOGIC;
            case RESOURCE_LEAK:
                return BugModel.TypeEnum.RESOURCE_LEAK;
            case NULL_POINTER:
                return BugModel.TypeEnum.NULL_POINTER;
            case ARCHITECTURE:
                return BugModel.TypeEnum.ARCHITECTURE;
            case OTHER:
                return BugModel.TypeEnum.OTHER;
            default:
                throw new IllegalArgumentException("Unknown bug type: " + type);
        }
    }
    private static BugModel.ConfidenceEnum mapConfidence(LlmScoringResponse.Confidence confidence) {
        switch (confidence) {
            case HIGH:
                return BugModel.ConfidenceEnum.HIGH;
            case MEDIUM:
                return BugModel.ConfidenceEnum.MEDIUM;
            case LOW:
                return BugModel.ConfidenceEnum.LOW;
            default:
                throw new IllegalArgumentException("Unknown confidence: " + confidence);
        }
    }
    private static BugModel.SourceEnum mapBugSource(LlmScoringResponse.BugSource source) {
        switch (source) {
            case LLM:
                return BugModel.SourceEnum.LLM;
            case PMD:
                return BugModel.SourceEnum.PMD;
            case SPOTBUGS:
                return BugModel.SourceEnum.SPOTBUGS;
            default:
                throw new IllegalArgumentException("Unknown bug source: " + source);
        }
    }
    private static ReviewTagsModel mapTags(LlmScoringResponse.Tags tags) {
        ReviewTagsModel toReturn = new ReviewTagsModel();
        toReturn.setTechnical(Optional.ofNullable(tags.getTechnical()).orElse(Collections.emptyList()));
        toReturn.setFunctional(Optional.ofNullable(tags.getFunctional()).orElse(Collections.emptyList()));
        return toReturn;
    }
    private static AssessmentModel mapAssessment(LlmScoringResponse llmResponse) {
        AssessmentModel toReturn = new AssessmentModel();
        toReturn.setThinking(llmResponse.getThinking());
        if (Objects.nonNull(llmResponse.getEffortBreakdown())) {
            toReturn.setEffortBreakdown(mapEffortBreakdown(llmResponse.getEffortBreakdown()));
        }
        if (Objects.nonNull(llmResponse.getQualityMultiplier())) {
            toReturn.setQualityMultiplier(mapQualityMultiplier(llmResponse.getQualityMultiplier()));
        }
        if (Objects.nonNull(llmResponse.getArchitectureEffortBonus())) {
            toReturn.setArchitectureEffortBonus(mapArchitectureEffortBonus(llmResponse.getArchitectureEffortBonus()));
        }
        if (Objects.nonNull(llmResponse.getQualityDimensions())) {
            toReturn.setRiskDimensions(mapQualityDimensions(llmResponse.getQualityDimensions()));
        }
        if (Objects.nonNull(llmResponse.getStaticAnalysisReview())) {
            toReturn.setStaticAnalysisReview(mapStaticAnalysisReview(llmResponse.getStaticAnalysisReview()));
        }
        if (Objects.nonNull(llmResponse.getBlastRadiusAnalysis())) {
            toReturn.setBlastRadiusAnalysis(mapBlastRadiusAnalysis(llmResponse.getBlastRadiusAnalysis()));
        }
        return toReturn;
    }
    private static EffortBreakdownModel mapEffortBreakdown(LlmScoringResponse.EffortBreakdown effortBreakdown) {
        EffortBreakdownModel toReturn = new EffortBreakdownModel();
        if (Objects.nonNull(effortBreakdown.getVolumeScore())) {
            toReturn.setVolumeScore(mapVolumeScore(effortBreakdown.getVolumeScore()));
        }
        if (Objects.nonNull(effortBreakdown.getComplexityMultiplier())) {
            toReturn.setComplexityMultiplier(mapComplexityMultiplier(effortBreakdown.getComplexityMultiplier()));
        }
        toReturn.setBaseEffortScore(effortBreakdown.getBaseEffortScore());
        return toReturn;
    }
    private static VolumeScoreModel mapVolumeScore(LlmScoringResponse.VolumeScore volumeScore) {
        VolumeScoreModel toReturn = new VolumeScoreModel();
        toReturn.setLinesChanged(volumeScore.getLinesChanged());
        toReturn.setLinesScore(volumeScore.getLinesScore());
        toReturn.setMethodsModified(volumeScore.getMethodsModified());
        toReturn.setMethodsModifiedScore(volumeScore.getMethodsModifiedScore());
        toReturn.setMethodsAdded(volumeScore.getMethodsAdded());
        toReturn.setMethodsAddedScore(volumeScore.getMethodsAddedScore());
        toReturn.setClassesModified(volumeScore.getClassesModified());
        toReturn.setClassesModifiedScore(volumeScore.getClassesModifiedScore());
        toReturn.setClassesAdded(volumeScore.getClassesAdded());
        toReturn.setClassesAddedScore(volumeScore.getClassesAddedScore());
        toReturn.setSizeFactor(volumeScore.getSizeFactor());
        toReturn.setModifyMultiplier(volumeScore.getModifyMultiplier());
        toReturn.setAddMultiplier(volumeScore.getAddMultiplier());
        toReturn.setRelativeAdjustment(volumeScore.getRelativeAdjustment());
        toReturn.setTotalVolumeScore(volumeScore.getTotalVolumeScore());
        return toReturn;
    }
    private static ComplexityMultiplierModel mapComplexityMultiplier(LlmScoringResponse.ComplexityMultiplier complexityMultiplier) {
        ComplexityMultiplierModel toReturn = new ComplexityMultiplierModel();
        toReturn.setAvgModifyComplexity(complexityMultiplier.getAvgModifyComplexity());
        toReturn.setModifyMultiplier(complexityMultiplier.getModifyMultiplier());
        toReturn.setAvgCreateComplexity(complexityMultiplier.getAvgCreateComplexity());
        toReturn.setCreateMultiplier(complexityMultiplier.getCreateMultiplier());
        toReturn.setCombinedMultiplier(complexityMultiplier.getCombinedMultiplier());
        return toReturn;
    }
    private static QualityMultiplierModel mapQualityMultiplier(LlmScoringResponse.QualityMultiplier qualityMultiplier) {
        QualityMultiplierModel toReturn = new QualityMultiplierModel();
        if (Objects.nonNull(qualityMultiplier.getCpdAnalysis())) {
            toReturn.setCpdAnalysis(mapCpdAnalysis(qualityMultiplier.getCpdAnalysis()));
        }
        if (Objects.nonNull(qualityMultiplier.getStaticAnalysis())) {
            toReturn.setStaticAnalysis(mapStaticAnalysisImpact(qualityMultiplier.getStaticAnalysis()));
        }
        if (Objects.nonNull(qualityMultiplier.getCoverageAnalysis())) {
            toReturn.setCoverageAnalysis(mapCoverageAnalysis(qualityMultiplier.getCoverageAnalysis()));
        }
        if (Objects.nonNull(qualityMultiplier.getArchitectureAnalysis())) {
            toReturn.setArchitectureAnalysis(mapArchitectureAnalysis(qualityMultiplier.getArchitectureAnalysis()));
        }
        if (Objects.nonNull(qualityMultiplier.getQualityGateAnalysis())) {
            toReturn.setQualityGateAnalysis(mapQualityGateAnalysis(qualityMultiplier.getQualityGateAnalysis()));
        }
        toReturn.setFinalMultiplier(qualityMultiplier.getFinalMultiplier());
        return toReturn;
    }
    private static CpdAnalysisModel mapCpdAnalysis(LlmScoringResponse.CpdAnalysis cpdAnalysis) {
        CpdAnalysisModel toReturn = new CpdAnalysisModel();
        toReturn.setDuplicationPercent(cpdAnalysis.getDuplicationPercent());
        toReturn.setImpact(cpdAnalysis.getImpact());
        return toReturn;
    }
    private static StaticAnalysisImpactModel mapStaticAnalysisImpact(LlmScoringResponse.StaticAnalysisImpact staticAnalysis) {
        StaticAnalysisImpactModel toReturn = new StaticAnalysisImpactModel();
        toReturn.setPmdViolationsInChanges(staticAnalysis.getPmdViolationsInChanges());
        toReturn.setSpotbugsIssuesInChanges(staticAnalysis.getSpotbugsIssuesInChanges());
        toReturn.setImpact(staticAnalysis.getImpact());
        return toReturn;
    }
    private static CoverageAnalysisModel mapCoverageAnalysis(LlmScoringResponse.CoverageAnalysis coverageAnalysis) {
        CoverageAnalysisModel toReturn = new CoverageAnalysisModel();
        toReturn.setCoveragePercent(coverageAnalysis.getCoveragePercent());
        toReturn.setImpact(coverageAnalysis.getImpact());
        return toReturn;
    }
    private static ArchitectureAnalysisModel mapArchitectureAnalysis(LlmScoringResponse.ArchitectureAnalysis architectureAnalysis) {
        ArchitectureAnalysisModel toReturn = new ArchitectureAnalysisModel();
        toReturn.setSolidViolations(Optional.ofNullable(architectureAnalysis.getSolidViolations()).orElse(Collections.emptyList()));
        toReturn.setArchitectureIssues(Optional.ofNullable(architectureAnalysis.getArchitectureIssues()).orElse(Collections.emptyList()));
        toReturn.setPenaltyImpact(architectureAnalysis.getPenaltyImpact());
        return toReturn;
    }
    private static ArchitectureEffortBonusModel mapArchitectureEffortBonus(LlmScoringResponse.ArchitectureEffortBonus bonus) {
        ArchitectureEffortBonusModel toReturn = new ArchitectureEffortBonusModel();
        toReturn.setArchitectureImpactScore(bonus.getArchitectureImpactScore());
        toReturn.setQualityFactor(bonus.getQualityFactor());
        toReturn.setBaseEffort(bonus.getBaseEffort());
        toReturn.setBonusCalculation(bonus.getBonusCalculation());
        toReturn.setBonusPoints(bonus.getBonusPoints());
        return toReturn;
    }
    private static QualityGateAnalysisModel mapQualityGateAnalysis(LlmScoringResponse.QualityGateAnalysis qualityGateAnalysis) {
        QualityGateAnalysisModel toReturn = new QualityGateAnalysisModel();
        toReturn.setFailedGates(Optional.ofNullable(qualityGateAnalysis.getFailedGates()).orElse(Collections.emptyList()));
        toReturn.setImpact(qualityGateAnalysis.getImpact());
        return toReturn;
    }
    private static RiskDimensionsModel mapQualityDimensions(LlmScoringResponse.QualityDimensions qualityDimensions) {
        RiskDimensionsModel toReturn = new RiskDimensionsModel();
        if (Objects.nonNull(qualityDimensions.getArchitectureImpact())) {
            toReturn.setArchitectureImpact(mapDimensionScore(qualityDimensions.getArchitectureImpact()));
        }
        if (Objects.nonNull(qualityDimensions.getConcurrencyRisk())) {
            toReturn.setConcurrencyRisk(mapDimensionScore(qualityDimensions.getConcurrencyRisk()));
        }
        if (Objects.nonNull(qualityDimensions.getIntegrationSurface())) {
            toReturn.setIntegrationSurface(mapDimensionScore(qualityDimensions.getIntegrationSurface()));
        }
        if (Objects.nonNull(qualityDimensions.getDataIntegrity())) {
            toReturn.setDataIntegrity(mapDimensionScore(qualityDimensions.getDataIntegrity()));
        }
        if (Objects.nonNull(qualityDimensions.getSecuritySensitivity())) {
            toReturn.setSecuritySensitivity(mapDimensionScore(qualityDimensions.getSecuritySensitivity()));
        }
        if (Objects.nonNull(qualityDimensions.getScalabilityImpact())) {
            toReturn.setScalabilityImpact(mapDimensionScore(qualityDimensions.getScalabilityImpact()));
        }
        if (Objects.nonNull(qualityDimensions.getObservability())) {
            toReturn.setObservability(mapDimensionScore(qualityDimensions.getObservability()));
        }
        if (Objects.nonNull(qualityDimensions.getResilience())) {
            toReturn.setResilience(mapDimensionScore(qualityDimensions.getResilience()));
        }
        if (Objects.nonNull(qualityDimensions.getPerformance())) {
            toReturn.setPerformance(mapDimensionScore(qualityDimensions.getPerformance()));
        }
        if (Objects.nonNull(qualityDimensions.getTestingCoverage())) {
            toReturn.setTestingCoverage(mapDimensionScore(qualityDimensions.getTestingCoverage()));
        }
        return toReturn;
    }
    private static DimensionScoreModel mapDimensionScore(LlmScoringResponse.DimensionScore dimensionScore) {
        DimensionScoreModel toReturn = new DimensionScoreModel();
        toReturn.setScore(dimensionScore.getScore());
        toReturn.setRationale(dimensionScore.getRationale());
        toReturn.setQualityGateMet(dimensionScore.isQualityGateMet());
        return toReturn;
    }
    private static StaticAnalysisReviewModel mapStaticAnalysisReview(LlmScoringResponse.StaticAnalysisReview staticAnalysisReview) {
        StaticAnalysisReviewModel toReturn = new StaticAnalysisReviewModel();
        toReturn.setPmdInChangedLines(Optional.ofNullable(staticAnalysisReview.getPmdInChangedLines())
                .map(LlmResponseMapper::mapFindings).orElse(Collections.emptyList()));
        toReturn.setPmdPreExisting(Optional.ofNullable(staticAnalysisReview.getPmdPreExisting())
                .map(LlmResponseMapper::mapFindings).orElse(Collections.emptyList()));
        toReturn.setPmdFalsePositives(Optional.ofNullable(staticAnalysisReview.getPmdFalsePositives())
                .map(LlmResponseMapper::mapFindings).orElse(Collections.emptyList()));
        toReturn.setSpotbugsInChangedLines(Optional.ofNullable(staticAnalysisReview.getSpotbugsInChangedLines())
                .map(LlmResponseMapper::mapFindings).orElse(Collections.emptyList()));
        toReturn.setSpotbugsPreExisting(Optional.ofNullable(staticAnalysisReview.getSpotbugsPreExisting())
                .map(LlmResponseMapper::mapFindings).orElse(Collections.emptyList()));
        toReturn.setSpotbugsFalsePositives(Optional.ofNullable(staticAnalysisReview.getSpotbugsFalsePositives())
                .map(LlmResponseMapper::mapFindings).orElse(Collections.emptyList()));
        return toReturn;
    }
    private static List<StaticAnalysisFindingModel> mapFindings(List<LlmScoringResponse.StaticAnalysisFinding> findings) {
        return findings.stream()
                .map(LlmResponseMapper::mapFinding)
                .collect(Collectors.toList());
    }
    private static StaticAnalysisFindingModel mapFinding(LlmScoringResponse.StaticAnalysisFinding finding) {
        StaticAnalysisFindingModel toReturn = new StaticAnalysisFindingModel();
        toReturn.setRule(finding.getRule());
        toReturn.setFile(finding.getFile());
        toReturn.setLine(finding.getLine());
        toReturn.setAssessment(finding.getAssessment());
        toReturn.setSeverity(Optional.ofNullable(finding.getSeverity())
                .map(LlmResponseMapper::mapFindingSeverity)
                .orElse(StaticAnalysisFindingModel.SeverityEnum.INFO));
        return toReturn;
    }
    private static StaticAnalysisFindingModel.SeverityEnum mapFindingSeverity(LlmScoringResponse.FindingSeverity severity) {
        switch (severity) {
            case ERROR:
                return StaticAnalysisFindingModel.SeverityEnum.ERROR;
            case WARNING:
                return StaticAnalysisFindingModel.SeverityEnum.WARNING;
            case INFO:
                return StaticAnalysisFindingModel.SeverityEnum.INFO;
            default:
                throw new IllegalArgumentException("Unknown finding severity: " + severity);
        }
    }
    private static BlastRadiusAnalysisModel mapBlastRadiusAnalysis(LlmScoringResponse.BlastRadiusAnalysis blastRadiusAnalysis) {
        BlastRadiusAnalysisModel toReturn = new BlastRadiusAnalysisModel();
        toReturn.setTotalCallers(blastRadiusAnalysis.getTotalCallers());
        toReturn.setProductionCallers(blastRadiusAnalysis.getProductionCallers());
        toReturn.setTestCallers(blastRadiusAnalysis.getTestCallers());
        toReturn.setRiskLevel(Optional.ofNullable(blastRadiusAnalysis.getRiskLevel())
                .map(LlmResponseMapper::mapBlastRadiusRiskLevel)
                .orElse(BlastRadiusAnalysisModel.RiskLevelEnum.LOW));
        toReturn.setCriticalCallers(Optional.ofNullable(blastRadiusAnalysis.getCriticalCallers()).orElse(Collections.emptyList()));
        toReturn.setExplanation(blastRadiusAnalysis.getExplanation());
        if (Objects.nonNull(blastRadiusAnalysis.getModuleType())) {
            toReturn.setModuleType(mapModuleType(blastRadiusAnalysis.getModuleType()));
        }
        if (Objects.nonNull(blastRadiusAnalysis.getSignatureChanges())) {
            toReturn.setSignatureChanges(mapSignatureChanges(blastRadiusAnalysis.getSignatureChanges()));
        }
        if (Objects.nonNull(blastRadiusAnalysis.getExternalImpactEstimate())) {
            toReturn.setExternalImpactEstimate(mapExternalImpactEstimate(blastRadiusAnalysis.getExternalImpactEstimate()));
        }
        return toReturn;
    }
    private static BlastRadiusAnalysisModel.RiskLevelEnum mapBlastRadiusRiskLevel(LlmScoringResponse.RiskLevel riskLevel) {
        switch (riskLevel) {
            case LOW:
                return BlastRadiusAnalysisModel.RiskLevelEnum.LOW;
            case MODERATE:
                return BlastRadiusAnalysisModel.RiskLevelEnum.MODERATE;
            case HIGH:
            case VERY_HIGH:
                return BlastRadiusAnalysisModel.RiskLevelEnum.HIGH;
            case CRITICAL:
                return BlastRadiusAnalysisModel.RiskLevelEnum.CRITICAL;
            default:
                throw new IllegalArgumentException("Unknown risk level: " + riskLevel);
        }
    }
    private static BlastRadiusAnalysisModel.ModuleTypeEnum mapModuleType(LlmScoringResponse.ModuleType moduleType) {
        switch (moduleType) {
            case CORE_LIBRARY:
                return BlastRadiusAnalysisModel.ModuleTypeEnum.CORE_LIBRARY;
            case SHARED_UTILITY:
                return BlastRadiusAnalysisModel.ModuleTypeEnum.SHARED_UTILITY;
            case LEAF_APPLICATION:
                return BlastRadiusAnalysisModel.ModuleTypeEnum.LEAF_APPLICATION;
            default:
                throw new IllegalArgumentException("Unknown module type: " + moduleType);
        }
    }
    private static SignatureChangesModel mapSignatureChanges(LlmScoringResponse.SignatureChanges signatureChanges) {
        SignatureChangesModel toReturn = new SignatureChangesModel();
        toReturn.setHasBreakingChanges(signatureChanges.isHasBreakingChanges());
        toReturn.setChangedSignatures(Optional.ofNullable(signatureChanges.getChangedSignatures()).orElse(Collections.emptyList()));
        if (Objects.nonNull(signatureChanges.getBreakingChangeType())) {
            toReturn.setBreakingChangeType(mapBreakingChangeType(signatureChanges.getBreakingChangeType()));
        }
        return toReturn;
    }
    private static SignatureChangesModel.BreakingChangeTypeEnum mapBreakingChangeType(LlmScoringResponse.BreakingChangeType breakingChangeType) {
        switch (breakingChangeType) {
            case PARAMETER_ADDED:
                return SignatureChangesModel.BreakingChangeTypeEnum.PARAMETER_ADDED;
            case PARAMETER_REMOVED:
                return SignatureChangesModel.BreakingChangeTypeEnum.PARAMETER_REMOVED;
            case PARAMETER_CHANGED:
                return SignatureChangesModel.BreakingChangeTypeEnum.PARAMETER_CHANGED;
            case RETURN_TYPE_CHANGED:
                return SignatureChangesModel.BreakingChangeTypeEnum.RETURN_TYPE_CHANGED;
            case EXCEPTION_ADDED:
                return SignatureChangesModel.BreakingChangeTypeEnum.EXCEPTION_ADDED;
            default:
                throw new IllegalArgumentException("Unknown breaking change type: " + breakingChangeType);
        }
    }
    private static BlastRadiusAnalysisModel.ExternalImpactEstimateEnum mapExternalImpactEstimate(LlmScoringResponse.ExternalImpact externalImpactEstimate) {
        switch (externalImpactEstimate) {
            case NONE:
                return BlastRadiusAnalysisModel.ExternalImpactEstimateEnum.NONE;
            case LOW:
                return BlastRadiusAnalysisModel.ExternalImpactEstimateEnum.LOW;
            case MEDIUM:
                return BlastRadiusAnalysisModel.ExternalImpactEstimateEnum.MEDIUM;
            case HIGH:
                return BlastRadiusAnalysisModel.ExternalImpactEstimateEnum.HIGH;
            case UNKNOWN:
                return BlastRadiusAnalysisModel.ExternalImpactEstimateEnum.UNKNOWN;
            default:
                throw new IllegalArgumentException("Unknown external impact: " + externalImpactEstimate);
        }
    }
}
