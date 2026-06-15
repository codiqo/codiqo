package io.codiqo.llm;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;

public class ThymeleafPromptBuilder implements PromptBuilder {
    private static final String TEMPLATE_SYSTEM_PROMPT = "system-prompt";
    private static final String TEMPLATE_USER_PROMPT = "user-message";
    private static final String TEMPLATE_WEB_SEARCH_RESULTS = "web-search-results";
    private static final String TEMPLATE_PRE_COMPUTED_SCORES = "pre-computed-scores";
    private static final String TEMPLATE_VALIDATION_FEEDBACK = "validation-feedback";
    private static final TemplateEngine TEMPLATE_ENGINE;
    private static final ObjectMapper MAPPER;
    private static final Encoding TOKEN_ENCODER = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    static {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);

        TEMPLATE_ENGINE = new TemplateEngine();
        TEMPLATE_ENGINE.setTemplateResolver(resolver);

        MAPPER = new ObjectMapper();
        MAPPER.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        MAPPER.setDefaultPropertyInclusion(Include.NON_NULL);
        MAPPER.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
        MAPPER.registerModule(new JavaTimeModule());
    }

    private final Log log;
    private final VolumeScoreCalculator volumeCalculator;

    public ThymeleafPromptBuilder(RunArgs args, Log log) {
        this.log = log;
        volumeCalculator = new VolumeScoreCalculator(args);
    }
    @Override
    public String buildSystemPrompt(PromptContext context) {
        Context ctx = createContext(context);
        return TEMPLATE_ENGINE.process(TEMPLATE_SYSTEM_PROMPT, ctx);
    }
    @SneakyThrows
    @Override
    public UserMessageResult buildUserMessageWithScores(LlmScoringRequest request, PromptContext context) {
        Context ctx = createContext(context);
        ctx.setVariable("request", request);

        Map<LlmScoringRequest.DuplicationInfo.CloneLocation, String> savedSlices = stripSourceSlices(request);
        Map<LlmScoringRequest.FileChange, String> savedDiffs = annotateDiffs(request);
        String requestJson = MAPPER.writeValueAsString(request);
        restoreDiffs(savedDiffs);
        restoreSourceSlices(savedSlices);

        ctx.setVariable("requestJson", requestJson);

        PreComputedScores preComputedScores = volumeCalculator.calculate(
                request,
                context.getProjectTotalStatements(),
                context.getProjectTotalMethods(),
                context.getMethodCapQuantileProd(),
                context.getMethodCapQuantileTest(),
                context.getConstructorCapQuantileProd(),
                context.getConstructorCapQuantileTest());
        logPromptMetrics(request, preComputedScores, requestJson);
        ctx.setVariable("preComputedScores", preComputedScores);
        ctx.setVariable("preComputedScoresSection", buildPreComputedScoresSection(preComputedScores));

        String message = TEMPLATE_ENGINE.process(TEMPLATE_USER_PROMPT, ctx);
        return new UserMessageResult(message, preComputedScores);
    }
    @SneakyThrows
    private void logPromptMetrics(LlmScoringRequest request, PreComputedScores scores, String requestJson) {
        LlmScoringRequest.ChangeSummary cs = request.getChangeSummary();
        log.info("prompt: files=%d, methods=%d, lines=%d (effectiveStmts=%d) | json=%d chars (~%d tokens)",
                cs.getTotalFilesChanged(),
                cs.getCodeBlocksModified() + cs.getCodeBlocksAdded(),
                cs.getTotalLinesChanged(),
                scores.getTotalEffectiveStatements(),
                requestJson.length(),
                estimateTokens(requestJson));

        if (CollectionUtils.isEmpty(request.getFileChanges())) {
            return;
        }

        List<FileTokens> perFile = Lists.newArrayList();
        for (LlmScoringRequest.FileChange file : request.getFileChanges()) {
            int tokens = estimateTokens(MAPPER.writeValueAsString(file));
            perFile.add(FileTokens.builder().path(file.getPath()).tokens(tokens).linesChanged(file.getLinesAdded() + file.getLinesDeleted()).build());
        }
        perFile.sort(Comparator.comparingInt(FileTokens::getTokens).reversed());

        log.info("token breakdown by file (largest first):");
        for (FileTokens entry : perFile) {
            log.info("  %s: ~%d tokens (%d lines changed)", entry.getPath(), entry.getTokens(), entry.getLinesChanged());
        }
    }
    @Override
    public String buildWebSearchResults(String query, List<WebSearchResultItem> results) {
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("query", query);
        ctx.setVariable("results", Optional.ofNullable(results).orElse(Collections.emptyList()));
        return TEMPLATE_ENGINE.process(TEMPLATE_WEB_SEARCH_RESULTS, ctx);
    }
    @Override
    public String buildValidationFeedback(FinalScoreCalculator.ValidationReport report) {
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("failures", report.getFailures());
        return TEMPLATE_ENGINE.process(TEMPLATE_VALIDATION_FEEDBACK, ctx);
    }
    public static int estimateTokens(String text) {
        return TOKEN_ENCODER.countTokensOrdinary(text);
    }
    private static Map<LlmScoringRequest.DuplicationInfo.CloneLocation, String> stripSourceSlices(LlmScoringRequest request) {
        Map<LlmScoringRequest.DuplicationInfo.CloneLocation, String> saved = Maps.newIdentityHashMap();
        if (Objects.nonNull(request.getDuplication()) && CollectionUtils.isNotEmpty(request.getDuplication().getCloneDetails())) {
            for (LlmScoringRequest.DuplicationInfo.CloneDetail cd : request.getDuplication().getCloneDetails()) {
                if (CollectionUtils.isNotEmpty(cd.getLocations())) {
                    for (LlmScoringRequest.DuplicationInfo.CloneLocation loc : cd.getLocations()) {
                        if (Objects.nonNull(loc.getSourceSlice())) {
                            saved.put(loc, loc.getSourceSlice());
                            loc.setSourceSlice(null);
                        }
                    }
                }
            }
        }
        return saved;
    }
    private static void restoreSourceSlices(Map<LlmScoringRequest.DuplicationInfo.CloneLocation, String> saved) {
        saved.forEach(LlmScoringRequest.DuplicationInfo.CloneLocation::setSourceSlice);
    }
    private static Map<LlmScoringRequest.FileChange, String> annotateDiffs(LlmScoringRequest request) {
        Map<LlmScoringRequest.FileChange, String> saved = Maps.newIdentityHashMap();
        if (CollectionUtils.isNotEmpty(request.getFileChanges())) {
            for (LlmScoringRequest.FileChange fc : request.getFileChanges()) {
                if (Objects.nonNull(fc.getDiff())) {
                    saved.put(fc, fc.getDiff());
                    fc.setDiff(UnifiedDiffLines.parse(fc.getDiff(), fc.getLineProfile()).getAnnotated());
                }
            }
        }
        return saved;
    }
    private static void restoreDiffs(Map<LlmScoringRequest.FileChange, String> saved) {
        saved.forEach(LlmScoringRequest.FileChange::setDiff);
    }
    private static String buildPreComputedScoresSection(PreComputedScores scores) {
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("scores", scores);
        return TEMPLATE_ENGINE.process(TEMPLATE_PRE_COMPUTED_SCORES, ctx);
    }
    private static Context createContext(PromptContext promptContext) {
        Context ctx = new Context(Locale.ENGLISH);
        RunArgs args = promptContext.getArgs();

        long projectStatements = promptContext.getProjectTotalStatements();
        ctx.setVariable("STATIC_ANALYSIS_PENALTY_CAP", args.getStaticAnalysisPenaltyCap());
        ctx.setVariable("ARCHITECTURE_PENALTY_CAP", args.getArchitecturePenaltyCap());
        ctx.setVariable("QUALITY_GATE_PENALTY_CAP", args.getQualityGatePenaltyCap());
        ctx.setVariable("volume_exponent", args.getVolumeExponent());
        ctx.setVariable("fanout_high_threshold", args.getFanOutHighThreshold());
        ctx.setVariable("npath_complex_threshold", args.getNpathComplexThreshold());
        ctx.setVariable("cpd_clean_bonus", String.format("+%.2f", args.getCpdCleanBonus()));
        ctx.setVariable("cpd_moderate_penalty", String.format("+%.2f", args.getCpdModeratePenalty()));
        ctx.setVariable("cpd_high_penalty", String.format("+%.2f", args.getCpdHighPenalty()));
        ctx.setVariable("cpd_severe_penalty", String.format("+%.2f", args.getCpdSeverePenalty()));
        ctx.setVariable("static_analysis_clean_bonus", String.format("+%.2f", args.getStaticAnalysisCleanBonus()));
        ctx.setVariable("pmd_p1_penalty", String.format("+%.2f", args.getPmdPriority1Penalty()));
        ctx.setVariable("pmd_p2_penalty", String.format("+%.2f", args.getPmdPriority2Penalty()));
        ctx.setVariable("pmd_p3_penalty", String.format("+%.2f", args.getPmdPriority3Penalty()));
        ctx.setVariable("spotbugs_scariest_penalty", String.format("+%.2f", args.getSpotbugsScariestPenalty()));
        ctx.setVariable("spotbugs_scary_penalty", String.format("+%.2f", args.getSpotbugsScaryPenalty()));
        ctx.setVariable("spotbugs_troubling_penalty", String.format("+%.2f", args.getSpotbugsTroublingPenalty()));
        ctx.setVariable("coverage_excellent_bonus", String.format("+%.2f", args.getCoverageExcellentBonus()));
        ctx.setVariable("coverage_good_bonus", String.format("+%.2f", args.getCoverageGoodBonus()));
        ctx.setVariable("coverage_low_penalty", String.format("+%.2f", args.getCoverageLowPenalty()));
        ctx.setVariable("coverage_poor_penalty", String.format("+%.2f", args.getCoveragePoorPenalty()));
        ctx.setVariable("coverage_terrible_penalty", String.format("+%.2f", args.getCoverageTerriblePenalty()));
        ctx.setVariable("arch_minor_penalty", String.format("+%.2f", args.getArchitectureMinorPenalty()));
        ctx.setVariable("arch_solid_penalty", String.format("+%.2f", args.getArchitectureSolidPenalty()));
        ctx.setVariable("arch_major_penalty", String.format("+%.2f", args.getArchitectureMajorPenalty()));
        ctx.setVariable("quality_gate_failure_penalty", String.format("+%.2f", args.getQualityGateFailurePenalty()));
        ctx.setVariable("arch_impact_score_threshold", args.getArchitectureImpactScoreThreshold());
        ctx.setVariable("arch_impact_coverage_required", args.getArchitectureImpactCoverageRequired());
        ctx.setVariable("concurrency_risk_threshold", args.getConcurrencyRiskThreshold());
        ctx.setVariable("integration_surface_threshold", args.getIntegrationSurfaceThreshold());
        ctx.setVariable("data_integrity_threshold", args.getDataIntegrityThreshold());
        ctx.setVariable("security_sensitivity_threshold", args.getSecuritySensitivityThreshold());
        ctx.setVariable("scalability_impact_threshold", args.getScalabilityImpactThreshold());
        ctx.setVariable("observability_threshold", args.getObservabilityThreshold());
        ctx.setVariable("resilience_threshold", args.getResilienceThreshold());
        ctx.setVariable("performance_threshold", args.getPerformanceThreshold());
        ctx.setVariable("project_total_statements", projectStatements);
        ctx.setVariable("project_total_files", promptContext.getProjectTotalFiles());
        ctx.setVariable("project_total_methods", promptContext.getProjectTotalMethods());
        ctx.setVariable("code_units_affected", promptContext.getCodeUnitsAffected());
        ctx.setVariable("technical_tags", String.join(", ", promptContext.getTechnicalTags()));
        ctx.setVariable("functional_tags", String.join(", ", promptContext.getFunctionalTags()));
        ctx.setVariable("web_search_enabled", args.isLlmEnableWebSearchTool());
        ctx.setVariable("architecture_bonus_factor", args.getArchitectureBonusFactor());
        ctx.setVariable("quality_multiplier_min", args.getQualityMultiplierMin());
        ctx.setVariable("quality_multiplier_max", args.getQualityMultiplierMax());
        ctx.setVariable("risk_high_dimension_threshold", args.getRiskHighDimensionThreshold());
        ctx.setVariable("risk_base_multiplier", args.getRiskBaseMultiplier());
        ctx.setVariable("risk_high_dim_penalty", args.getRiskHighDimensionPenalty());
        ctx.setVariable("risk_core_library_penalty", args.getRiskCoreLibraryPenalty());
        ctx.setVariable("risk_breaking_changes_penalty", args.getRiskBreakingChangesPenalty());
        ctx.setVariable("risk_score_max", args.getRiskScoreMax());
        ctx.setVariable("risk_level_low_max", args.getRiskLevelLowMax());
        ctx.setVariable("risk_level_moderate_max", args.getRiskLevelModerateMax());
        ctx.setVariable("risk_level_high_max", args.getRiskLevelHighMax());
        ctx.setVariable("risk_level_very_high_max", args.getRiskLevelVeryHighMax());
        ctx.setVariable("cov_excellent_min", args.getCoverageImpactExcellentMin());
        ctx.setVariable("cov_good_min", args.getCoverageImpactGoodMin());
        ctx.setVariable("cov_acceptable_min", args.getCoverageImpactAcceptableMin());
        ctx.setVariable("cov_low_min", args.getCoverageImpactLowMin());
        ctx.setVariable("cov_poor_min", args.getCoverageImpactPoorMin());
        ctx.setVariable("stats_quantile_percent", (int) (args.getStatsQuantile() * 100));
        return ctx;
    }
    @Data
    @Builder
    public static class FileTokens {
        String path;
        int tokens;
        int linesChanged;
    }
}
