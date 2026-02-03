package io.codiqo.llm;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CallerInfo;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo.CloneDetail;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.BlastRadiusAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.ComplexityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.CoverageAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.CpdAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import io.codiqo.llm.schema.LlmScoringResponse.QualityGateAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.RiskAssessment;
import io.codiqo.llm.schema.LlmScoringResponse.SignatureChanges;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisImpact;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisReview;
import io.codiqo.llm.schema.LlmScoringResponse.VolumeScore;
import lombok.Builder;
import lombok.Value;

public class HtmlReportBuilder implements ReportBuilder {
    private static final String TEMPLATE_COMMIT_ANALYSIS = "commit-analysis";

    private final RunArgs args;
    private final TemplateEngine templateEngine;

    public HtmlReportBuilder(RunArgs args) {
        this.args = Objects.requireNonNull(args);

        ClassLoaderTemplateResolver htmlResolver = new ClassLoaderTemplateResolver();
        htmlResolver.setPrefix("thymeleaf/html/");
        htmlResolver.setSuffix(".html");
        htmlResolver.setTemplateMode(TemplateMode.HTML);
        htmlResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        htmlResolver.setCacheable(true);

        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(htmlResolver);
    }
    @Override
    public String buildReport(ScoringResult result, LlmScoringRequest request, ReportContext reportContext) {
        Context ctx = new Context(Locale.ENGLISH);
        LlmScoringResponse response = result.getResponse();

        ctx.setVariable("commitId", reportContext.getCommitId());
        ctx.setVariable("commitIdShort", reportContext.getCommitId());
        ctx.setVariable("author", reportContext.getAuthor());
        ctx.setVariable("authorEmail", reportContext.getAuthorEmail());
        ctx.setVariable("timestamp", reportContext.getTimestamp());
        ctx.setVariable("message", reportContext.getCommitMessage());
        ctx.setVariable("mergeCommit", reportContext.isMergeCommit());
        ctx.setVariable("revertCommit", reportContext.isRevertCommit());
        ctx.setVariable("revertedCommitId", reportContext.getRevertedCommitId());
        ctx.setVariable("gpgSignature", false);
        ctx.setVariable("repositoryName", reportContext.getRepositoryName());
        ctx.setVariable("generatedAt", LocalDateTime.now());
        ctx.setVariable("branches", Optional.ofNullable(reportContext.getBranches()).orElse(Collections.emptyList()));
        ctx.setVariable("llmModel", reportContext.getLlmModel());
        ctx.setVariable("llmInputTokens", result.getPromptTokens());
        ctx.setVariable("llmOutputTokens", result.getCompletionTokens());
        ctx.setVariable("llmTotalTokens", result.getTotalTokens());
        ctx.setVariable("llmDurationMillis", reportContext.getAnalysisDuration().toMillis());
        ctx.setVariable("totalScore", response.getScore());
        ctx.setVariable("scoreThresholdHuge", args.getScoreThresholdHuge());
        ctx.setVariable("scoreThresholdLarge", args.getScoreThresholdLarge());
        ctx.setVariable("scoreThresholdMedium", args.getScoreThresholdMedium());
        ctx.setVariable("scoreThresholdSmall", args.getScoreThresholdSmall());
        ctx.setVariable("dimScoreCritical", args.getDimensionScoreCritical());
        ctx.setVariable("dimScoreMajor", args.getDimensionScoreMajor());
        ctx.setVariable("dimScoreModerate", args.getDimensionScoreModerate());
        ctx.setVariable("seniorReviewCritical", args.getSeniorReviewCriticalThreshold());
        ctx.setVariable("seniorReviewMajor", args.getSeniorReviewThreshold());
        ctx.setVariable("complexityHighDisplay", args.getComplexityHighDisplayThreshold());
        ctx.setVariable("complexityModerateDisplay", args.getComplexityModerateDisplayThreshold());
        ctx.setVariable("similarityCritical", args.getSimilarityCriticalThreshold());
        ctx.setVariable("similarityMajor", args.getSimilarityMajorThreshold());
        ctx.setVariable("changeClassification", response.getChangeClassification());
        ctx.setVariable("scoreCalculation", response.getScoreCalculation());
        ctx.setVariable("seniorReviewScore", response.getRequiresSeniorReview());
        ctx.setVariable("volumeExponent", args.getVolumeExponent());
        ctx.setVariable("filesScopeFactor", args.getFilesScopeFactor());
        ctx.setVariable("fileDensityThreshold", args.getFileDensityThreshold());
        ctx.setVariable("linesLogFactor", args.getLinesLogFactor());
        ctx.setVariable("codeBlocksModLogFactor", args.getCodeBlocksModifiedLogFactor());
        ctx.setVariable("codeBlocksAddLogFactor", args.getCodeBlocksAddedLogFactor());
        ctx.setVariable("classesModLogFactor", args.getClassesModifiedLogFactor());
        ctx.setVariable("classesAddLogFactor", args.getClassesAddedLogFactor());
        ctx.setVariable("architectureBonusFactor", args.getArchitectureBonusFactor());
        ctx.setVariable("qualityMultiplierMin", args.getQualityMultiplierMin());
        ctx.setVariable("qualityMultiplierMax", args.getQualityMultiplierMax());
        ctx.setVariable("complexityTrivialMax", args.getComplexityTrivialMax());
        ctx.setVariable("complexityModerateMax", args.getComplexityModerateMax());
        ctx.setVariable("complexityComplexMax", args.getComplexityComplexMax());
        ctx.setVariable("llmSummary", response.getSummary());
        ctx.setVariable("llmThinking", response.getThinking());

        populateEffortBreakdown(ctx, response, result.getPreComputedScores());
        populateQualityMultiplier(ctx, response);
        populateArchitectureBonus(ctx, response);
        populateRiskAssessment(ctx, response);
        populateStaticAnalysisReview(ctx, response);

        populateBlastRadius(ctx, response, request);
        populateCpdDetails(ctx, request);

        List<String> techTags = Collections.emptyList();
        if (Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getTechnical())) {
            techTags = response.getTags().getTechnical();
        }
        ctx.setVariable("technicalTags", techTags);

