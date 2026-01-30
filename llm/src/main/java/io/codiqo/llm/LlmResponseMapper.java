package io.codiqo.llm;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;

import io.codiqo.client.model.AnalysisResultModel;
import io.codiqo.client.model.ArchitectureAnalysisModel;
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
import io.codiqo.client.model.VolumeScoreModel;
import io.codiqo.llm.schema.LlmScoringResponse;

public class LlmResponseMapper {
    public void mapToAnalysisResult(LlmScoringResponse llmResponse, AnalysisResultModel result) {
        result.setChangeClassification(mapChangeClassification(llmResponse.getChangeClassification()));
        result.setRiskAssessment(mapRiskAssessment(llmResponse.getRiskAssessment()));
        result.setBugs(mapBugs(llmResponse.getBugs()));
        result.setRequiresSeniorReview(llmResponse.getRequiresSeniorReview());
        result.setSeniorReviewReasons(llmResponse.getSeniorReviewReasons());
        result.setTags(mapTags(llmResponse.getTags()));
        result.setAssessment(mapAssessment(llmResponse));
    }
    private static AnalysisResultModel.ChangeClassificationEnum mapChangeClassification(LlmScoringResponse.ChangeClassification classification) {
        if (Objects.isNull(classification)) {
            return AnalysisResultModel.ChangeClassificationEnum.MEDIUM;
        }
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
        if (Objects.isNull(riskAssessment)) {
            return null;
        }
        RiskAssessmentModel toReturn = new RiskAssessmentModel();
        toReturn.setRiskScore(riskAssessment.getRiskScore());
        toReturn.setRiskLevel(mapRiskLevel(riskAssessment.getRiskLevel()));
        return toReturn;
    }
    private static RiskAssessmentModel.RiskLevelEnum mapRiskLevel(LlmScoringResponse.RiskLevel riskLevel) {
        if (Objects.isNull(riskLevel)) {
            return RiskAssessmentModel.RiskLevelEnum.LOW;
        }
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
        if (Objects.isNull(bugs)) {
            return null;
        }
        BugsModel toReturn = new BugsModel();
        toReturn.setBlocking(mapBugList(bugs.getBlocking()));
        toReturn.setMajor(mapBugList(bugs.getMajor()));
        toReturn.setMinor(mapBugList(bugs.getMinor()));
        toReturn.setHasBlockingBugs(CollectionUtils.isNotEmpty(bugs.getBlocking()));
        return toReturn;
    }
    private static List<BugModel> mapBugList(List<LlmScoringResponse.Bug> bugs) {
        if (CollectionUtils.isEmpty(bugs)) {
            return Collections.emptyList();
        }
        return bugs.stream()
                .map(LlmResponseMapper::mapBug)
                .collect(Collectors.toList());
    }
    private static BugModel mapBug(LlmScoringResponse.Bug bug) {
        BugModel toReturn = new BugModel();
        toReturn.setType(mapBugType(bug.getType()));
        toReturn.setTitle(bug.getTitle());
        toReturn.setDescription(bug.getDescription());
        toReturn.setFile(bug.getFile());
        toReturn.setLine(bug.getLine());
        toReturn.setSuggestedFix(bug.getSuggestedFix());
        toReturn.setConfidence(mapConfidence(bug.getConfidence()));
        toReturn.setSource(mapBugSource(bug.getSource()));
        return toReturn;
    }
    private static BugModel.TypeEnum mapBugType(LlmScoringResponse.BugType type) {
        if (Objects.isNull(type)) {
            return BugModel.TypeEnum.OTHER;
        }
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
        if (Objects.isNull(confidence)) {
            return BugModel.ConfidenceEnum.MEDIUM;
        }
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
        if (Objects.isNull(source)) {
            return BugModel.SourceEnum.LLM;
        }
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
        if (Objects.isNull(tags)) {
            return null;
        }
        ReviewTagsModel toReturn = new ReviewTagsModel();
        toReturn.setTechnical(Optional.ofNullable(tags.getTechnical()).orElse(Collections.emptyList()));
        toReturn.setFunctional(Optional.ofNullable(tags.getFunctional()).orElse(Collections.emptyList()));
        return toReturn;
    }
    private static AssessmentModel mapAssessment(LlmScoringResponse llmResponse) {
        AssessmentModel toReturn = new AssessmentModel();
        toReturn.setThinking(llmResponse.getThinking());
        toReturn.setEffortBreakdown(mapEffortBreakdown(llmResponse.getEffortBreakdown()));
        toReturn.setQualityMultiplier(mapQualityMultiplier(llmResponse.getQualityMultiplier()));
        toReturn.setArchitectureEffortBonus(mapArchitectureEffortBonus(llmResponse.getArchitectureEffortBonus()));
        toReturn.setRiskDimensions(mapQualityDimensions(llmResponse.getQualityDimensions()));
        toReturn.setStaticAnalysisReview(mapStaticAnalysisReview(llmResponse.getStaticAnalysisReview()));
        toReturn.setBlastRadiusAnalysis(mapBlastRadiusAnalysis(llmResponse.getBlastRadiusAnalysis()));
        return toReturn;
    }
    private static EffortBreakdownModel mapEffortBreakdown(LlmScoringResponse.EffortBreakdown effortBreakdown) {
        if (Objects.isNull(effortBreakdown)) {
            return null;
        }
        EffortBreakdownModel toReturn = new EffortBreakdownModel();
        toReturn.setVolumeScore(mapVolumeScore(effortBreakdown.getVolumeScore()));
        toReturn.setComplexityMultiplier(mapComplexityMultiplier(effortBreakdown.getComplexityMultiplier()));
        toReturn.setBaseEffortScore(effortBreakdown.getBaseEffortScore());
        return toReturn;
    }
    private static VolumeScoreModel mapVolumeScore(LlmScoringResponse.VolumeScore volumeScore) {
        if (Objects.isNull(volumeScore)) {
            return null;
        }
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
        if (Objects.isNull(complexityMultiplier)) {
            return null;
        }
        ComplexityMultiplierModel toReturn = new ComplexityMultiplierModel();
        toReturn.setAvgModifyComplexity(complexityMultiplier.getAvgModifyComplexity());
        toReturn.setModifyMultiplier(complexityMultiplier.getModifyMultiplier());
        toReturn.setAvgCreateComplexity(complexityMultiplier.getAvgCreateComplexity());
        toReturn.setCreateMultiplier(complexityMultiplier.getCreateMultiplier());
        toReturn.setCombinedMultiplier(complexityMultiplier.getCombinedMultiplier());
        return toReturn;
    }
    private static QualityMultiplierModel mapQualityMultiplier(LlmScoringResponse.QualityMultiplier qualityMultiplier) {
        if (Objects.isNull(qualityMultiplier)) {
            return null;
        }
        QualityMultiplierModel toReturn = new QualityMultiplierModel();
        toReturn.setCpdAnalysis(mapCpdAnalysis(qualityMultiplier.getCpdAnalysis()));
        toReturn.setStaticAnalysis(mapStaticAnalysisImpact(qualityMultiplier.getStaticAnalysis()));
        toReturn.setCoverageAnalysis(mapCoverageAnalysis(qualityMultiplier.getCoverageAnalysis()));
        toReturn.setArchitectureAnalysis(mapArchitectureAnalysis(qualityMultiplier.getArchitectureAnalysis()));
        toReturn.setQualityGateAnalysis(mapQualityGateAnalysis(qualityMultiplier.getQualityGateAnalysis()));
        toReturn.setFinalMultiplier(qualityMultiplier.getFinalMultiplier());
        return toReturn;
    }
    private static CpdAnalysisModel mapCpdAnalysis(LlmScoringResponse.CpdAnalysis cpdAnalysis) {
        if (Objects.isNull(cpdAnalysis)) {
            return null;
        }
        CpdAnalysisModel toReturn = new CpdAnalysisModel();
        toReturn.setDuplicationPercent(cpdAnalysis.getDuplicationPercent());
        toReturn.setImpact(cpdAnalysis.getImpact());
        return toReturn;
    }
    private static StaticAnalysisImpactModel mapStaticAnalysisImpact(LlmScoringResponse.StaticAnalysisImpact staticAnalysis) {
        if (Objects.isNull(staticAnalysis)) {
            return null;
        }
        StaticAnalysisImpactModel toReturn = new StaticAnalysisImpactModel();
        toReturn.setPmdViolationsInChanges(staticAnalysis.getPmdViolationsInChanges());
        toReturn.setSpotbugsIssuesInChanges(staticAnalysis.getSpotbugsIssuesInChanges());
        toReturn.setImpact(staticAnalysis.getImpact());
        return toReturn;
    }
    private static CoverageAnalysisModel mapCoverageAnalysis(LlmScoringResponse.CoverageAnalysis coverageAnalysis) {
        if (Objects.isNull(coverageAnalysis)) {
            return null;
        }
        CoverageAnalysisModel toReturn = new CoverageAnalysisModel();
        toReturn.setCoveragePercent(coverageAnalysis.getCoveragePercent());
        toReturn.setImpact(coverageAnalysis.getImpact());
        return toReturn;
    }
    private static ArchitectureAnalysisModel mapArchitectureAnalysis(LlmScoringResponse.ArchitectureAnalysis architectureAnalysis) {
        if (Objects.isNull(architectureAnalysis)) {
            return null;
        }
        ArchitectureAnalysisModel toReturn = new ArchitectureAnalysisModel();
        toReturn.setSolidViolations(Optional.ofNullable(architectureAnalysis.getSolidViolations()).orElse(Collections.emptyList()));
        toReturn.setArchitectureIssues(Optional.ofNullable(architectureAnalysis.getArchitectureIssues()).orElse(Collections.emptyList()));
        toReturn.setPenaltyImpact(architectureAnalysis.getPenaltyImpact());
        return toReturn;
    }
    private static ArchitectureEffortBonusModel mapArchitectureEffortBonus(LlmScoringResponse.ArchitectureEffortBonus bonus) {
        if (Objects.isNull(bonus)) {
            return null;
        }
        ArchitectureEffortBonusModel toReturn = new ArchitectureEffortBonusModel();
        toReturn.setArchitectureImpactScore(bonus.getArchitectureImpactScore());
        toReturn.setQualityFactor(bonus.getQualityFactor());
        toReturn.setBaseEffort(bonus.getBaseEffort());
        toReturn.setBonusCalculation(bonus.getBonusCalculation());
        toReturn.setBonusPoints(bonus.getBonusPoints());
        return toReturn;
    }
    private static QualityGateAnalysisModel mapQualityGateAnalysis(LlmScoringResponse.QualityGateAnalysis qualityGateAnalysis) {
        if (Objects.isNull(qualityGateAnalysis)) {
            return null;
        }
        QualityGateAnalysisModel toReturn = new QualityGateAnalysisModel();
        toReturn.setFailedGates(Optional.ofNullable(qualityGateAnalysis.getFailedGates()).orElse(Collections.emptyList()));
        toReturn.setImpact(qualityGateAnalysis.getImpact());
        return toReturn;
    }
    private static RiskDimensionsModel mapQualityDimensions(LlmScoringResponse.QualityDimensions qualityDimensions) {
        if (Objects.isNull(qualityDimensions)) {
            return null;
        }
        RiskDimensionsModel toReturn = new RiskDimensionsModel();
        toReturn.setArchitectureImpact(mapDimensionScore(qualityDimensions.getArchitectureImpact()));
        toReturn.setConcurrencyRisk(mapDimensionScore(qualityDimensions.getConcurrencyRisk()));
        toReturn.setIntegrationSurface(mapDimensionScore(qualityDimensions.getIntegrationSurface()));
        toReturn.setDataIntegrity(mapDimensionScore(qualityDimensions.getDataIntegrity()));
        toReturn.setSecuritySensitivity(mapDimensionScore(qualityDimensions.getSecuritySensitivity()));
        toReturn.setScalabilityImpact(mapDimensionScore(qualityDimensions.getScalabilityImpact()));
        toReturn.setObservability(mapDimensionScore(qualityDimensions.getObservability()));
        toReturn.setResilience(mapDimensionScore(qualityDimensions.getResilience()));
        toReturn.setPerformance(mapDimensionScore(qualityDimensions.getPerformance()));
        toReturn.setTestingCoverage(mapDimensionScore(qualityDimensions.getTestingCoverage()));
        return toReturn;
    }
    private static DimensionScoreModel mapDimensionScore(LlmScoringResponse.DimensionScore dimensionScore) {
        if (Objects.isNull(dimensionScore)) {
            return null;
        }
        DimensionScoreModel toReturn = new DimensionScoreModel();
        toReturn.setScore(dimensionScore.getScore());
        toReturn.setRationale(dimensionScore.getRationale());
        toReturn.setQualityGateMet(dimensionScore.isQualityGateMet());
        return toReturn;
    }
    private static StaticAnalysisReviewModel mapStaticAnalysisReview(LlmScoringResponse.StaticAnalysisReview staticAnalysisReview) {
        if (Objects.isNull(staticAnalysisReview)) {
            return null;
        }
        StaticAnalysisReviewModel toReturn = new StaticAnalysisReviewModel();
        toReturn.setPmdInChangedLines(mapFindings(staticAnalysisReview.getPmdInChangedLines()));
        toReturn.setPmdPreExisting(mapFindings(staticAnalysisReview.getPmdPreExisting()));
        toReturn.setPmdFalsePositives(mapFindings(staticAnalysisReview.getPmdFalsePositives()));
        toReturn.setSpotbugsInChangedLines(mapFindings(staticAnalysisReview.getSpotbugsInChangedLines()));
        toReturn.setSpotbugsPreExisting(mapFindings(staticAnalysisReview.getSpotbugsPreExisting()));
        toReturn.setSpotbugsFalsePositives(mapFindings(staticAnalysisReview.getSpotbugsFalsePositives()));
        return toReturn;
    }
    private static List<StaticAnalysisFindingModel> mapFindings(List<LlmScoringResponse.StaticAnalysisFinding> findings) {
        if (CollectionUtils.isEmpty(findings)) {
            return Collections.emptyList();
        }
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
        toReturn.setSeverity(mapFindingSeverity(finding.getSeverity()));
        return toReturn;
    }
    private static StaticAnalysisFindingModel.SeverityEnum mapFindingSeverity(LlmScoringResponse.FindingSeverity severity) {
        if (Objects.isNull(severity)) {
            return StaticAnalysisFindingModel.SeverityEnum.INFO;
        }
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
        if (Objects.isNull(blastRadiusAnalysis)) {
            return null;
        }
        BlastRadiusAnalysisModel toReturn = new BlastRadiusAnalysisModel();
        toReturn.setTotalCallers(blastRadiusAnalysis.getTotalCallers());
        toReturn.setProductionCallers(blastRadiusAnalysis.getProductionCallers());
        toReturn.setTestCallers(blastRadiusAnalysis.getTestCallers());
        toReturn.setRiskLevel(mapBlastRadiusRiskLevel(blastRadiusAnalysis.getRiskLevel()));
        toReturn.setCriticalCallers(Optional.ofNullable(blastRadiusAnalysis.getCriticalCallers()).orElse(Collections.emptyList()));
        toReturn.setExplanation(blastRadiusAnalysis.getExplanation());
        toReturn.setModuleType(mapModuleType(blastRadiusAnalysis.getModuleType()));
        toReturn.setSignatureChanges(mapSignatureChanges(blastRadiusAnalysis.getSignatureChanges()));
        toReturn.setExternalImpactEstimate(mapExternalImpactEstimate(blastRadiusAnalysis.getExternalImpactEstimate()));
        return toReturn;
    }
    private static BlastRadiusAnalysisModel.RiskLevelEnum mapBlastRadiusRiskLevel(LlmScoringResponse.RiskLevel riskLevel) {
        if (Objects.isNull(riskLevel)) {
            return BlastRadiusAnalysisModel.RiskLevelEnum.LOW;
        }
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
        if (Objects.isNull(moduleType)) {
            return null;
        }
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
        if (Objects.isNull(signatureChanges)) {
            return null;
        }
        SignatureChangesModel toReturn = new SignatureChangesModel();
        toReturn.setHasBreakingChanges(signatureChanges.isHasBreakingChanges());
        toReturn.setChangedSignatures(Optional.ofNullable(signatureChanges.getChangedSignatures()).orElse(Collections.emptyList()));
        toReturn.setBreakingChangeType(mapBreakingChangeType(signatureChanges.getBreakingChangeType()));
        return toReturn;
    }
    private static SignatureChangesModel.BreakingChangeTypeEnum mapBreakingChangeType(LlmScoringResponse.BreakingChangeType breakingChangeType) {
        if (Objects.isNull(breakingChangeType)) {
            return null;
        }
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
        if (Objects.isNull(externalImpactEstimate)) {
            return null;
        }
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
