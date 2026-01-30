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

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.BlastRadiusAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import lombok.Builder;
import lombok.Value;

public class HtmlReportBuilder implements ReportBuilder {
    private static final String TEMPLATE_COMMIT_ANALYSIS = "commit-analysis";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int SHORT_COMMIT_ID_LENGTH = 7;
    private static final double SCORE_GAUGE_CIRCUMFERENCE = 314;
    private static final double SCORE_GAUGE_FACTOR = 3.14;
    private static final int MAX_SCORE_FOR_GAUGE = 100;
    private static final int SCORE_THRESHOLD_HUGE = 150;
    private static final int SCORE_THRESHOLD_LARGE = 90;
    private static final int SCORE_THRESHOLD_MEDIUM = 50;
    private static final int SCORE_THRESHOLD_SMALL = 20;
    private static final int DIMENSION_SCORE_CRITICAL = 8;
    private static final int DIMENSION_SCORE_MAJOR = 6;
    private static final int DIMENSION_SCORE_MODERATE = 4;
    private static final int CALLER_THRESHOLD_HIGH = 10;
    private static final int CALLER_THRESHOLD_MODERATE = 5;
    private static final int MAX_CLONES_TO_SHOW = 10;
    private static final int MAX_SOURCE_LINES = 30;
    private static final int TRUNCATE_SOURCE_LINES = 25;
    private static final int MAX_SIGNATURE_LENGTH = 80;
    private static final double TEST_CODE_PENALTY_WEIGHT = 0.2;
    private final TemplateEngine templateEngine;
    public HtmlReportBuilder() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/html/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);
    }
    @Override
    public String buildReport(ScoringResult result, LlmScoringRequest request, ReportContext reportContext) {
        Context ctx = new Context(Locale.ENGLISH);
        LlmScoringResponse response = result.getResponse();
        ctx.setVariable("commitId", reportContext.getCommitId());
        ctx.setVariable("commitIdShort", Objects.nonNull(reportContext.getCommitId())
                ? reportContext.getCommitId().substring(0, Math.min(SHORT_COMMIT_ID_LENGTH, reportContext.getCommitId().length()))
                : "unknown");
        ctx.setVariable("author", reportContext.getAuthor());
        ctx.setVariable("authorEmail", reportContext.getAuthorEmail());
        ctx.setVariable("timestamp", reportContext.getTimestamp());
        ctx.setVariable("message", reportContext.getCommitMessage());
        ctx.setVariable("mergeCommit", reportContext.isMergeCommit());
        ctx.setVariable("gpgSignature", false);
        ctx.setVariable("repositoryName", reportContext.getRepositoryName());
        ctx.setVariable("generatedAt", LocalDateTime.now().format(TIMESTAMP_FORMAT));
        ctx.setVariable("branches", Objects.nonNull(reportContext.getBranches()) ? reportContext.getBranches() : Collections.emptyList());
        ctx.setVariable("llmModel", reportContext.getLlmModel());
        ctx.setVariable("llmInputTokensFormatted", formatNumber(result.getPromptTokens()));
        ctx.setVariable("llmOutputTokensFormatted", formatNumber(result.getCompletionTokens()));
        ctx.setVariable("llmTotalTokensFormatted", formatNumber(result.getTotalTokens()));
        ctx.setVariable("llmTotalTokens", result.getTotalTokens());
        ctx.setVariable("llmDuration", Objects.nonNull(reportContext.getAnalysisDuration())
                ? formatDuration(reportContext.getAnalysisDuration())
                : null);
        double totalScore = response.getScore();
        ctx.setVariable("totalScore", (int) totalScore);
        ctx.setVariable("totalScoreFormatted", String.format("%.1f", totalScore));
        ctx.setVariable("dangerClass", getDangerClass(totalScore));
        ctx.setVariable("dangerLevel", getDangerLevel(totalScore));
        double scoreGaugeOffset = SCORE_GAUGE_CIRCUMFERENCE - Math.min(MAX_SCORE_FOR_GAUGE, totalScore) * SCORE_GAUGE_FACTOR;
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
        List<String> techTags = Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getTechnical())
                ? response.getTags().getTechnical()
                : Collections.emptyList();
        List<String> funcTags = Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getFunctional())
                ? response.getTags().getFunctional()
                : Collections.emptyList();
        ctx.setVariable("technicalTags", techTags);
        ctx.setVariable("functionalTags", funcTags);
        List<DimensionView> dimScores = Objects.nonNull(response.getQualityDimensions())
                ? buildDimensionScores(response.getQualityDimensions())
                : Collections.emptyList();
        ctx.setVariable("dimensionScores", dimScores);
        int criticalCount = Objects.nonNull(response.getBugs()) && Objects.nonNull(response.getBugs().getBlocking())
                ? response.getBugs().getBlocking().size()
                : 0;
        int majorCount = Objects.nonNull(response.getBugs()) && Objects.nonNull(response.getBugs().getMajor())
                ? response.getBugs().getMajor().size()
                : 0;
        int minorCount = Objects.nonNull(response.getBugs()) && Objects.nonNull(response.getBugs().getMinor())
                ? response.getBugs().getMinor().size()
                : 0;
        ctx.setVariable("criticalFindings", criticalCount);
        ctx.setVariable("majorFindings", majorCount);
        ctx.setVariable("minorFindings", minorCount);
        List<FindingView> findings = buildFindings(response);
        ctx.setVariable("findings", findings);
        List<String> recommendations = Objects.nonNull(response.getSeniorReviewReasons())
                ? response.getSeniorReviewReasons()
                : Collections.emptyList();
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
        var breakdown = response.getEffortBreakdown();
        if (Objects.isNull(breakdown)) {
            ctx.setVariable("volumeScore", null);
            ctx.setVariable("complexityMultiplier", null);
            ctx.setVariable("baseEffortScore", "0.00");
            return;
        }
        var volume = breakdown.getVolumeScore();
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
        var complexity = breakdown.getComplexityMultiplier();
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
        var qm = response.getQualityMultiplier();
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
        var cpd = qm.getCpdAnalysis();
        ctx.setVariable("cpdAnalysis", cpd);
        double cpdImpactValue = 0.0;
        if (Objects.nonNull(cpd)) {
            cpdImpactValue = cpd.getImpact();
            ctx.setVariable("cpdDuplicationPercent", formatScore(cpd.getDuplicationPercent()));
            ctx.setVariable("cpdImpact", formatImpact(cpd.getImpact()));
        }
        var sa = qm.getStaticAnalysis();
        ctx.setVariable("staticAnalysisQuality", sa);
        double saImpactValue = 0.0;
        if (Objects.nonNull(sa)) {
            saImpactValue = sa.getImpact();
            ctx.setVariable("pmdViolationsInChanges", sa.getPmdViolationsInChanges());
            ctx.setVariable("spotbugsIssuesInChanges", sa.getSpotbugsIssuesInChanges());
            ctx.setVariable("staticAnalysisImpact", formatImpact(sa.getImpact()));
            ctx.setVariable("staticAnalysisClean", sa.getImpact() > 0);
        }
        var cov = qm.getCoverageAnalysis();
        ctx.setVariable("coverageQuality", cov);
        double covImpactValue = 0.0;
        if (Objects.nonNull(cov)) {
            covImpactValue = cov.getImpact();
            ctx.setVariable("qualityCoveragePercent", formatScore(cov.getCoveragePercent()));
            ctx.setVariable("coverageImpact", formatImpact(cov.getImpact()));
        }
        var arch = qm.getArchitectureAnalysis();
        ctx.setVariable("architectureQuality", arch);
        double archImpactValue = 0.0;
        if (Objects.nonNull(arch)) {
            archImpactValue = arch.getPenaltyImpact();
            ctx.setVariable("solidViolations", Objects.nonNull(arch.getSolidViolations()) ? arch.getSolidViolations() : Collections.emptyList());
            ctx.setVariable("architectureIssues", Objects.nonNull(arch.getArchitectureIssues()) ? arch.getArchitectureIssues() : Collections.emptyList());
            ctx.setVariable("architecturePenalty", formatImpact(arch.getPenaltyImpact()));
        }
        var qg = qm.getQualityGateAnalysis();
        ctx.setVariable("qualityGateAnalysis", qg);
        double qgImpactValue = 0.0;
        if (Objects.nonNull(qg)) {
            qgImpactValue = qg.getImpact();
            ctx.setVariable("failedQualityGates", Objects.nonNull(qg.getFailedGates()) ? qg.getFailedGates() : Collections.emptyList());
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
        var bonus = response.getArchitectureEffortBonus();
        ctx.setVariable("architectureBonus", bonus);
        if (Objects.isNull(bonus)) {
            return;
        }
        ctx.setVariable("architectureImpactScore", bonus.getArchitectureImpactScore());
        ctx.setVariable("qualityFactor", formatScore(bonus.getQualityFactor()));
        ctx.setVariable("bonusBaseEffort", formatScore(bonus.getBaseEffort()));
        ctx.setVariable("bonusCalculation", bonus.getBonusCalculation());
        ctx.setVariable("bonusPoints", formatScore(bonus.getBonusPoints()));
    }
    private static void populateRiskAssessment(Context ctx, LlmScoringResponse response) {
        var risk = response.getRiskAssessment();
        ctx.setVariable("riskAssessment", risk);
        if (Objects.isNull(risk)) {
            return;
        }
        ctx.setVariable("riskScore", risk.getRiskScore());
        ctx.setVariable("riskLevel", Objects.nonNull(risk.getRiskLevel()) ? risk.getRiskLevel().name().toLowerCase() : null);
        ctx.setVariable("riskLevelClass", getRiskLevelClass(risk.getRiskLevel()));
    }
    private static void populateCpdDetails(Context ctx, LlmScoringRequest request) {
        var dup = request.getDuplication();
        if (Objects.isNull(dup) || CollectionUtils.isEmpty(dup.getCloneDetails())) {
            ctx.setVariable("cpdDuplicationCount", 0);
            ctx.setVariable("cpdDuplications", Collections.emptyList());
            ctx.setVariable("cpdMarkdownReport", "");
            return;
        }
        var clones = dup.getCloneDetails();
        ctx.setVariable("cpdDuplicationCount", clones.size());
        ctx.setVariable("cpdDuplications", clones);
        long introducedClones = clones.stream().filter(c -> c.isIntroducedInCommit()).count();
        long preExistingClones = clones.size() - introducedClones;
        long testOnlyClones = clones.stream().filter(c -> c.isAllTestCode()).count();
        long productionClones = clones.size() - testOnlyClones;
        long selfDupClones = clones.stream().filter(c -> c.isSelfDuplication()).count();
        long crossFileClones = clones.stream().filter(c -> c.isCrossFile()).count();
        double effectivePenalty = 0;
        for (var clone : clones) {
            if (clone.isIntroducedInCommit()) {
                effectivePenalty += clone.isAllTestCode() ? TEST_CODE_PENALTY_WEIGHT : 1.0;
            }
        }
        StringBuilder md = new StringBuilder();
        md.append("## What This Report Shows\n\n");
        md.append("We scan the **entire project** for duplicated code blocks, then filter to show only those that **overlap with lines you changed**.\n");
        md.append("A duplicate is \"introduced\" if your changes created or significantly modified the duplicated code.\n\n");
        md.append("### Quick Summary\n\n");
        md.append("| What | Count | What It Means |\n");
        md.append("|------|-------|---------------|\n");
        md.append(String.format("| **Duplicated Blocks** | %d | Code blocks that appear more than once and touch your changes |\n", clones.size()));
        md.append(String.format("| Duplicated Lines | %d | Total lines of duplicated code |\n", dup.getTotalDuplicatedLines()));
        md.append(String.format("| Duplication Rate | %.1f%% | Percentage of changed code that is duplicated |\n", dup.getDuplicatedPercentage()));
        md.append("\n### Score Impact Breakdown\n\n");
        md.append("| Category | Count | Score Impact |\n");
        md.append("|----------|-------|-------------|\n");
        md.append(String.format("| 🔴 **Introduced (you created)** | %d | Penalized |\n", introducedClones));
        md.append(String.format("| ✅ Pre-existing (not your fault) | %d | No penalty |\n", preExistingClones));
        md.append(String.format("| 🧪 In test code | %d | 1/5 weight if introduced |\n", testOnlyClones));
        md.append(String.format("| ⚙️ In production code | %d | Full weight if introduced |\n", productionClones));
        if (selfDupClones > 0) {
            md.append(String.format("| ⚠️ Self-duplication | %d | Should refactor |\n", selfDupClones));
        }
        if (crossFileClones > 0) {
            md.append(String.format("| 📁 Cross-file | %d | Consider shared utility |\n", crossFileClones));
        }
        if (introducedClones > 0) {
            md.append("\n### 🔴 Why These Are \"Introduced\"\n\n");
            md.append("A clone is marked **introduced** when ≥40% of its lines overlap with your diff.\n\n");
            md.append("**Labels:** 🧪 = test code (1/5 penalty weight), ⚙️ = production code (full penalty)\n\n");
            int idx = 0;
            for (var clone : clones) {
                if (clone.isIntroducedInCommit()) {
                    idx++;
                    int totalLines = clone.getLineCount();
                    String testIcon = clone.isAllTestCode() ? "🧪" : "⚙️";
                    md.append(String.format("**%d. %s Clone** (%d lines)\n", idx, testIcon, totalLines));
                    for (var loc : clone.getLocations()) {
                        String fileName = Objects.nonNull(loc.getFile())
                                ? loc.getFile().substring(Math.max(0, loc.getFile().lastIndexOf('/') + 1))
                                : "unknown";
                        int overlap = loc.getLinesOverlappingDiff();
                        double overlapPct = (overlap * 100.0 / Math.max(totalLines, 1));
                        String locIcon = loc.isTestCode() ? "🧪" : "⚙️";
                        String marker = loc.isIntroducedInCommit() ? " ← **INTRODUCED**" : "";
                        md.append(String.format("   - %s `%s` lines %d-%d: **%d/%d lines overlap (%.0f%%)**%s\n",
                                locIcon, fileName, loc.getStartLine(), loc.getEndLine(),
                                overlap, totalLines, overlapPct, marker));
                    }
                    md.append("\n");
                }
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
        md.append("\n### Your Score Impact\n\n");
        md.append(String.format("**Overall Rating:** %s\n\n", penaltyCategory));
        md.append(String.format("**Score Effect:** %s\n\n", penaltyImpact));
        md.append(String.format("*%s*\n", penaltyExplanation));
        if (CollectionUtils.isNotEmpty(dup.getClonesFromExisting())) {
            md.append("\n### ⚠️ Code Copied From Existing (Unchanged) Code\n\n");
            md.append("Your new/modified code duplicates existing code that you didn't touch:\n\n");
            int copyIdx = 0;
            for (var clone : dup.getClonesFromExisting()) {
                copyIdx++;
                md.append(String.format("**%d.** Your code in: `%s`\n", copyIdx, formatSignatureAsLocation(clone.getAffectedSignature())));
                md.append("   Duplicates existing code in:\n");
                for (var src : clone.getSourceSignatures()) {
                    md.append(String.format("   - `%s`\n", formatSignatureAsLocation(src)));
                }
            }
            md.append("\n💡 **Suggestion:** Extract the common code into a shared utility method.\n");
        }
        if (CollectionUtils.isNotEmpty(dup.getNewClones())) {
            md.append("\n### 🔴 Copy-Pasted Within This Commit\n\n");
            md.append("You wrote the same code multiple times in this commit:\n\n");
            int groupIdx = 0;
            for (var group : dup.getNewClones()) {
                groupIdx++;
                md.append(String.format("**Group %d** (%d copies):\n", groupIdx, group.getMemberSignatures().size()));
                for (var sig : group.getMemberSignatures()) {
                    md.append(String.format("- `%s`\n", formatSignatureAsLocation(sig)));
                }
                md.append("\n");
            }
            md.append("💡 **Suggestion:** Write the code once and call it from other places.\n");
        }
        md.append("\n### Detailed View\n\n");
        md.append("Below are the exact locations where duplicate code was found:\n\n");
        int shownClones = 0;
        for (var clone : clones) {
            if (shownClones >= MAX_CLONES_TO_SHOW) {
                md.append(String.format("\n*... and %d more clones (see full analysis in data)*\n", clones.size() - shownClones));
                break;
            }
            shownClones++;
            String status = clone.isIntroducedInCommit() ? "🔴 New (affects your score)" : "✅ Already existed (no penalty)";
            String testStatus = clone.isAllTestCode() ? "Test Code" : "Production Code";
            String selfStatus = clone.isSelfDuplication() ? " ⚠️ Copy-paste within same commit" : "";
            md.append(String.format("---\n#### Duplicate #%d: %s\n", shownClones, status));
            md.append(String.format("**Type:** %s%s | **Size:** %d lines\n\n", testStatus, selfStatus, clone.getLineCount()));
            md.append("**Locations:**\n\n");
            int locNum = 0;
            String firstSourceSlice = null;
            for (var loc : clone.getLocations()) {
                locNum++;
                String locStatus = loc.isIntroducedInCommit()
                        ? String.format("**INTRODUCED** (%d lines overlap with diff)", loc.getLinesOverlappingDiff())
                        : "pre-existing";
                String locTest = loc.isTestCode() ? "🧪 TEST" : "⚙️ PROD";
                String fileName = Objects.nonNull(loc.getFile()) ? loc.getFile().substring(Math.max(0, loc.getFile().lastIndexOf('/') + 1)) : "unknown";
                String methodInfo = Objects.nonNull(loc.getMethodSignature())
                        ? String.format(" in `%s`", truncateSignature(loc.getMethodSignature()))
                        : "";
                md.append(String.format("%d. %s [**%s:%d-%d**]%s → %s\n",
                        locNum, locTest, fileName, loc.getStartLine(), loc.getEndLine(), methodInfo, locStatus));
                if (Objects.isNull(firstSourceSlice) && Objects.nonNull(loc.getSourceSlice()) && !loc.getSourceSlice().isEmpty()) {
                    firstSourceSlice = loc.getSourceSlice();
                }
            }
            if (Objects.nonNull(firstSourceSlice) && !firstSourceSlice.isEmpty()) {
                md.append("\n**Duplicated Code:**\n");
                md.append("```java\n");
                String[] lines = firstSourceSlice.split("\n");
                if (lines.length > MAX_SOURCE_LINES) {
                    for (int i = 0; i < TRUNCATE_SOURCE_LINES; i++) {
                        md.append(lines[i]).append("\n");
                    }
                    md.append("// ... truncated (" + (lines.length - TRUNCATE_SOURCE_LINES) + " more lines) ...\n");
                } else {
                    if (firstSourceSlice.endsWith("\n")) {
                        md.append(firstSourceSlice);
                    } else {
                        md.append(firstSourceSlice).append("\n");
                    }
                }
                md.append("```\n");
            }
            md.append("\n");
        }
        ctx.setVariable("cpdMarkdownReport", md.toString());
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
    private static void populateBlastRadius(Context ctx, LlmScoringResponse response, LlmScoringRequest request) {
        BlastRadiusAnalysis br = response.getBlastRadiusAnalysis();
        ctx.setVariable("blastRadius", br);
        int totalCallers = 0;
        int productionCallers = 0;
        int testCallers = 0;
        if (Objects.nonNull(request.getMethodChanges())) {
            for (var method : request.getMethodChanges()) {
                if (Objects.nonNull(method.getCallers())) {
                    for (var caller : method.getCallers()) {
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
            LlmScoringResponse.RiskLevel fallbackRisk = totalCallers > CALLER_THRESHOLD_HIGH ? LlmScoringResponse.RiskLevel.HIGH :
                    totalCallers > CALLER_THRESHOLD_MODERATE ? LlmScoringResponse.RiskLevel.MODERATE : LlmScoringResponse.RiskLevel.LOW;
            ctx.setVariable("brRiskLevel", fallbackRisk);
            ctx.setVariable("brRiskLevelClass", getRiskLevelClass(fallbackRisk));
            ctx.setVariable("brCriticalCallers", Collections.emptyList());
            ctx.setVariable("brExplanation", null);
            ctx.setVariable("brModuleType", null);
            ctx.setVariable("brModuleTypeDisplay", "Unknown");
            ctx.setVariable("brExternalImpact", null);
            ctx.setVariable("brExternalImpactClass", "bg-gray-100 text-gray-600");
            return;
        }
        ctx.setVariable("brRiskLevel", br.getRiskLevel());
        ctx.setVariable("brRiskLevelClass", getRiskLevelClass(br.getRiskLevel()));
        ctx.setVariable("brCriticalCallers", Objects.nonNull(br.getCriticalCallers()) ? br.getCriticalCallers() : Collections.emptyList());
        ctx.setVariable("brExplanation", br.getExplanation());
        ctx.setVariable("brModuleType", br.getModuleType());
        ctx.setVariable("brModuleTypeDisplay", formatModuleType(br.getModuleType()));
        ctx.setVariable("brExternalImpact", br.getExternalImpactEstimate());
        ctx.setVariable("brExternalImpactClass", getExternalImpactClass(br.getExternalImpactEstimate()));
        var sig = br.getSignatureChanges();
        ctx.setVariable("signatureChanges", sig);
        ctx.setVariable("hasBreakingChanges", Objects.nonNull(sig) && sig.isHasBreakingChanges());
        ctx.setVariable("changedSignatures", Objects.nonNull(sig) && Objects.nonNull(sig.getChangedSignatures()) ? sig.getChangedSignatures() : Collections.emptyList());
        ctx.setVariable("breakingChangeType", Objects.nonNull(sig) ? sig.getBreakingChangeType() : null);
    }
    private static void populateStaticAnalysisReview(Context ctx, LlmScoringResponse response) {
        var review = response.getStaticAnalysisReview();
        ctx.setVariable("staticAnalysisReview", review);
        ctx.setVariable("pmdInChangedLines", Objects.nonNull(review) && Objects.nonNull(review.getPmdInChangedLines()) ? review.getPmdInChangedLines() : Collections.emptyList());
        ctx.setVariable("pmdPreExisting", Objects.nonNull(review) && Objects.nonNull(review.getPmdPreExisting()) ? review.getPmdPreExisting() : Collections.emptyList());
        ctx.setVariable("pmdFalsePositives", Objects.nonNull(review) && Objects.nonNull(review.getPmdFalsePositives()) ? review.getPmdFalsePositives() : Collections.emptyList());
        ctx.setVariable("spotbugsInChangedLines",
                Objects.nonNull(review) && Objects.nonNull(review.getSpotbugsInChangedLines()) ? review.getSpotbugsInChangedLines() : Collections.emptyList());
        ctx.setVariable("spotbugsPreExisting",
                Objects.nonNull(review) && Objects.nonNull(review.getSpotbugsPreExisting()) ? review.getSpotbugsPreExisting() : Collections.emptyList());
        ctx.setVariable("spotbugsFalsePositives",
                Objects.nonNull(review) && Objects.nonNull(review.getSpotbugsFalsePositives()) ? review.getSpotbugsFalsePositives() : Collections.emptyList());
        int pmdNewCount = Objects.nonNull(review) && Objects.nonNull(review.getPmdInChangedLines()) ? review.getPmdInChangedLines().size() : 0;
        int spotbugsNewCount = Objects.nonNull(review) && Objects.nonNull(review.getSpotbugsInChangedLines()) ? review.getSpotbugsInChangedLines().size() : 0;
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
    private static String getRiskLevelClass(LlmScoringResponse.RiskLevel level) {
        if (Objects.isNull(level)) {
            return "bg-gray-100 text-gray-600";
        }
        switch (level) {
            case CRITICAL:
                return "bg-red-100 text-red-700";
            case VERY_HIGH:
            case HIGH:
                return "bg-orange-100 text-orange-700";
            case MODERATE:
                return "bg-yellow-100 text-yellow-700";
            case LOW:
                return "bg-green-100 text-green-700";
            default:
                throw new IllegalArgumentException("Unknown risk level: " + level);
        }
    }
    private static String formatModuleType(LlmScoringResponse.ModuleType type) {
        if (Objects.isNull(type)) {
            return "Unknown";
        }
        switch (type) {
            case CORE_LIBRARY:
                return "Core Library";
            case SHARED_UTILITY:
                return "Shared Utility";
            case LEAF_APPLICATION:
                return "Leaf Application";
            default:
                throw new IllegalArgumentException("Unknown module type: " + type);
        }
    }
    private static String getExternalImpactClass(LlmScoringResponse.ExternalImpact impact) {
        if (Objects.isNull(impact)) {
            return "bg-gray-100 text-gray-600";
        }
        switch (impact) {
            case HIGH:
                return "bg-red-100 text-red-700";
            case MEDIUM:
                return "bg-orange-100 text-orange-700";
            case LOW:
                return "bg-yellow-100 text-yellow-700";
            case NONE:
                return "bg-green-100 text-green-700";
            case UNKNOWN:
                return "bg-gray-100 text-gray-600";
            default:
                throw new IllegalArgumentException("Unknown external impact: " + impact);
        }
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
    private static String getDangerClass(double score) {
        if (score >= SCORE_THRESHOLD_HUGE) {
            return "danger-critical";
        }
        if (score >= SCORE_THRESHOLD_LARGE) {
            return "danger-high";
        }
        if (score >= SCORE_THRESHOLD_MEDIUM) {
            return "danger-medium";
        }
        return "danger-low";
    }
    private static String getDangerLevel(double score) {
        if (score >= SCORE_THRESHOLD_HUGE) {
            return "HUGE";
        }
        if (score >= SCORE_THRESHOLD_LARGE) {
            return "LARGE";
        }
        if (score >= SCORE_THRESHOLD_MEDIUM) {
            return "MEDIUM";
        }
        if (score >= SCORE_THRESHOLD_SMALL) {
            return "SMALL";
        }
        return "TRIVIAL";
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
        if (Objects.isNull(dim)) {
            return;
        }
        boolean applicable = Objects.nonNull(dim.getScore());
        int score = applicable ? dim.getScore() : 0;
        views.add(DimensionView.builder()
                .name(name)
                .icon(getIconForType(iconType))
                .score(score)
                .rationale(dim.getRationale())
                .applicable(applicable)
                .scoreDisplay(applicable ? String.valueOf(score) : "N/A")
                .scoreClass(getScoreClass(score, applicable))
                .barClass(getBarClass(score, applicable))
                .build());
    }
    private static String getIconForType(String type) {
        switch (type) {
            case "architecture":
                return "\uD83C\uDFD7\uFE0F";
            case "concurrency":
                return "\u26A1";
            case "integration":
                return "\uD83D\uDD17";
            case "data":
                return "\uD83D\uDDC4\uFE0F";
            case "security":
                return "\uD83D\uDD12";
            case "scalability":
                return "\uD83D\uDCC8";
            case "observability":
                return "\uD83D\uDC41\uFE0F";
            case "resilience":
                return "\uD83D\uDEE1\uFE0F";
            case "performance":
                return "\u26A1";
            case "testing":
                return "\u2705";
            default:
                return "\u2753";
        }
    }
    private static String getScoreClass(int score, boolean applicable) {
        if (applicable) {
            if (score >= DIMENSION_SCORE_CRITICAL) {
                return "score-critical";
            }
            if (score >= DIMENSION_SCORE_MAJOR) {
                return "score-major";
            }
            if (score >= DIMENSION_SCORE_MODERATE) {
                return "score-moderate";
            }
            return "score-low";
        }
        return "score-na";
    }
    private static String getBarClass(int score, boolean applicable) {
        if (applicable) {
            if (score >= DIMENSION_SCORE_CRITICAL) {
                return "bar-critical";
            }
            if (score >= DIMENSION_SCORE_MAJOR) {
                return "bar-major";
            }
            if (score >= DIMENSION_SCORE_MODERATE) {
                return "bar-moderate";
            }
            return "bar-low";
        }
        return "bar-na";
    }
    private static List<FindingView> buildFindings(LlmScoringResponse response) {
        List<FindingView> toReturn = Lists.newArrayList();
        if (Objects.isNull(response.getBugs())) {
            return toReturn;
        }
        if (Objects.nonNull(response.getBugs().getBlocking())) {
            response.getBugs().getBlocking().forEach(bug -> toReturn.add(buildFindingView(bug, "severity-critical", "\uD83D\uDED1")));
        }
        if (Objects.nonNull(response.getBugs().getMajor())) {
            response.getBugs().getMajor().forEach(bug -> toReturn.add(buildFindingView(bug, "severity-major", "\u26A0\uFE0F")));
        }
        if (Objects.nonNull(response.getBugs().getMinor())) {
            response.getBugs().getMinor().forEach(bug -> toReturn.add(buildFindingView(bug, "severity-minor", "\u2139\uFE0F")));
        }
        return toReturn;
    }
    private static FindingView buildFindingView(LlmScoringResponse.Bug bug, String severityClass, String severityIcon) {
        return FindingView.builder()
                .title(bug.getTitle())
                .description(bug.getDescription())
                .file(sanitizePath(bug.getFile()))
                .lineHint(Objects.nonNull(bug.getLine()) ? "line " + bug.getLine() : null)
                .severityClass(severityClass)
                .severityIcon(severityIcon)
                .type(Objects.nonNull(bug.getType()) ? bug.getType().name() : null)
                .typeDisplay(formatBugType(bug.getType()))
                .confidence(Objects.nonNull(bug.getConfidence()) ? bug.getConfidence().name() : null)
                .source(Objects.nonNull(bug.getSource()) ? bug.getSource().name() : null)
                .suggestedFix(bug.getSuggestedFix())
                .build();
    }
    private static String formatBugType(LlmScoringResponse.BugType type) {
        if (Objects.isNull(type)) {
            return "Other";
        }
        switch (type) {
            case SECURITY:
                return "🔐 Security";
            case CONCURRENCY:
                return "⚡ Concurrency";
            case DATA_CORRUPTION:
                return "💾 Data Corruption";
            case LOGIC:
                return "🧠 Logic";
            case RESOURCE_LEAK:
                return "💧 Resource Leak";
            case NULL_POINTER:
                return "❌ Null Pointer";
            case ARCHITECTURE:
                return "🏗️ Architecture";
            case OTHER:
                return "❓ Other";
            default:
                throw new IllegalArgumentException("Unknown bug type: " + type);
        }
    }
    private static int countJavaFiles(LlmScoringRequest request) {
        if (Objects.isNull(request.getFileChanges())) {
            return 0;
        }
        return (int) request.getFileChanges().stream()
                .filter(f -> Objects.nonNull(f.getPath()) && "java".equals(FilenameUtils.getExtension(f.getPath())))
                .count();
    }
    private static int countAffectedSymbols(LlmScoringRequest request) {
        if (Objects.isNull(request.getMethodChanges())) {
            return 0;
        }
        return request.getMethodChanges().size();
    }
    private static int countConfirmedErrors(LlmScoringResponse response) {
        if (Objects.isNull(response.getBugs())) {
            return 0;
        }
        return CollectionUtils.size(response.getBugs().getBlocking()) + CollectionUtils.size(response.getBugs().getMajor());
    }
    private static List<FileView> buildFileViews(LlmScoringRequest request) {
        if (Objects.isNull(request.getFileChanges())) {
            return Collections.emptyList();
        }
        Map<String, List<LlmScoringRequest.MethodChange>> methodsByFile = Maps.newHashMap();
        if (Objects.nonNull(request.getMethodChanges())) {
            for (var method : request.getMethodChanges()) {
                if (Objects.nonNull(method.getFile())) {
                    methodsByFile.computeIfAbsent(method.getFile(), k -> Lists.newArrayList()).add(method);
                }
            }
        }
        List<FileView> toReturn = Lists.newArrayList();
        for (var file : request.getFileChanges()) {
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
            List<LlmScoringRequest.MethodChange> methods = methodsByFile.get(originalPath);
            if (Objects.isNull(methods)) {
                for (var entry : methodsByFile.entrySet()) {
                    if (entry.getKey().endsWith(path) || originalPath.endsWith(entry.getKey())) {
                        methods = entry.getValue();
                        break;
                    }
                }
            }
            if (Objects.nonNull(methods)) {
                for (var method : methods) {
                    symbols.add(buildSymbolView(method));
                }
            }
            toReturn.add(FileView.builder()
                    .path(path)
                    .fileName(fileName)
                    .fileType(fileType)
                    .fileTypeIcon(getFileTypeIcon(fileType))
                    .changeType(changeType)
                    .changeTypeClass(getChangeTypeClass(changeType))
                    .diff(file.getDiff())
                    .hljsLanguage(getHljsLanguage(fileType))
                    .affectedSymbols(symbols)
                    .build());
        }
        return toReturn;
    }
    private static SymbolView buildSymbolView(LlmScoringRequest.MethodChange method) {
        List<CallerView> callerViews = Lists.newArrayList();
        if (Objects.nonNull(method.getCallers())) {
            for (var caller : method.getCallers()) {
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
        String kindIcon = method.isConstructor() ? "\uD83C\uDFD7\uFE0F" : "\uD83D\uDD27";
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
                .kindIcon(kindIcon)
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
    private static String getFileTypeIcon(String fileType) {
        switch (fileType) {
            case "java":
                return "\u2615";
            case "kt":
                return "\uD83C\uDDF0";
            case "scala":
                return "\uD83C\uDDF8";
            case "py":
                return "\uD83D\uDC0D";
            case "go":
                return "\uD83D\uDC39";
            case "rs":
                return "\u2699\uFE0F";
            case "ts":
            case "js":
                return "\uD83D\uDFE8";
            case "sql":
                return "\uD83D\uDDC3\uFE0F";
            case "xml":
            case "yaml":
            case "json":
                return "\uD83D\uDCC4";
            case "properties":
                return "\u2699\uFE0F";
            case "gradle":
                return "\uD83D\uDC18";
            default:
                return "\uD83D\uDCC4";
        }
    }
    private static String getChangeTypeClass(String operation) {
        if (Objects.isNull(operation)) {
            return "bg-blue-100 text-blue-800";
        }
        switch (operation.toUpperCase()) {
            case "NEW":
                return "bg-green-100 text-green-800";
            case "DELETE":
                return "bg-red-100 text-red-800";
            case "MODIFY":
                return "bg-blue-100 text-blue-800";
            default:
                return "bg-gray-100 text-gray-800";
        }
    }
    private static String getHljsLanguage(String fileType) {
        switch (fileType) {
            case "java":
                return "java";
            case "kt":
                return "kotlin";
            case "scala":
                return "scala";
            case "py":
                return "python";
            case "go":
                return "go";
            case "rs":
                return "rust";
            case "ts":
                return "typescript";
            case "js":
                return "javascript";
            case "xml":
                return "xml";
            case "yaml":
                return "yaml";
            case "json":
                return "json";
            case "sql":
                return "sql";
            case "properties":
                return "properties";
            case "gradle":
                return "gradle";
            default:
                return "plaintext";
        }
    }
    @Value
    @Builder
    public static class DimensionView {
        String name;
        String icon;
        int score;
        String rationale;
        boolean applicable;
        String scoreDisplay;
        String scoreClass;
        String barClass;
    }
    @Value
    @Builder
    public static class FindingView {
        String title;
        String description;
        String file;
        String lineHint;
        String severityClass;
        String severityIcon;
        String type;
        String typeDisplay;
        String confidence;
        String source;
        String suggestedFix;
    }
    @Value
    @Builder
    public static class FileView {
        String path;
        String fileName;
        String fileType;
        String fileTypeIcon;
        String changeType;
        String changeTypeClass;
        String diff;
        String contentBefore;
        String contentAfter;
        String hljsLanguage;
        @lombok.Builder.Default
        List<SymbolView> affectedSymbols = Collections.emptyList();
    }
    @Value
    @Builder
    public static class SymbolView {
        String name;
        String signature;
        String kindIcon;
        String lineRange;
        String containerName;
        int callerCount;
        @lombok.Builder.Default
        List<CallerView> callers = Collections.emptyList();
    }
    @Value
    @Builder
    public static class CallerView {
        String name;
        String detail;
        String file;
        int line;
    }
}
