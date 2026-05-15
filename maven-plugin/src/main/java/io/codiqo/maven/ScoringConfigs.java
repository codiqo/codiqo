package io.codiqo.maven;

import java.time.Duration;
import java.util.Optional;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.ScoringConfigModel;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ScoringConfigs {
    public ScoringConfigModel map(RunArgs args) {
        ScoringConfigModel toReturn = new ScoringConfigModel();

        toReturn.setCommitId(args.getCommitId());
        toReturn.setJdtlsVersion(args.getJdtlsVersion());
        toReturn.setPmdMinPriority(args.getPmdMinPriority());
        toReturn.setPmdRules(args.getPmdRules());
        toReturn.setSpotbugsPriorityThreshold(args.getSpotbugsPriorityThreshold());
        toReturn.setSpotbugsOmitVisitors(args.getSpotbugsOmitVisitors());

        toReturn.setIncludeUntracked(args.isIncludeUntracked());
        toReturn.setAutoBuild(args.isAutoBuild());
        toReturn.setDumpAnalysis(args.isDumpAnalysis());
        toReturn.setIgnoreCoverage(args.isIgnoreCoverage());
        toReturn.setIgnoreComplexity(args.isIgnoreComplexity());
        toReturn.setIgnoreCpd(args.isIgnoreCpd());
        toReturn.setIgnoreDiagnostics(args.isIgnoreDiagnostics());
        toReturn.setFailOnJdtlsError(args.isFailOnJdtlsError());
        toReturn.setHideSourceCode(args.isHideSourceCode());
        toReturn.setJdtUseSharedIndex(args.isJdtUseSharedIndex());

        toReturn.setBuildTimeout(Optional.ofNullable(args.getBuildTimeout()).map(Duration::toString).orElse(null));
        toReturn.setTestTimeout(Optional.ofNullable(args.getTestTimeout()).map(Duration::toString).orElse(null));
        toReturn.setImportTimeout(Optional.ofNullable(args.getImportTimeout()).map(Duration::toString).orElse(null));
        toReturn.setLspQueryTimeout(Optional.ofNullable(args.getLspQueryTimeout()).map(Duration::toString).orElse(null));
        toReturn.setConnectTimeout(Optional.ofNullable(args.getConnectTimeout()).map(Duration::toString).orElse(null));
        toReturn.setReadTimeout(Optional.ofNullable(args.getReadTimeout()).map(Duration::toString).orElse(null));

        toReturn.setMaxRequests(args.getMaxRequests());
        toReturn.setMaxRequestsPerHost(args.getMaxRequestsPerHost());
        toReturn.setCpdMinimumTileSize(args.getCpdMinimumTileSize());
        toReturn.setCpdIntroducedThreshold(args.getCpdIntroducedThreshold());
        toReturn.setDiffContextLines(args.getDiffContextLines());

        toReturn.setLlmModel(args.getLlmModel());
        toReturn.setLlmBaseUrl(args.getLlmBaseUrl());
        toReturn.setLlmTemperature(args.getLlmTemperature());
        toReturn.setLlmTopP(args.getLlmTopP());
        toReturn.setLlmMaxTokens(args.getLlmMaxTokens());
        toReturn.setLlmMaxRetries(Optional.ofNullable(args.getLlmMaxRetries()).map(Short::intValue).orElse(null));
        toReturn.setLlmEnableWebSearchTool(args.isLlmEnableWebSearchTool());

        toReturn.setIncludeBranches(args.getIncludeBranches());
        toReturn.setIncludeAuthorEmails(args.getIncludeAuthorEmails());

        toReturn.setSizeFactorDivisor(args.getSizeFactorDivisor());
        toReturn.setModifyMultiplierScale(args.getModifyMultiplierScale());
        toReturn.setModifyMultiplierCap(args.getModifyMultiplierCap());
        toReturn.setAddMultiplierScale(args.getAddMultiplierScale());
        toReturn.setQualityMultiplierMin(args.getQualityMultiplierMin());
        toReturn.setQualityMultiplierMax(args.getQualityMultiplierMax());

        toReturn.setStaticAnalysisPenaltyCap(args.getStaticAnalysisPenaltyCap());
        toReturn.setStaticAnalysisIntroducedPenalty(args.getStaticAnalysisIntroducedPenalty());
        toReturn.setStaticAnalysisPreExistingPenalty(args.getStaticAnalysisPreExistingPenalty());
        toReturn.setStaticAnalysisCleanBonus(args.getStaticAnalysisCleanBonus());
        toReturn.setArchitecturePenaltyCap(args.getArchitecturePenaltyCap());
        toReturn.setQualityGatePenaltyCap(args.getQualityGatePenaltyCap());

        toReturn.setVolumeExponent(args.getVolumeExponent());
        toReturn.setFilesScopeLogCoefficient(args.getFilesScopeLogCoefficient());
        toReturn.setFilesScopeMaxBonus(args.getFilesScopeMaxBonus());
        toReturn.setDriverScoreCapMultiplier(args.getDriverScoreCapMultiplier());
        toReturn.setDriverFactorMaxDeviation(args.getDriverFactorMaxDeviation());
        toReturn.setDriverScoreCapDryRun(args.isDriverScoreCapDryRun());
        toReturn.setStatsQuantile(args.getStatsQuantile());

        toReturn.setCoverageLowThreshold(args.getCoverageLowThreshold());
        toReturn.setCoverageCriticalThreshold(args.getCoverageCriticalThreshold());
        toReturn.setCoverageHighThreshold(args.getCoverageHighThreshold());
        toReturn.setHighComplexityThreshold(args.getHighComplexityThreshold());

        toReturn.setCpdCleanBonus(args.getCpdCleanBonus());
        toReturn.setCpdModeratePenalty(args.getCpdModeratePenalty());
        toReturn.setCpdHighPenalty(args.getCpdHighPenalty());
        toReturn.setCpdSeverePenalty(args.getCpdSeverePenalty());
        toReturn.setCpdCleanThreshold(args.getCpdCleanThreshold());
        toReturn.setCpdAcceptableThreshold(args.getCpdAcceptableThreshold());
        toReturn.setCpdModerateThreshold(args.getCpdModerateThreshold());
        toReturn.setCpdHighThreshold(args.getCpdHighThreshold());
        toReturn.setTestCodeScoreMultiplier(args.getTestCodeScoreMultiplier());
        toReturn.setTestCodePenaltyWeight(args.getTestCodePenaltyWeight());

        toReturn.setScoreThresholdHuge(args.getScoreThresholdHuge());
        toReturn.setScoreThresholdLarge(args.getScoreThresholdLarge());
        toReturn.setScoreThresholdMedium(args.getScoreThresholdMedium());
        toReturn.setScoreThresholdSmall(args.getScoreThresholdSmall());

        toReturn.setDimensionScoreCritical(args.getDimensionScoreCritical());
        toReturn.setDimensionScoreMajor(args.getDimensionScoreMajor());
        toReturn.setDimensionScoreModerate(args.getDimensionScoreModerate());

        toReturn.setCallerThresholdHigh(args.getCallerThresholdHigh());
        toReturn.setCallerThresholdModerate(args.getCallerThresholdModerate());

        toReturn.setMaxClonesToShow(args.getMaxClonesToShow());
        toReturn.setMaxSourceLines(args.getMaxSourceLines());
        toReturn.setTruncateSourceLines(args.getTruncateSourceLines());

        toReturn.setArchitectureBonusFactor(args.getArchitectureBonusFactor());

        toReturn.setPmdPriority1Penalty(args.getPmdPriority1Penalty());
        toReturn.setPmdPriority2Penalty(args.getPmdPriority2Penalty());
        toReturn.setPmdPriority3Penalty(args.getPmdPriority3Penalty());
        toReturn.setSpotbugsScariestPenalty(args.getSpotbugsScariestPenalty());
        toReturn.setSpotbugsScaryPenalty(args.getSpotbugsScaryPenalty());
        toReturn.setSpotbugsTroublingPenalty(args.getSpotbugsTroublingPenalty());

        toReturn.setCoverageExcellentBonus(args.getCoverageExcellentBonus());
        toReturn.setCoverageGoodBonus(args.getCoverageGoodBonus());
        toReturn.setCoverageLowPenalty(args.getCoverageLowPenalty());
        toReturn.setCoveragePoorPenalty(args.getCoveragePoorPenalty());
        toReturn.setCoverageTerriblePenalty(args.getCoverageTerriblePenalty());

        toReturn.setArchitectureMinorPenalty(args.getArchitectureMinorPenalty());
        toReturn.setArchitectureSolidPenalty(args.getArchitectureSolidPenalty());
        toReturn.setArchitectureMajorPenalty(args.getArchitectureMajorPenalty());
        toReturn.setQualityGateFailurePenalty(args.getQualityGateFailurePenalty());

        toReturn.setArchitectureImpactScoreThreshold(args.getArchitectureImpactScoreThreshold());
        toReturn.setArchitectureImpactCoverageRequired(args.getArchitectureImpactCoverageRequired());
        toReturn.setConcurrencyRiskThreshold(args.getConcurrencyRiskThreshold());
        toReturn.setIntegrationSurfaceThreshold(args.getIntegrationSurfaceThreshold());
        toReturn.setDataIntegrityThreshold(args.getDataIntegrityThreshold());
        toReturn.setSecuritySensitivityThreshold(args.getSecuritySensitivityThreshold());
        toReturn.setScalabilityImpactThreshold(args.getScalabilityImpactThreshold());
        toReturn.setObservabilityThreshold(args.getObservabilityThreshold());
        toReturn.setResilienceThreshold(args.getResilienceThreshold());
        toReturn.setPerformanceThreshold(args.getPerformanceThreshold());
        toReturn.setSeniorReviewThreshold(args.getSeniorReviewThreshold());
        toReturn.setSeniorReviewCriticalThreshold(args.getSeniorReviewCriticalThreshold());

        toReturn.setComplexityHighDisplayThreshold(args.getComplexityHighDisplayThreshold());
        toReturn.setComplexityModerateDisplayThreshold(args.getComplexityModerateDisplayThreshold());

        toReturn.setSimilarityCriticalThreshold(args.getSimilarityCriticalThreshold());
        toReturn.setSimilarityMajorThreshold(args.getSimilarityMajorThreshold());

        toReturn.setRiskHighDimensionThreshold(args.getRiskHighDimensionThreshold());
        toReturn.setRiskBaseMultiplier(args.getRiskBaseMultiplier());
        toReturn.setRiskHighDimensionPenalty(args.getRiskHighDimensionPenalty());
        toReturn.setRiskCoreLibraryPenalty(args.getRiskCoreLibraryPenalty());
        toReturn.setRiskBreakingChangesPenalty(args.getRiskBreakingChangesPenalty());
        toReturn.setRiskScoreMax(args.getRiskScoreMax());
        toReturn.setRiskLevelLowMax(args.getRiskLevelLowMax());
        toReturn.setRiskLevelModerateMax(args.getRiskLevelModerateMax());
        toReturn.setRiskLevelHighMax(args.getRiskLevelHighMax());
        toReturn.setRiskLevelVeryHighMax(args.getRiskLevelVeryHighMax());

        toReturn.setCoverageImpactExcellentMin(args.getCoverageImpactExcellentMin());
        toReturn.setCoverageImpactGoodMin(args.getCoverageImpactGoodMin());
        toReturn.setCoverageImpactAcceptableMin(args.getCoverageImpactAcceptableMin());
        toReturn.setCoverageImpactLowMin(args.getCoverageImpactLowMin());
        toReturn.setCoverageImpactPoorMin(args.getCoverageImpactPoorMin());

        toReturn.setFanOutHighThreshold(args.getFanOutHighThreshold());
        toReturn.setNpathComplexThreshold(args.getNpathComplexThreshold());

        return toReturn;
    }
}
