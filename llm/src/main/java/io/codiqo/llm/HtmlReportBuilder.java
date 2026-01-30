package io.codiqo.llm;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CallerInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo.CloneDetail;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo.CloneFromExisting;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo.CloneLocation;
import io.codiqo.llm.schema.LlmScoringRequest.DuplicationInfo.NewCloneGroup;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringRequest.MethodChange;
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
    private static final String TEMPLATE_CPD_REPORT = "cpd-report";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final double SCORE_GAUGE_CIRCUMFERENCE = 314;
    private static final double SCORE_GAUGE_FACTOR = 3.14;
    private static final int MAX_SCORE_FOR_GAUGE = 100;
    private static final int MAX_SIGNATURE_LENGTH = 80;

    private final RunArgs args;
    private final TemplateEngine templateEngine;
    private final TemplateEngine textTemplateEngine;

    public HtmlReportBuilder(RunArgs args) {
        this.args = args;
        ClassLoaderTemplateResolver htmlResolver = new ClassLoaderTemplateResolver();
        htmlResolver.setPrefix("thymeleaf/html/");
        htmlResolver.setSuffix(".html");
        htmlResolver.setTemplateMode(TemplateMode.HTML);
        htmlResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        htmlResolver.setCacheable(true);
        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(htmlResolver);
        ClassLoaderTemplateResolver textResolver = new ClassLoaderTemplateResolver();
        textResolver.setPrefix("thymeleaf/templates/");
        textResolver.setSuffix(".txt");
        textResolver.setTemplateMode(TemplateMode.TEXT);
        textResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        textResolver.setCacheable(true);
        textTemplateEngine = new TemplateEngine();
        textTemplateEngine.setTemplateResolver(textResolver);
    }
    @Override
    public String buildReport(ScoringResult result, LlmScoringRequest request, ReportContext reportContext) {
        Context ctx = new Context(Locale.ENGLISH);

        LlmScoringResponse response = result.getResponse();
        double totalScore = response.getScore();
        double scoreGaugeOffset = SCORE_GAUGE_CIRCUMFERENCE - Math.min(MAX_SCORE_FOR_GAUGE, totalScore) * SCORE_GAUGE_FACTOR;

        ctx.setVariable("commitId", reportContext.getCommitId());
        ctx.setVariable("commitIdShort", reportContext.getCommitId());
        ctx.setVariable("author", reportContext.getAuthor());
        ctx.setVariable("authorEmail", reportContext.getAuthorEmail());
        ctx.setVariable("timestamp", reportContext.getTimestamp());
        ctx.setVariable("message", reportContext.getCommitMessage());
        ctx.setVariable("mergeCommit", reportContext.isMergeCommit());
        ctx.setVariable("gpgSignature", false);
        ctx.setVariable("repositoryName", reportContext.getRepositoryName());
        ctx.setVariable("generatedAt", LocalDateTime.now().format(TIMESTAMP_FORMAT));
        ctx.setVariable("branches", Optional.ofNullable(reportContext.getBranches()).orElse(Collections.emptyList()));
        ctx.setVariable("llmModel", reportContext.getLlmModel());
        ctx.setVariable("llmInputTokensFormatted", formatNumber(result.getPromptTokens()));
        ctx.setVariable("llmOutputTokensFormatted", formatNumber(result.getCompletionTokens()));
        ctx.setVariable("llmTotalTokensFormatted", formatNumber(result.getTotalTokens()));
        ctx.setVariable("llmTotalTokens", result.getTotalTokens());
        ctx.setVariable("llmDuration", formatDuration(reportContext.getAnalysisDuration()));
        ctx.setVariable("totalScore", (int) totalScore);
        ctx.setVariable("totalScoreFormatted", String.format("%.1f", totalScore));
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
        ctx.setVariable("scoreGaugeOffset", String.format("%.2f", scoreGaugeOffset));
        ctx.setVariable("changeClassification", response.getChangeClassification());
        ctx.setVariable("scoreCalculation", response.getScoreCalculation());
        ctx.setVariable("seniorReviewScore", response.getRequiresSeniorReview());
        populateEffortBreakdown(ctx, response);
        populateQualityMultiplier(ctx, response);
        populateArchitectureBonus(ctx, response);
        populateRiskAssessment(ctx, response);
        ctx.setVariable("llmSummary", response.getSummary());
        ctx.setVariable("llmThinking", response.getThinking());
        populateBlastRadius(ctx, response, request);
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
        List<FindingView> findings = buildFindings(response);
        ctx.setVariable("findings", findings);
        List<String> recommendations = Optional.ofNullable(response.getSeniorReviewReasons()).orElse(Collections.emptyList());
        ctx.setVariable("recommendations", recommendations);
        populateStaticAnalysisReview(ctx, response);
        int totalFiles = Objects.nonNull(request.getFileChanges()) ? request.getFileChanges().size() : 0;
        ctx.setVariable("totalFilesChanged", totalFiles);
        ctx.setVariable("javaFilesChanged", countJavaFiles(request));
        ctx.setVariable("totalAffectedSymbols", countAffectedSymbols(request));
        ctx.setVariable("confirmedErrorCount", countConfirmedErrors(response));
        ctx.setVariable("files", buildFileViews(request));
        populateCpdDetails(ctx, request);
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
    private static void populateEffortBreakdown(Context ctx, LlmScoringResponse response) {
        EffortBreakdown breakdown = response.getEffortBreakdown();
        if (Objects.isNull(breakdown)) {
            ctx.setVariable("volumeScore", null);
            ctx.setVariable("complexityMultiplier", null);
            ctx.setVariable("baseEffortScore", "0.00");
            return;
        }
        VolumeScore volume = breakdown.getVolumeScore();
        ctx.setVariable("volumeScore", volume);
        if (Objects.nonNull(volume)) {
            ctx.setVariable("linesChanged", volume.getLinesChanged());
            ctx.setVariable("linesScore", formatScore(volume.getLinesScore()));
            ctx.setVariable("methodsModified", volume.getMethodsModified());
            ctx.setVariable("methodsModifiedScore", formatScore(volume.getMethodsModifiedScore()));
            ctx.setVariable("methodsAdded", volume.getMethodsAdded());
            ctx.setVariable("methodsAddedScore", formatScore(volume.getMethodsAddedScore()));
            ctx.setVariable("classesModified", volume.getClassesModified());
            ctx.setVariable("classesModifiedScore", formatScore(volume.getClassesModifiedScore()));
            ctx.setVariable("classesAdded", volume.getClassesAdded());
            ctx.setVariable("classesAddedScore", formatScore(volume.getClassesAddedScore()));
            ctx.setVariable("totalVolumeScore", formatScore(volume.getTotalVolumeScore()));
            ctx.setVariable("sizeFactor", formatScore(volume.getSizeFactor()));
            ctx.setVariable("relativeAdj", formatScore(volume.getRelativeAdjustment()));
            ctx.setVariable("volModifyMult", formatScore(volume.getModifyMultiplier()));
            ctx.setVariable("volAddMult", formatScore(volume.getAddMultiplier()));
        }
        ComplexityMultiplier complexity = breakdown.getComplexityMultiplier();
        ctx.setVariable("complexityMultiplier", complexity);
        if (Objects.nonNull(complexity)) {
            ctx.setVariable("avgModifyComplexity", formatScore(complexity.getAvgModifyComplexity()));
            ctx.setVariable("modifyMultiplier", formatScore(complexity.getModifyMultiplier()));
            ctx.setVariable("avgCreateComplexity", formatScore(complexity.getAvgCreateComplexity()));
            ctx.setVariable("createMultiplier", formatScore(complexity.getCreateMultiplier()));
            ctx.setVariable("combinedComplexityMultiplier", formatScore(complexity.getCombinedMultiplier()));
        }
        ctx.setVariable("baseEffortScore", formatScore(breakdown.getBaseEffortScore()));
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
            ctx.setVariable("qualityMultiplierCalculation", null);
            return;
        }
        ctx.setVariable("finalQualityMultiplier", formatScore(qm.getFinalMultiplier()));
        ctx.setVariable("qualityMultiplierPositive", qm.getFinalMultiplier() >= 1.0);
        CpdAnalysis cpd = qm.getCpdAnalysis();
        ctx.setVariable("cpdAnalysis", cpd);
        double cpdImpactValue = 0.0;
        if (Objects.nonNull(cpd)) {
            cpdImpactValue = cpd.getImpact();
            ctx.setVariable("cpdDuplicationPercent", formatScore(cpd.getDuplicationPercent()));
            ctx.setVariable("cpdImpact", formatImpact(cpd.getImpact()));
        }
        StaticAnalysisImpact sa = qm.getStaticAnalysis();
        ctx.setVariable("staticAnalysisQuality", sa);
        double saImpactValue = 0.0;
        if (Objects.nonNull(sa)) {
            saImpactValue = sa.getImpact();
            ctx.setVariable("pmdViolationsInChanges", sa.getPmdViolationsInChanges());
            ctx.setVariable("spotbugsIssuesInChanges", sa.getSpotbugsIssuesInChanges());
            ctx.setVariable("staticAnalysisImpact", formatImpact(sa.getImpact()));
            ctx.setVariable("staticAnalysisClean", sa.getImpact() > 0);
        }
        CoverageAnalysis cov = qm.getCoverageAnalysis();
        ctx.setVariable("coverageQuality", cov);
        double covImpactValue = 0.0;
        if (Objects.nonNull(cov)) {
            covImpactValue = cov.getImpact();
            ctx.setVariable("qualityCoveragePercent", formatScore(cov.getCoveragePercent()));
            ctx.setVariable("coverageImpact", formatImpact(cov.getImpact()));
        }
        ArchitectureAnalysis arch = qm.getArchitectureAnalysis();
        ctx.setVariable("architectureQuality", arch);
        double archImpactValue = 0.0;
        if (Objects.nonNull(arch)) {
            archImpactValue = arch.getPenaltyImpact();
            ctx.setVariable("solidViolations", Optional.ofNullable(arch.getSolidViolations()).orElse(Collections.emptyList()));
            ctx.setVariable("architectureIssues", Optional.ofNullable(arch.getArchitectureIssues()).orElse(Collections.emptyList()));
            ctx.setVariable("architecturePenalty", formatImpact(arch.getPenaltyImpact()));
        }
        QualityGateAnalysis qg = qm.getQualityGateAnalysis();
        ctx.setVariable("qualityGateAnalysis", qg);
        double qgImpactValue = 0.0;
        if (Objects.nonNull(qg)) {
            qgImpactValue = qg.getImpact();
            ctx.setVariable("failedQualityGates", Optional.ofNullable(qg.getFailedGates()).orElse(Collections.emptyList()));
            ctx.setVariable("qualityGateImpact", formatImpact(qg.getImpact()));
        }
        String calculation = buildQualityMultiplierCalculation(cpdImpactValue, saImpactValue, covImpactValue, archImpactValue, qgImpactValue, qm.getFinalMultiplier());
        ctx.setVariable("qualityMultiplierCalculation", calculation);
    }
    private static String buildQualityMultiplierCalculation(double cpd, double sa, double cov, double arch, double qg, double finalMult) {
        StringBuilder sb = new StringBuilder();
        sb.append("1.0");
        if (cpd != 0) {
            sb.append(cpd > 0 ? " + " : " ").append(String.format("%.2f", cpd)).append(" (CPD)");
        }
        if (sa != 0) {
            sb.append(sa > 0 ? " + " : " ").append(String.format("%.2f", sa)).append(" (SA)");
        }
        if (cov != 0) {
            sb.append(cov > 0 ? " + " : " ").append(String.format("%.2f", cov)).append(" (Cov)");
        }
        if (arch != 0) {
            sb.append(arch > 0 ? " + " : " ").append(String.format("%.2f", arch)).append(" (Arch)");
        }
        if (qg != 0) {
            sb.append(qg > 0 ? " + " : " ").append(String.format("%.2f", qg)).append(" (QG)");
        }
        sb.append(" = ").append(String.format("%.2f", finalMult));
        return sb.toString();
    }
    private static void populateArchitectureBonus(Context ctx, LlmScoringResponse response) {
        ArchitectureEffortBonus bonus = response.getArchitectureEffortBonus();
        ctx.setVariable("architectureBonus", bonus);
        if (Objects.nonNull(bonus)) {
            ctx.setVariable("architectureImpactScore", bonus.getArchitectureImpactScore());
            ctx.setVariable("qualityFactor", formatScore(bonus.getQualityFactor()));
            ctx.setVariable("bonusBaseEffort", formatScore(bonus.getBaseEffort()));
            ctx.setVariable("bonusCalculation", bonus.getBonusCalculation());
            ctx.setVariable("bonusPoints", formatScore(bonus.getBonusPoints()));
        }
    }
    private static void populateRiskAssessment(Context ctx, LlmScoringResponse response) {
        RiskAssessment risk = response.getRiskAssessment();
        ctx.setVariable("riskAssessment", risk);
        if (Objects.nonNull(risk)) {
            ctx.setVariable("riskScore", risk.getRiskScore());
            String riskLevel = null;
            if (Objects.nonNull(risk.getRiskLevel())) {
                riskLevel = risk.getRiskLevel().name().toLowerCase();
            }
            ctx.setVariable("riskLevel", riskLevel);
        }
    }
    private void populateCpdDetails(Context ctx, LlmScoringRequest request) {
        DuplicationInfo dup = request.getDuplication();
        if (Objects.isNull(dup) || CollectionUtils.isEmpty(dup.getCloneDetails())) {
            ctx.setVariable("cpdDuplicationCount", 0);
            ctx.setVariable("cpdDuplications", Collections.emptyList());
            ctx.setVariable("cpdMarkdownReport", "");
            return;
        }
        List<CloneDetail> clones = dup.getCloneDetails();
        ctx.setVariable("cpdDuplicationCount", clones.size());
        ctx.setVariable("cpdDuplications", clones);
        long introducedCount = clones.stream().filter(CloneDetail::isIntroducedInCommit).count();
        long testOnlyCount = clones.stream().filter(CloneDetail::isAllTestCode).count();
        long selfDupCount = clones.stream().filter(CloneDetail::isSelfDuplication).count();
        long crossFileCount = clones.stream().filter(CloneDetail::isCrossFile).count();
        double effectivePenalty = 0;
        for (CloneDetail clone : clones) {
            if (clone.isIntroducedInCommit()) {
                effectivePenalty += clone.isAllTestCode() ? args.getTestCodePenaltyWeight() : 1.0;
            }
        }
        String penaltyCategory;
        String penaltyImpact;
        String penaltyExplanation;
        if (effectivePenalty <= 1) {
            penaltyCategory = "✅ Excellent";
            penaltyImpact = "+5% score bonus";
            penaltyExplanation = "Minimal or no copy-pasting. Great job!";
        } else if (effectivePenalty <= 3) {
            penaltyCategory = "✅ Good";
            penaltyImpact = "No change";
            penaltyExplanation = "Some duplication, but acceptable.";
        } else if (effectivePenalty <= 6) {
            penaltyCategory = "⚠️ Needs Attention";
            penaltyImpact = "-10% score penalty";
            penaltyExplanation = "Consider refactoring duplicated code.";
        } else if (effectivePenalty <= 10) {
            penaltyCategory = "🔴 Too Much";
            penaltyImpact = "-20% score penalty";
            penaltyExplanation = "Significant copy-pasting detected. Please refactor.";
        } else {
            penaltyCategory = "🔴 Excessive";
            penaltyImpact = "-30% score penalty";
            penaltyExplanation = "Excessive duplication. Refactoring required.";
        }
        List<CpdCloneView> introducedClones = Lists.newArrayList();
        for (CloneDetail clone : clones) {
            if (clone.isIntroducedInCommit()) {
                introducedClones.add(buildCpdCloneView(clone));
            }
        }
        List<CpdExistingView> clonesFromExisting = Lists.newArrayList();
        if (CollectionUtils.isNotEmpty(dup.getClonesFromExisting())) {
            for (CloneFromExisting existing : dup.getClonesFromExisting()) {
                List<String> sourceLocations = Lists.newArrayList();
                for (String src : existing.getSourceSignatures()) {
                    sourceLocations.add(formatSignatureAsLocation(src));
                }
                clonesFromExisting.add(CpdExistingView.builder()
                        .affectedLocation(formatSignatureAsLocation(existing.getAffectedSignature()))
                        .sourceLocations(sourceLocations)
                        .build());
            }
        }
        List<CpdNewGroupView> newCloneGroups = Lists.newArrayList();
        if (CollectionUtils.isNotEmpty(dup.getNewClones())) {
            for (NewCloneGroup group : dup.getNewClones()) {
                List<String> memberLocations = Lists.newArrayList();
                for (String sig : group.getMemberSignatures()) {
                    memberLocations.add(formatSignatureAsLocation(sig));
                }
                newCloneGroups.add(CpdNewGroupView.builder()
                        .memberCount(group.getMemberSignatures().size())
                        .memberLocations(memberLocations)
                        .build());
            }
        }
        int maxToShow = args.getMaxClonesToShow();
        List<CpdCloneView> detailedClones = Lists.newArrayList();
        for (int i = 0; i < Math.min(clones.size(), maxToShow); i++) {
            detailedClones.add(buildCpdCloneView(clones.get(i)));
        }
        int remainingCloneCount = Math.max(0, clones.size() - maxToShow);
        Context cpdCtx = new Context(Locale.ENGLISH);
        cpdCtx.setVariable("totalClones", clones.size());
        cpdCtx.setVariable("totalDuplicatedLines", dup.getTotalDuplicatedLines());
        cpdCtx.setVariable("duplicatedPercentage", dup.getDuplicatedPercentage());
        cpdCtx.setVariable("introducedCount", introducedCount);
        cpdCtx.setVariable("preExistingCount", clones.size() - introducedCount);
        cpdCtx.setVariable("testOnlyCount", testOnlyCount);
        cpdCtx.setVariable("productionCount", clones.size() - testOnlyCount);
        cpdCtx.setVariable("selfDupCount", selfDupCount);
        cpdCtx.setVariable("crossFileCount", crossFileCount);
        cpdCtx.setVariable("penaltyCategory", penaltyCategory);
        cpdCtx.setVariable("penaltyImpact", penaltyImpact);
        cpdCtx.setVariable("penaltyExplanation", penaltyExplanation);
        cpdCtx.setVariable("introducedClones", introducedClones);
        cpdCtx.setVariable("clonesFromExisting", clonesFromExisting);
        cpdCtx.setVariable("newCloneGroups", newCloneGroups);
        cpdCtx.setVariable("detailedClones", detailedClones);
        cpdCtx.setVariable("remainingCloneCount", remainingCloneCount);
        ctx.setVariable("cpdMarkdownReport", textTemplateEngine.process(TEMPLATE_CPD_REPORT, cpdCtx));
    }
    private CpdCloneView buildCpdCloneView(CloneDetail clone) {
        List<CpdLocationView> locations = Lists.newArrayList();
        String firstSourceSlice = null;
        for (CloneLocation loc : clone.getLocations()) {
            String fileName = Objects.nonNull(loc.getFile())
                    ? loc.getFile().substring(Math.max(0, loc.getFile().lastIndexOf('/') + 1))
                    : "unknown";
            String methodInfo = Objects.nonNull(loc.getMethodSignature())
                    ? " in `" + truncateSignature(loc.getMethodSignature()) + "`"
                    : "";
            String locStatus = loc.isIntroducedInCommit()
                    ? "**INTRODUCED** (" + loc.getLinesOverlappingDiff() + " lines overlap with diff)"
                    : "pre-existing";
            int overlapPercent = (int) (loc.getLinesOverlappingDiff() * 100.0 / Math.max(clone.getLineCount(), 1));
            locations.add(CpdLocationView.builder()
                    .fileName(fileName)
                    .testCode(loc.isTestCode())
                    .introduced(loc.isIntroducedInCommit())
                    .startLine(loc.getStartLine())
                    .endLine(loc.getEndLine())
                    .linesOverlapping(loc.getLinesOverlappingDiff())
                    .overlapPercent(overlapPercent)
                    .methodInfo(methodInfo)
                    .locStatus(locStatus)
                    .build());
            if (Objects.isNull(firstSourceSlice) && Objects.nonNull(loc.getSourceSlice()) && !loc.getSourceSlice().isEmpty()) {
                firstSourceSlice = loc.getSourceSlice();
            }
        }
        String truncatedSource = truncateSource(firstSourceSlice);
        return CpdCloneView.builder()
                .introduced(clone.isIntroducedInCommit())
                .testCode(clone.isAllTestCode())
                .selfDuplication(clone.isSelfDuplication())
                .lineCount(clone.getLineCount())
                .locations(locations)
                .truncatedSource(truncatedSource)
                .build();
    }
    private String truncateSource(String source) {
        if (Objects.isNull(source) || source.isEmpty()) {
            return "";
        }
        String[] lines = source.split("\n");
        if (lines.length > args.getMaxSourceLines()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.getTruncateSourceLines(); i++) {
                sb.append(lines[i]).append("\n");
            }
            sb.append("// ... truncated (").append(lines.length - args.getTruncateSourceLines()).append(" more lines) ...\n");
            return sb.toString();
        }
        if (source.endsWith("\n")) {
            return source;
        }
        return source + "\n";
    }
    private static String formatSignatureAsLocation(String signature) {
        if (Objects.isNull(signature) || signature.isEmpty()) {
            return "unknown";
        }
        String result = signature;
        int parenIdx = result.indexOf('(');
        if (parenIdx > 0) {
            result = result.substring(0, parenIdx);
        }
        result = result.replace('/', '.');
        String[] parts = result.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1] + "()";
        } else if (parts.length == 1) {
            return parts[0] + "()";
        }
        return result;
    }
    private static String truncateSignature(String signature) {
        if (Objects.isNull(signature)) {
            return "unknown";
        }
        String result = signature;
        int lastSlash = result.lastIndexOf('/');
        if (lastSlash > 0) {
            result = result.substring(lastSlash + 1);
        }
        if (result.length() > MAX_SIGNATURE_LENGTH) {
            return result.substring(0, MAX_SIGNATURE_LENGTH - 3) + "...";
        }
        return result;
    }
    private void populateBlastRadius(Context ctx, LlmScoringResponse response, LlmScoringRequest request) {
        BlastRadiusAnalysis br = response.getBlastRadiusAnalysis();
        ctx.setVariable("blastRadius", br);
        int totalCallers = 0;
        int productionCallers = 0;
        int testCallers = 0;
        if (Objects.nonNull(request.getMethodChanges())) {
            for (MethodChange method : request.getMethodChanges()) {
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
            ctx.setVariable("brRiskLevel", fallbackRisk.name().toLowerCase());
            ctx.setVariable("brCriticalCallers", Collections.emptyList());
            ctx.setVariable("brExplanation", null);
            ctx.setVariable("brModuleType", null);
            ctx.setVariable("brExternalImpact", null);
            return;
        }
        String brRiskLevel = Objects.nonNull(br.getRiskLevel()) ? br.getRiskLevel().name().toLowerCase() : null;
        ctx.setVariable("brRiskLevel", brRiskLevel);
        ctx.setVariable("brCriticalCallers", Optional.ofNullable(br.getCriticalCallers()).orElse(Collections.emptyList()));
        ctx.setVariable("brExplanation", br.getExplanation());
        String brModuleType = Objects.nonNull(br.getModuleType()) ? br.getModuleType().name().toLowerCase() : null;
        ctx.setVariable("brModuleType", brModuleType);
        String brExternalImpact = Objects.nonNull(br.getExternalImpactEstimate()) ? br.getExternalImpactEstimate().name().toLowerCase() : null;
        ctx.setVariable("brExternalImpact", brExternalImpact);
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
    private static String formatScore(double value) {
        return String.format("%.2f", value);
    }
    private static String formatImpact(double value) {
        if (value > 0) {
            return String.format("+%.2f", value);
        }
        if (value < 0) {
            return String.format("%.2f", value);
        }
        return "0.00";
    }
    private static String formatNumber(int number) {
        return String.format("%,d", number);
    }
    private static String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes % 60, seconds % 60, millis % 1000);
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
            int score = applicable ? dim.getScore() : 0;
            views.add(DimensionView.builder()
                    .name(name)
                    .iconType(iconType)
                    .score(score)
                    .rationale(dim.getRationale())
                    .applicable(applicable)
                    .scoreDisplay(applicable ? String.valueOf(score) : "N/A")
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
                .file(sanitizePath(bug.getFile()))
                .lineHint(Objects.nonNull(bug.getLine()) ? "line " + bug.getLine() : null)
                .severity(severity)
                .type(Objects.nonNull(bug.getType()) ? bug.getType().name() : null)
                .confidence(Objects.nonNull(bug.getConfidence()) ? bug.getConfidence().name().toLowerCase() : null)
                .source(Objects.nonNull(bug.getSource()) ? bug.getSource().name() : null)
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
    private static int countAffectedSymbols(LlmScoringRequest request) {
        if (Objects.nonNull(request.getMethodChanges())) {
            return request.getMethodChanges().size();
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
        Map<String, List<MethodChange>> methodsByFile = Maps.newHashMap();
        if (Objects.nonNull(request.getMethodChanges())) {
            for (MethodChange method : request.getMethodChanges()) {
                if (Objects.nonNull(method.getFile())) {
                    methodsByFile.computeIfAbsent(method.getFile(), k -> Lists.newArrayList()).add(method);
                }
            }
        }
        List<FileView> toReturn = Lists.newArrayList();
        for (FileChange file : request.getFileChanges()) {
            String originalPath = file.getPath();
            String path = sanitizePath(originalPath);
            String fileName;
            if (Objects.isNull(originalPath) || originalPath.isEmpty()) {
                fileName = "(unknown)";
            } else if (path.contains("/")) {
                fileName = path.substring(path.lastIndexOf('/') + 1);
            } else {
                fileName = path;
            }
            String fileType = Objects.nonNull(file.getLanguage()) ? file.getLanguage() : getFileType(path);
            String changeType = normalizeChangeType(file.getChangeType());
            List<SymbolView> symbols = Lists.newArrayList();
            List<MethodChange> methods = methodsByFile.get(originalPath);
            if (Objects.isNull(methods)) {
                for (Map.Entry<String, List<MethodChange>> entry : methodsByFile.entrySet()) {
                    if (entry.getKey().endsWith(path) || originalPath.endsWith(entry.getKey())) {
                        methods = entry.getValue();
                        break;
                    }
                }
            }
            if (Objects.nonNull(methods)) {
                for (MethodChange method : methods) {
                    symbols.add(buildSymbolView(method));
                }
            }
            toReturn.add(FileView.builder()
                    .path(path)
                    .fileName(fileName)
                    .fileType(fileType)
                    .changeType(changeType)
                    .diff(file.getDiff())
                    .affectedSymbols(symbols)
                    .build());
        }
        return toReturn;
    }
    private static SymbolView buildSymbolView(MethodChange method) {
        List<CallerView> callerViews = Lists.newArrayList();
        if (Objects.nonNull(method.getCallers())) {
            for (CallerInfo caller : method.getCallers()) {
                String callerFile = caller.getFile();
                if (Objects.nonNull(callerFile) && callerFile.contains("/")) {
                    callerFile = callerFile.substring(callerFile.lastIndexOf('/') + 1);
                }
                callerViews.add(CallerView.builder()
                        .name(caller.getCallerMethod())
                        .detail(caller.isTestCaller() ? "(test)" : "(prod)")
                        .file(callerFile)
                        .line(caller.getLine())
                        .build());
            }
        }
        String lineRange = method.getStartLine() + "-" + method.getEndLine();
        String containerName = method.getClassName();
        if (Objects.isNull(containerName) && Objects.nonNull(method.getFullyQualifiedName())) {
            String fqn = method.getFullyQualifiedName();
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot > 0) {
                String beforeMethod = fqn.substring(0, lastDot);
                int classNameStart = Math.max(beforeMethod.lastIndexOf('.'), beforeMethod.lastIndexOf('/'));
                containerName = classNameStart >= 0 ? beforeMethod.substring(classNameStart + 1) : beforeMethod;
            }
        }
        return SymbolView.builder()
                .name(method.getMethodName())
                .signature(method.getSignature())
                .constructor(method.isConstructor())
                .lineRange(lineRange)
                .containerName(containerName)
                .callerCount(method.getCallerCount())
                .callers(callerViews)
                .build();
    }
    private static String sanitizePath(String path) {
        if (Objects.isNull(path) || path.isEmpty()) {
            return "(unknown file)";
        }
        if (path.startsWith("/")) {
            String[] markers = { "/src/main/java/", "/src/test/java/", "/src/main/resources/", "/src/test/resources/" };
            for (String marker : markers) {
                int idx = path.indexOf(marker);
                if (idx >= 0) {
                    return path.substring(idx + 1);
                }
            }
            String[] moduleMarkers = { "/pom.xml", "/build.gradle" };
            for (String marker : moduleMarkers) {
                if (path.endsWith(marker.substring(1))) {
                    int lastSlash = path.lastIndexOf('/');
                    if (lastSlash > 0) {
                        int secondLastSlash = path.lastIndexOf('/', lastSlash - 1);
                        if (secondLastSlash >= 0) {
                            return path.substring(secondLastSlash + 1);
                        }
                    }
                    return path.substring(lastSlash + 1);
                }
            }
            String[] rootPatterns = { "/dev/", "/projects/", "/workspace/", "/repo/", "/git/" };
            for (String pattern : rootPatterns) {
                int idx = path.indexOf(pattern);
                if (idx >= 0) {
                    String afterPattern = path.substring(idx + pattern.length());
                    int nextSlash = afterPattern.indexOf('/');
                    if (nextSlash >= 0) {
                        return afterPattern.substring(nextSlash + 1);
                    }
                }
            }
            int lastSlash = path.lastIndexOf('/');
            String result = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            while (result.startsWith("/")) {
                result = result.substring(1);
            }
            return result;
        }
        return path;
    }
    private static String normalizeChangeType(LlmScoringRequest.FileChangeType changeType) {
        if (Objects.isNull(changeType)) {
            return "MODIFY";
        }
        switch (changeType) {
            case ADDED:
                return "NEW";
            case MODIFIED:
                return "MODIFY";
            case DELETED:
                return "DELETE";
            case RENAMED:
                return "RENAME";
            default:
                throw new IllegalArgumentException("Unknown change type: " + changeType);
        }
    }
    private static String getFileType(String path) {
        if (Objects.isNull(path)) {
            return "unknown";
        }
        String extension = FilenameUtils.getExtension(path);
        switch (extension) {
            case "java":
            case "kt":
            case "scala":
            case "py":
            case "go":
            case "rs":
            case "ts":
            case "js":
            case "xml":
            case "json":
            case "sql":
            case "properties":
            case "gradle":
                return extension;
            case "yaml":
            case "yml":
                return "yaml";
            case "tsx":
                return "ts";
            case "jsx":
                return "js";
            case "kts":
                return "kt";
            default:
                return "unknown";
        }
    }
    @Value
    @Builder
    public static class DimensionView {
        String name;
        String iconType;
        int score;
        String rationale;
        boolean applicable;
        String scoreDisplay;
    }
    @Value
    @Builder
    public static class FindingView {
        String title;
        String description;
        String file;
        String lineHint;
        String severity;
        String type;
        String confidence;
        String source;
        String suggestedFix;
        String suggestedFileFix;
        String suggestedBlockCode;
    }
    @Value
    @Builder
    public static class FileView {
        String path;
        String fileName;
        String fileType;
        String changeType;
        String diff;
        String contentBefore;
        String contentAfter;
        @lombok.Builder.Default
        List<SymbolView> affectedSymbols = Lists.newArrayList();
    }
    @Value
    @Builder
    public static class SymbolView {
        String name;
        String signature;
        boolean constructor;
        String lineRange;
        String containerName;
        int callerCount;
        @lombok.Builder.Default
        List<CallerView> callers = Lists.newArrayList();
    }
    @Value
    @Builder
    public static class CallerView {
        String name;
        String detail;
        String file;
        int line;
    }
    @Value
    @Builder
    static class CpdCloneView {
        boolean introduced;
        boolean testCode;
        boolean selfDuplication;
        int lineCount;
        @lombok.Builder.Default
        List<CpdLocationView> locations = Lists.newArrayList();
        String truncatedSource;
    }
    @Value
    @Builder
    static class CpdLocationView {
        String fileName;
        boolean testCode;
        boolean introduced;
        int startLine;
        int endLine;
        int linesOverlapping;
        int overlapPercent;
        String methodInfo;
        String locStatus;
    }
    @Value
    @Builder
    static class CpdExistingView {
        String affectedLocation;
        @lombok.Builder.Default
        List<String> sourceLocations = Lists.newArrayList();
    }
    @Value
    @Builder
    static class CpdNewGroupView {
        int memberCount;
        @lombok.Builder.Default
        List<String> memberLocations = Lists.newArrayList();
    }
}
