package io.codiqo.llm;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import lombok.SneakyThrows;

public class ThymeleafPromptBuilder implements PromptBuilder {
    private static final String TEMPLATE_SYSTEM_PROMPT = "system-prompt";
    private static final String TEMPLATE_USER_PROMPT = "user-message";
    private static final String TEMPLATE_WEB_SEARCH_RESULTS = "web-search-results";
    private static final String TEMPLATE_PRE_COMPUTED_SCORES = "pre-computed-scores";

    private final TemplateEngine templateEngine;
    private final ObjectMapper mapper;
    private final VolumeScoreCalculator volumeCalculator;

    public ThymeleafPromptBuilder(RunArgs args) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);

        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        mapper = new ObjectMapper();
        mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        mapper.setDefaultPropertyInclusion(Include.NON_NULL);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.registerModule(new JavaTimeModule());

        volumeCalculator = new VolumeScoreCalculator(args);
    }
    @Override
    public String buildSystemPrompt(PromptContext context) {
        Context ctx = createContext(context);
        return templateEngine.process(TEMPLATE_SYSTEM_PROMPT, ctx);
    }
    @SneakyThrows
    @Override
    public UserMessageResult buildUserMessageWithScores(LlmScoringRequest request, PromptContext context) {
        Context ctx = createContext(context);
        ctx.setVariable("request", request);
        ctx.setVariable("requestJson", mapper.writeValueAsString(request));

        PreComputedScores preComputedScores = volumeCalculator.calculate(request, context.getProjectTotalLines());
        ctx.setVariable("preComputedScores", preComputedScores);
        ctx.setVariable("preComputedScoresSection", buildPreComputedScoresSection(preComputedScores));

        String message = templateEngine.process(TEMPLATE_USER_PROMPT, ctx);
        return new UserMessageResult(message, preComputedScores);
    }
    @Override
    public String buildWebSearchResults(String query, List<WebSearchResultItem> results) {
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("query", query);
        ctx.setVariable("results", Optional.ofNullable(results).orElse(Collections.emptyList()));
        return templateEngine.process(TEMPLATE_WEB_SEARCH_RESULTS, ctx);
    }
    private String buildPreComputedScoresSection(PreComputedScores scores) {
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("scores", scores);
        return templateEngine.process(TEMPLATE_PRE_COMPUTED_SCORES, ctx);
    }
    private static Context createContext(PromptContext promptContext) {
        Context ctx = new Context(Locale.ENGLISH);
        RunArgs args = promptContext.getArgs();
        long projectLines = promptContext.getProjectTotalLines();
        ctx.setVariable("SIZE_FACTOR_DIVISOR", args.getSizeFactorDivisor());
        ctx.setVariable("MODIFY_MULTIPLIER_SCALE", args.getModifyMultiplierScale());
        ctx.setVariable("MODIFY_MULTIPLIER_CAP", args.getModifyMultiplierCap());
        ctx.setVariable("ADD_MULTIPLIER_SCALE", args.getAddMultiplierScale());
        ctx.setVariable("RELATIVE_ADJUSTMENT_FACTOR", args.getRelativeAdjustmentFactor());
        ctx.setVariable("RELATIVE_ADJUSTMENT_MIN", args.getRelativeAdjustmentMin());
        ctx.setVariable("RELATIVE_ADJUSTMENT_MAX", args.getRelativeAdjustmentMax());
        ctx.setVariable("QUALITY_MULTIPLIER_MIN", args.getQualityMultiplierMin());
        ctx.setVariable("QUALITY_MULTIPLIER_MAX", args.getQualityMultiplierMax());
        ctx.setVariable("STATIC_ANALYSIS_PENALTY_CAP", args.getStaticAnalysisPenaltyCap());
        ctx.setVariable("ARCHITECTURE_PENALTY_CAP", args.getArchitecturePenaltyCap());
        ctx.setVariable("QUALITY_GATE_PENALTY_CAP", args.getQualityGatePenaltyCap());
        double sizeFactor = Math.cbrt(projectLines) / args.getSizeFactorDivisor();
        double modifyMult = 1.0 + Math.min(sizeFactor * args.getModifyMultiplierScale(), args.getModifyMultiplierCap());
        double addMult = 1.0 + args.getAddMultiplierScale() / (1.0 + sizeFactor);
        ctx.setVariable("calculated_size_factor", String.format("%.3f", sizeFactor));
        ctx.setVariable("calculated_modify_mult", String.format("%.3f", modifyMult));
        ctx.setVariable("calculated_add_mult", String.format("%.3f", addMult));
        ctx.setVariable("lines_log_factor", args.getLinesLogFactor());
        ctx.setVariable("methods_mod_log_factor", args.getMethodsModifiedLogFactor());
        ctx.setVariable("methods_add_log_factor", args.getMethodsAddedLogFactor());
        ctx.setVariable("classes_mod_log_factor", args.getClassesModifiedLogFactor());
        ctx.setVariable("classes_add_log_factor", args.getClassesAddedLogFactor());
        ctx.setVariable("trivial_max", args.getComplexityTrivialMax());
        ctx.setVariable("moderate_max", args.getComplexityModerateMax());
        ctx.setVariable("complex_max", args.getComplexityComplexMax());
        ctx.setVariable("modify_trivial_mult", args.getModifyTrivialMultiplier());
        ctx.setVariable("modify_moderate_mult", args.getModifyModerateMultiplier());
        ctx.setVariable("modify_complex_mult", args.getModifyComplexMultiplier());
        ctx.setVariable("modify_highly_complex_mult", args.getModifyHighlyComplexMultiplier());
        ctx.setVariable("create_trivial_mult", args.getCreateTrivialMultiplier());
        ctx.setVariable("create_moderate_mult", args.getCreateModerateMultiplier());
        ctx.setVariable("create_complex_mult", args.getCreateComplexMultiplier());
        ctx.setVariable("create_highly_complex_mult", args.getCreateHighlyComplexMultiplier());
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
        ctx.setVariable("project_total_lines", projectLines);
        ctx.setVariable("project_total_files", promptContext.getProjectTotalFiles());
        ctx.setVariable("project_total_methods", promptContext.getProjectTotalMethods());
        ctx.setVariable("code_units_affected", promptContext.getCodeUnitsAffected());
        ctx.setVariable("technical_tags", String.join(", ", promptContext.getTechnicalTags()));
        ctx.setVariable("functional_tags", String.join(", ", promptContext.getFunctionalTags()));
        ctx.setVariable("native_thinking", promptContext.isNativeThinking());
        return ctx;
    }
}