        List<String> funcTags = Collections.emptyList();
        if (Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getFunctional())) {
            funcTags = response.getTags().getFunctional();
        }
        ctx.setVariable("functionalTags", funcTags);

        List<DimensionView> dimScores = Collections.emptyList();
        if (Objects.nonNull(response.getQualityDimensions())) {
            dimScores = buildDimensionScores(response.getQualityDimensions());
        }
        ctx.setVariable("dimensionScores", dimScores);

        int criticalCount = 0;
        if (Objects.nonNull(response.getBugs()) && Objects.nonNull(response.getBugs().getBlocking())) {
            criticalCount = response.getBugs().getBlocking().size();
        }

        int majorCount = 0;
        if (Objects.nonNull(response.getBugs()) && Objects.nonNull(response.getBugs().getMajor())) {
            majorCount = response.getBugs().getMajor().size();
        }

        int minorCount = 0;
        if (Objects.nonNull(response.getBugs()) && Objects.nonNull(response.getBugs().getMinor())) {
            minorCount = response.getBugs().getMinor().size();
        }

        ctx.setVariable("criticalFindings", criticalCount);
        ctx.setVariable("majorFindings", majorCount);
        ctx.setVariable("minorFindings", minorCount);
        ctx.setVariable("findings", buildFindings(response));
        ctx.setVariable("recommendations", Optional.ofNullable(response.getSeniorReviewReasons()).orElse(Collections.emptyList()));
        ctx.setVariable("totalFilesChanged", request.getFileChanges().size());
        ctx.setVariable("javaFilesChanged", countJavaFiles(request));
        ctx.setVariable("totalAffectedCodeBlocks", request.getCodeBlockChanges().size());
        ctx.setVariable("confirmedErrorCount", countConfirmedErrors(response));
        ctx.setVariable("files", buildFileViews(request));
        ctx.setVariable("highRiskMethodCount", 0);
        ctx.setVariable("maxCognitiveComplexity", 0);
        ctx.setVariable("complexityAnalysis", Collections.emptyList());
        ctx.setVariable("coverageLevel", "UNKNOWN");
        ctx.setVariable("overallCoveragePercent", 0.0);
        ctx.setVariable("coveredLines", 0);
        ctx.setVariable("missedLines", 0);
        ctx.setVariable("coverageAnalysis", Collections.emptyList());
        ctx.setVariable("similarityMatches", Collections.emptyList());

        return templateEngine.process(TEMPLATE_COMMIT_ANALYSIS, ctx);
    }
    private void populateCpdDetails(Context ctx, LlmScoringRequest request) {
        DuplicationInfo dup = request.getDuplication();
        if (Objects.isNull(dup) || CollectionUtils.isEmpty(dup.getCloneDetails())) {
            ctx.setVariable("cpdDuplicationCount", 0);
            ctx.setVariable("cpdDuplication", null);
            ctx.setVariable("cpdEffectivePenalty", 0.0);
            return;
        }
        List<CloneDetail> clones = dup.getCloneDetails();
        ctx.setVariable("cpdDuplicationCount", clones.size());
        ctx.setVariable("cpdDuplication", dup);
        ctx.setVariable("cpdMaxClonesToShow", args.getMaxClonesToShow());

        long introducedCount = 0;
        long testOnlyCount = 0;
        long selfDupCount = 0;
        long crossFileCount = 0;
        double effectivePenalty = 0;
        for (CloneDetail clone : clones) {
            if (clone.isIntroducedInCommit()) {
                introducedCount++;
                effectivePenalty += clone.isAllTestCode() ? args.getTestCodePenaltyWeight() : 1.0;
            }
            if (clone.isAllTestCode()) {
                testOnlyCount++;
            }
            if (clone.isSelfDuplication()) {
                selfDupCount++;
            }
            if (clone.isCrossFile()) {
                crossFileCount++;
            }
        }
        ctx.setVariable("cpdEffectivePenalty", effectivePenalty);
        ctx.setVariable("cpdIntroducedCount", introducedCount);
        ctx.setVariable("cpdTestOnlyCount", testOnlyCount);
        ctx.setVariable("cpdSelfDupCount", selfDupCount);
        ctx.setVariable("cpdCrossFileCount", crossFileCount);
    }
    private void populateBlastRadius(Context ctx, LlmScoringResponse response, LlmScoringRequest request) {
        BlastRadiusAnalysis br = response.getBlastRadiusAnalysis();
        ctx.setVariable("blastRadius", br);
        int totalCallers = 0;
        int productionCallers = 0;
        int testCallers = 0;
        if (Objects.nonNull(request.getCodeBlockChanges())) {
            for (CodeBlockChange method : request.getCodeBlockChanges()) {
                if (Objects.nonNull(method.getCallers())) {
                    for (CallerInfo caller : method.getCallers()) {
                        totalCallers++;
                        if (caller.isTestCaller()) {
                            testCallers++;
                        } else {
                            productionCallers++;
                        }
                    }
                }
            }
        }
        ctx.setVariable("brTotalCallers", totalCallers);
        ctx.setVariable("brProductionCallers", productionCallers);
        ctx.setVariable("brTestCallers", testCallers);
        if (Objects.isNull(br)) {
            ctx.setVariable("signatureChanges", null);
            ctx.setVariable("changedSignatures", Collections.emptyList());
            LlmScoringResponse.RiskLevel fallbackRisk;
            if (totalCallers > args.getCallerThresholdHigh()) {
                fallbackRisk = LlmScoringResponse.RiskLevel.HIGH;
            } else if (totalCallers > args.getCallerThresholdModerate()) {
                fallbackRisk = LlmScoringResponse.RiskLevel.MODERATE;
            } else {
                fallbackRisk = LlmScoringResponse.RiskLevel.LOW;
            }
            ctx.setVariable("brRiskLevel", fallbackRisk);
            ctx.setVariable("brCriticalCallers", Collections.emptyList());
            ctx.setVariable("brExplanation", null);
            ctx.setVariable("brModuleType", null);
            ctx.setVariable("brExternalImpact", null);
            return;
        }
        ctx.setVariable("brRiskLevel", br.getRiskLevel());
        ctx.setVariable("brCriticalCallers", Optional.ofNullable(br.getCriticalCallers()).orElse(Collections.emptyList()));
        ctx.setVariable("brExplanation", br.getExplanation());
        ctx.setVariable("brModuleType", br.getModuleType());
        ctx.setVariable("brExternalImpact", br.getExternalImpactEstimate());
        SignatureChanges sig = br.getSignatureChanges();
        ctx.setVariable("signatureChanges", sig);
        if (Objects.nonNull(sig)) {
            ctx.setVariable("hasBreakingChanges", sig.isHasBreakingChanges());
            ctx.setVariable("changedSignatures", Optional.ofNullable(sig.getChangedSignatures()).orElse(Collections.emptyList()));
            ctx.setVariable("breakingChangeType", sig.getBreakingChangeType());
        } else {
            ctx.setVariable("hasBreakingChanges", false);
            ctx.setVariable("changedSignatures", Collections.emptyList());
            ctx.setVariable("breakingChangeType", null);
        }
    }
    private static void populateEffortBreakdown(Context ctx, LlmScoringResponse response, PreComputedScores preComputed) {
        EffortBreakdown breakdown = response.getEffortBreakdown();
        if (Objects.isNull(breakdown)) {
            ctx.setVariable("volumeScore", null);
            ctx.setVariable("complexityMultiplier", null);
            ctx.setVariable("baseEffortScore", 0.0);
            return;
        }
        VolumeScore volume = breakdown.getVolumeScore();
        ctx.setVariable("volumeScore", volume);
        if (Objects.nonNull(preComputed)) {
            ctx.setVariable("linesChanged", preComputed.getLinesChanged());
            ctx.setVariable("linesScore", preComputed.getLinesScore());
            ctx.setVariable("filesChanged", preComputed.getFilesChanged());
            ctx.setVariable("contentScore", preComputed.getContentScore());
            ctx.setVariable("filesScopeMultiplier", preComputed.getFilesScopeMultiplier());
            ctx.setVariable("fileDensity", preComputed.getFileDensity());
            ctx.setVariable("codeBlocksModified", preComputed.getCodeBlocksModified());
            ctx.setVariable("codeBlocksModifiedScore", preComputed.getCodeBlocksModifiedScore());
            ctx.setVariable("codeBlocksAdded", preComputed.getCodeBlocksAdded());
            ctx.setVariable("codeBlocksAddedScore", preComputed.getCodeBlocksAddedScore());
            ctx.setVariable("classesModified", preComputed.getClassesModified());
            ctx.setVariable("classesModifiedScore", preComputed.getClassesModifiedScore());
            ctx.setVariable("classesAdded", preComputed.getClassesAdded());
            ctx.setVariable("classesAddedScore", preComputed.getClassesAddedScore());
            ctx.setVariable("totalVolumeScore", preComputed.getVolumeScore());
            ctx.setVariable("sizeFactor", preComputed.getSizeFactor());
            ctx.setVariable("volModifyMult", preComputed.getModifyMult());
            ctx.setVariable("volAddMult", preComputed.getAddMult());
        } else if (Objects.nonNull(volume)) {
            ctx.setVariable("linesChanged", volume.getLinesChanged());
            ctx.setVariable("linesScore", volume.getLinesScore());
            ctx.setVariable("filesChanged", volume.getFilesChanged());
            ctx.setVariable("contentScore", volume.getContentScore());
            ctx.setVariable("filesScopeMultiplier", volume.getFilesScopeMultiplier());
            ctx.setVariable("fileDensity", volume.getFileDensity());
            ctx.setVariable("codeBlocksModified", volume.getCodeBlocksModified());
            ctx.setVariable("codeBlocksModifiedScore", volume.getCodeBlocksModifiedScore());
            ctx.setVariable("codeBlocksAdded", volume.getCodeBlocksAdded());
            ctx.setVariable("codeBlocksAddedScore", volume.getCodeBlocksAddedScore());
            ctx.setVariable("classesModified", volume.getClassesModified());
            ctx.setVariable("classesModifiedScore", volume.getClassesModifiedScore());
            ctx.setVariable("classesAdded", volume.getClassesAdded());
            ctx.setVariable("classesAddedScore", volume.getClassesAddedScore());
            ctx.setVariable("totalVolumeScore", volume.getTotalVolumeScore());
            ctx.setVariable("sizeFactor", volume.getSizeFactor());
            ctx.setVariable("volModifyMult", volume.getModifyMultiplier());
            ctx.setVariable("volAddMult", volume.getAddMultiplier());
        }
        ComplexityMultiplier complexity = breakdown.getComplexityMultiplier();
        ctx.setVariable("complexityMultiplier", complexity);
        if (Objects.nonNull(complexity)) {
            ctx.setVariable("avgModifyComplexity", complexity.getAvgModifyComplexity());
            ctx.setVariable("modifyMultiplier", complexity.getModifyMultiplier());
            ctx.setVariable("avgCreateComplexity", complexity.getAvgCreateComplexity());
            ctx.setVariable("createMultiplier", complexity.getCreateMultiplier());
            ctx.setVariable("combinedComplexityMultiplier", complexity.getCombinedMultiplier());
        }
        ctx.setVariable("baseEffortScore", breakdown.getBaseEffortScore());
    }
    private static void populateQualityMultiplier(Context ctx, LlmScoringResponse response) {
        QualityMultiplier qm = response.getQualityMultiplier();
        ctx.setVariable("qualityMultiplier", qm);
        if (Objects.isNull(qm)) {
            ctx.setVariable("cpdAnalysis", null);
            ctx.setVariable("staticAnalysisQuality", null);
            ctx.setVariable("coverageQuality", null);
            ctx.setVariable("architectureQuality", null);
            ctx.setVariable("qualityGateAnalysis", null);
            return;
        }
        ctx.setVariable("finalQualityMultiplier", qm.getFinalMultiplier());

        CpdAnalysis cpd = qm.getCpdAnalysis();
        ctx.setVariable("cpdAnalysis", cpd);
        double cpdImpact = 0.0;
        if (Objects.nonNull(cpd)) {
            cpdImpact = cpd.getImpact();
            ctx.setVariable("cpdDuplicationPercent", cpd.getDuplicationPercent());
        }
        ctx.setVariable("cpdImpact", cpdImpact);

        StaticAnalysisImpact sa = qm.getStaticAnalysis();
        ctx.setVariable("staticAnalysisQuality", sa);
        double saImpact = 0.0;
        if (Objects.nonNull(sa)) {
            saImpact = sa.getImpact();
            ctx.setVariable("pmdViolationsInChanges", sa.getPmdViolationsInChanges());
            ctx.setVariable("spotbugsIssuesInChanges", sa.getSpotbugsIssuesInChanges());
        }
        ctx.setVariable("staticAnalysisImpact", saImpact);

        CoverageAnalysis cov = qm.getCoverageAnalysis();
        ctx.setVariable("coverageQuality", cov);
        double covImpact = 0.0;
        if (Objects.nonNull(cov)) {
            covImpact = cov.getImpact();
            ctx.setVariable("qualityCoveragePercent", cov.getCoveragePercent());
        }
        ctx.setVariable("coverageImpact", covImpact);

        ArchitectureAnalysis arch = qm.getArchitectureAnalysis();
        ctx.setVariable("architectureQuality", arch);
        double archPenalty = 0.0;
        if (Objects.nonNull(arch)) {
            archPenalty = arch.getPenaltyImpact();
            ctx.setVariable("solidViolations", Optional.ofNullable(arch.getSolidViolations()).orElse(Collections.emptyList()));
            ctx.setVariable("architectureIssues", Optional.ofNullable(arch.getArchitectureIssues()).orElse(Collections.emptyList()));
        }
        ctx.setVariable("architecturePenalty", archPenalty);

        QualityGateAnalysis qg = qm.getQualityGateAnalysis();
        ctx.setVariable("qualityGateAnalysis", qg);
        double qgImpact = 0.0;
        if (Objects.nonNull(qg)) {
            qgImpact = qg.getImpact();
            ctx.setVariable("failedQualityGates", Optional.ofNullable(qg.getFailedGates()).orElse(Collections.emptyList()));
        }
        ctx.setVariable("qualityGateImpact", qgImpact);
    }
    private static void populateArchitectureBonus(Context ctx, LlmScoringResponse response) {
        ArchitectureEffortBonus bonus = response.getArchitectureEffortBonus();
        ctx.setVariable("architectureBonus", bonus);
        if (Objects.nonNull(bonus)) {
            ctx.setVariable("architectureImpactScore", bonus.getArchitectureImpactScore());
            ctx.setVariable("qualityFactor", bonus.getQualityFactor());
            ctx.setVariable("bonusBaseEffort", bonus.getBaseEffort());
            ctx.setVariable("bonusCalculation", bonus.getBonusCalculation());
            ctx.setVariable("bonusPoints", bonus.getBonusPoints());
        }
    }
    private static void populateRiskAssessment(Context ctx, LlmScoringResponse response) {
        RiskAssessment risk = response.getRiskAssessment();
        ctx.setVariable("riskAssessment", risk);
        if (Objects.nonNull(risk)) {
            ctx.setVariable("riskScore", risk.getRiskScore());
            ctx.setVariable("riskLevel", risk.getRiskLevel());
        }
    }
    private static void populateStaticAnalysisReview(Context ctx, LlmScoringResponse response) {
        StaticAnalysisReview review = response.getStaticAnalysisReview();
        ctx.setVariable("staticAnalysisReview", review);
        if (Objects.isNull(review)) {
            ctx.setVariable("pmdInChangedLines", Collections.emptyList());
            ctx.setVariable("pmdPreExisting", Collections.emptyList());
            ctx.setVariable("pmdFalsePositives", Collections.emptyList());
            ctx.setVariable("spotbugsInChangedLines", Collections.emptyList());
            ctx.setVariable("spotbugsPreExisting", Collections.emptyList());
            ctx.setVariable("spotbugsFalsePositives", Collections.emptyList());
            ctx.setVariable("totalNewIssues", 0);
            return;
        }
        ctx.setVariable("pmdInChangedLines", Optional.ofNullable(review.getPmdInChangedLines()).orElse(Collections.emptyList()));
        ctx.setVariable("pmdPreExisting", Optional.ofNullable(review.getPmdPreExisting()).orElse(Collections.emptyList()));
        ctx.setVariable("pmdFalsePositives", Optional.ofNullable(review.getPmdFalsePositives()).orElse(Collections.emptyList()));
        ctx.setVariable("spotbugsInChangedLines", Optional.ofNullable(review.getSpotbugsInChangedLines()).orElse(Collections.emptyList()));
        ctx.setVariable("spotbugsPreExisting", Optional.ofNullable(review.getSpotbugsPreExisting()).orElse(Collections.emptyList()));
        ctx.setVariable("spotbugsFalsePositives", Optional.ofNullable(review.getSpotbugsFalsePositives()).orElse(Collections.emptyList()));
        int pmdNewCount = CollectionUtils.size(review.getPmdInChangedLines());
        int spotbugsNewCount = CollectionUtils.size(review.getSpotbugsInChangedLines());
        ctx.setVariable("totalNewIssues", pmdNewCount + spotbugsNewCount);
    }
    private static List<DimensionView> buildDimensionScores(QualityDimensions dims) {
        List<DimensionView> toReturn = Lists.newArrayList();
        addDimension(toReturn, "Architecture Impact", dims.getArchitectureImpact(), "architecture");
        addDimension(toReturn, "Concurrency Risk", dims.getConcurrencyRisk(), "concurrency");
        addDimension(toReturn, "Integration Surface", dims.getIntegrationSurface(), "integration");
        addDimension(toReturn, "Data Integrity", dims.getDataIntegrity(), "data");
        addDimension(toReturn, "Security Sensitivity", dims.getSecuritySensitivity(), "security");
        addDimension(toReturn, "Scalability Impact", dims.getScalabilityImpact(), "scalability");
        addDimension(toReturn, "Observability", dims.getObservability(), "observability");
        addDimension(toReturn, "Resilience", dims.getResilience(), "resilience");
        addDimension(toReturn, "Performance", dims.getPerformance(), "performance");
        addDimension(toReturn, "Testing Coverage", dims.getTestingCoverage(), "testing");
        return toReturn;
    }
    private static void addDimension(List<DimensionView> views, String name, DimensionScore dim, String iconType) {
        if (Objects.nonNull(dim)) {
            boolean applicable = Objects.nonNull(dim.getScore());
            views.add(DimensionView.builder()
                    .name(name)
                    .iconType(iconType)
                    .score(applicable ? dim.getScore() : 0)
                    .rationale(dim.getRationale())
                    .applicable(applicable)
                    .build());
        }
    }
    private static List<FindingView> buildFindings(LlmScoringResponse response) {
        List<FindingView> toReturn = Lists.newArrayList();
        if (Objects.nonNull(response.getBugs())) {
            if (Objects.nonNull(response.getBugs().getBlocking())) {
                response.getBugs().getBlocking().forEach(bug -> toReturn.add(buildFindingView(bug, "BLOCKING")));
            }
            if (Objects.nonNull(response.getBugs().getMajor())) {
                response.getBugs().getMajor().forEach(bug -> toReturn.add(buildFindingView(bug, "MAJOR")));
            }
            if (Objects.nonNull(response.getBugs().getMinor())) {
                response.getBugs().getMinor().forEach(bug -> toReturn.add(buildFindingView(bug, "MINOR")));
            }
        }
        return toReturn;
    }
    private static FindingView buildFindingView(LlmScoringResponse.Bug bug, String severity) {
        return FindingView.builder()
                .title(bug.getTitle())
                .description(bug.getDescription())
                .file(bug.getFile())
                .line(bug.getLine())
                .severity(severity)
                .type(bug.getType())
                .confidence(bug.getConfidence())
                .source(bug.getSource())
                .suggestedFix(bug.getSuggestedFix())
                .suggestedFileFix(bug.getSuggestedFileFix())
                .suggestedBlockCode(bug.getSuggestedBlockCode())
                .build();
    }
    private static int countJavaFiles(LlmScoringRequest request) {
        if (Objects.nonNull(request.getFileChanges())) {
            return (int) request.getFileChanges().stream()
                    .filter(f -> Objects.nonNull(f.getPath()) && "java".equals(FilenameUtils.getExtension(f.getPath())))
                    .count();
        }
        return 0;
    }
    private static int countConfirmedErrors(LlmScoringResponse response) {
        if (Objects.nonNull(response.getBugs())) {
            return CollectionUtils.size(response.getBugs().getBlocking()) + CollectionUtils.size(response.getBugs().getMajor());
        }
        return 0;
    }
    private static List<FileView> buildFileViews(LlmScoringRequest request) {
        if (Objects.isNull(request.getFileChanges())) {
            return Collections.emptyList();
        }
        Map<String, List<CodeBlockChange>> methodsByFile = Maps.newHashMap();
        if (Objects.nonNull(request.getCodeBlockChanges())) {
            for (CodeBlockChange method : request.getCodeBlockChanges()) {
                if (Objects.nonNull(method.getFile())) {
                    methodsByFile.computeIfAbsent(method.getFile(), k -> Lists.newArrayList()).add(method);
                }
            }
        }
        List<FileView> toReturn = Lists.newArrayList();
        for (FileChange file : request.getFileChanges()) {
            List<CodeBlockView> symbols = Lists.newArrayList();
            List<CodeBlockChange> methods = methodsByFile.get(file.getPath());
            if (Objects.isNull(methods)) {
                for (Map.Entry<String, List<CodeBlockChange>> entry : methodsByFile.entrySet()) {
                    if (entry.getKey().endsWith(file.getPath()) || file.getPath().endsWith(entry.getKey())) {
                        methods = entry.getValue();
                        break;
                    }
                }
            }
            if (Objects.nonNull(methods)) {
                for (CodeBlockChange method : methods) {
                    symbols.add(buildCodeBlockView(method));
                }
            }
            toReturn.add(FileView.builder()
                    .path(file.getPath())
                    .language(file.getLanguage())
                    .changeType(file.getChangeType())
                    .diff(file.getDiff())
                    .affectedCodeBlocks(symbols)
                    .build());
        }
        return toReturn;
    }
    private static CodeBlockView buildCodeBlockView(CodeBlockChange method) {
        List<CallerView> callerViews = Lists.newArrayList();
        if (Objects.nonNull(method.getCallers())) {
            for (CallerInfo caller : method.getCallers()) {
                callerViews.add(CallerView.builder()
                        .name(caller.getCallerMethod())
                        .testCaller(caller.isTestCaller())
                        .file(caller.getFile())
                        .line(caller.getLine())
                        .build());
            }
        }
        return CodeBlockView.builder()
                .name(method.getName())
                .signature(method.getSignature())
                .constructor(method.isConstructor())
                .startLine(method.getStartLine())
                .endLine(method.getEndLine())
                .className(method.getClassName())
                .fullyQualifiedName(method.getFullyQualifiedName())
                .callerCount(method.getCallerCount())
                .callers(callerViews)
                .build();
    }
    @Value
    @Builder
    public static class DimensionView {
        String name;
        String iconType;
        int score;
        String rationale;
        boolean applicable;
    }
    @Value
    @Builder
    public static class FindingView {
        String title;
        String description;
        String file;
        Integer line;
        String severity;
        LlmScoringResponse.BugType type;
        LlmScoringResponse.Confidence confidence;
        LlmScoringResponse.BugSource source;
        String suggestedFix;
        String suggestedFileFix;
        String suggestedBlockCode;
    }
    @Value
    @Builder
    public static class FileView {
        String path;
        String language;
        LlmScoringRequest.FileChangeType changeType;
        String diff;
        String contentBefore;
        String contentAfter;
        @lombok.Builder.Default
        List<CodeBlockView> affectedCodeBlocks = Lists.newArrayList();
    }
    @Value
    @Builder
    public static class CodeBlockView {
        String name;
        String signature;
        boolean constructor;
        int startLine;
        int endLine;
        String className;
        String fullyQualifiedName;
        int callerCount;
        @lombok.Builder.Default
        List<CallerView> callers = Lists.newArrayList();
    }
    @Value
    @Builder
    public static class CallerView {
        String name;
        boolean testCaller;
        String file;
        int line;
    }
}
