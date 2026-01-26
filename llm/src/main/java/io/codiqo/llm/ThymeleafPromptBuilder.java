package io.codiqo.llm;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.codiqo.llm.schema.LlmScoringRequest;
import lombok.Builder;
import lombok.Value;

public class ThymeleafPromptBuilder {
    public static final String TEMPLATE_SYSTEM_PROMPT = "system-prompt";
    public static final String TEMPLATE_USER_PROMPT = "user-message";

    private final TemplateEngine templateEngine;
    private final ObjectMapper mapper;

    public ThymeleafPromptBuilder() {
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
    }

    public String buildSystemPrompt(PromptContext context) {
        Context ctx = createContext(context);
        return templateEngine.process(TEMPLATE_SYSTEM_PROMPT, ctx);
    }

    public String buildUserMessage(LlmScoringRequest request, PromptContext context) {
        for (;;) {
            try {
                Context ctx = createContext(context);
                ctx.setVariable("request", request);
                ctx.setVariable("requestJson", mapper.writeValueAsString(request));
                return templateEngine.process(TEMPLATE_USER_PROMPT, ctx);
            } catch (JsonProcessingException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    private static Context createContext(PromptContext promptContext) {
        Context ctx = new Context(Locale.ENGLISH);
        ScoringConfig config = promptContext.getConfig();
        long projectLines = promptContext.getProjectTotalLines();

        /**
         * ==================== MATHEMATICAL CONSTANTS ====================
         */
        ctx.setVariable("SIZE_FACTOR_DIVISOR", ScoringConfig.SIZE_FACTOR_DIVISOR);
        ctx.setVariable("MODIFY_MULTIPLIER_SCALE", ScoringConfig.MODIFY_MULTIPLIER_SCALE);
        ctx.setVariable("MODIFY_MULTIPLIER_CAP", ScoringConfig.MODIFY_MULTIPLIER_CAP);
        ctx.setVariable("ADD_MULTIPLIER_SCALE", ScoringConfig.ADD_MULTIPLIER_SCALE);
        ctx.setVariable("RELATIVE_ADJUSTMENT_FACTOR", ScoringConfig.RELATIVE_ADJUSTMENT_FACTOR);
        ctx.setVariable("RELATIVE_ADJUSTMENT_MIN", ScoringConfig.RELATIVE_ADJUSTMENT_MIN);
        ctx.setVariable("RELATIVE_ADJUSTMENT_MAX", ScoringConfig.RELATIVE_ADJUSTMENT_MAX);
        ctx.setVariable("QUALITY_MULTIPLIER_MIN", ScoringConfig.QUALITY_MULTIPLIER_MIN);
        ctx.setVariable("QUALITY_MULTIPLIER_MAX", ScoringConfig.QUALITY_MULTIPLIER_MAX);
        ctx.setVariable("STATIC_ANALYSIS_PENALTY_CAP", ScoringConfig.STATIC_ANALYSIS_PENALTY_CAP);
        ctx.setVariable("ARCHITECTURE_PENALTY_CAP", ScoringConfig.ARCHITECTURE_PENALTY_CAP);
        ctx.setVariable("QUALITY_GATE_PENALTY_CAP", ScoringConfig.QUALITY_GATE_PENALTY_CAP);

        /**
         * ==================== PRE-CALCULATED VALUES FOR THIS PROJECT ====================
         */
        double sizeFactor = Math.cbrt(projectLines) / ScoringConfig.SIZE_FACTOR_DIVISOR;
        double modifyMult = 1.0 + Math.min(sizeFactor * ScoringConfig.MODIFY_MULTIPLIER_SCALE, ScoringConfig.MODIFY_MULTIPLIER_CAP);
        double addMult = 1.0 + ScoringConfig.ADD_MULTIPLIER_SCALE / (1.0 + sizeFactor);

        ctx.setVariable("calculated_size_factor", String.format("%.3f", sizeFactor));
        ctx.setVariable("calculated_modify_mult", String.format("%.3f", modifyMult));
        ctx.setVariable("calculated_add_mult", String.format("%.3f", addMult));

        /**
         * ==================== VOLUME SCORING (Logarithmic Factors) ====================
         */
        ScoringConfig.VolumeScoring vol = config.getVolume();
        ctx.setVariable("lines_log_factor", vol.getLinesLogFactor());
        ctx.setVariable("methods_mod_log_factor", vol.getMethodsModifiedLogFactor());
        ctx.setVariable("methods_add_log_factor", vol.getMethodsAddedLogFactor());
        ctx.setVariable("classes_mod_log_factor", vol.getClassesModifiedLogFactor());
        ctx.setVariable("classes_add_log_factor", vol.getClassesAddedLogFactor());

        /**
         * ==================== COMPLEXITY SCORING ====================
         */
        ScoringConfig.ComplexityScoring cmplx = config.getComplexity();
        ctx.setVariable("trivial_max", cmplx.getTrivialMax());
        ctx.setVariable("moderate_max", cmplx.getModerateMax());
        ctx.setVariable("complex_max", cmplx.getComplexMax());
        ctx.setVariable("modify_trivial_mult", cmplx.getModifyTrivialMultiplier());
        ctx.setVariable("modify_moderate_mult", cmplx.getModifyModerateMultiplier());
        ctx.setVariable("modify_complex_mult", cmplx.getModifyComplexMultiplier());
        ctx.setVariable("modify_highly_complex_mult", cmplx.getModifyHighlyComplexMultiplier());
        ctx.setVariable("create_trivial_mult", cmplx.getCreateTrivialMultiplier());
        ctx.setVariable("create_moderate_mult", cmplx.getCreateModerateMultiplier());
        ctx.setVariable("create_complex_mult", cmplx.getCreateComplexMultiplier());
        ctx.setVariable("create_highly_complex_mult", cmplx.getCreateHighlyComplexMultiplier());

        /**
         * ==================== QUALITY IMPACTS ====================
         */
        ScoringConfig.QualityImpacts qual = config.getQualityImpacts();
        ctx.setVariable("cpd_clean_bonus", String.format("+%.2f", qual.getCpdCleanBonus()));
        ctx.setVariable("cpd_moderate_penalty", String.format("+%.2f", qual.getCpdModeratePenalty()));
        ctx.setVariable("cpd_high_penalty", String.format("+%.2f", qual.getCpdHighPenalty()));
        ctx.setVariable("cpd_severe_penalty", String.format("+%.2f", qual.getCpdSeverePenalty()));
        ctx.setVariable("pmd_p1_penalty", String.format("+%.2f", qual.getPmdPriority1Penalty()));
        ctx.setVariable("pmd_p2_penalty", String.format("+%.2f", qual.getPmdPriority2Penalty()));
        ctx.setVariable("pmd_p3_penalty", String.format("+%.2f", qual.getPmdPriority3Penalty()));
        ctx.setVariable("spotbugs_scariest_penalty", String.format("+%.2f", qual.getSpotbugsScariestPenalty()));
        ctx.setVariable("spotbugs_scary_penalty", String.format("+%.2f", qual.getSpotbugsScaryPenalty()));
        ctx.setVariable("spotbugs_troubling_penalty", String.format("+%.2f", qual.getSpotbugsTroublingPenalty()));
        ctx.setVariable("coverage_excellent_bonus", String.format("+%.2f", qual.getCoverageExcellentBonus()));
        ctx.setVariable("coverage_good_bonus", String.format("+%.2f", qual.getCoverageGoodBonus()));
        ctx.setVariable("coverage_low_penalty", String.format("+%.2f", qual.getCoverageLowPenalty()));
        ctx.setVariable("coverage_poor_penalty", String.format("+%.2f", qual.getCoveragePoorPenalty()));
        ctx.setVariable("coverage_terrible_penalty", String.format("+%.2f", qual.getCoverageTerriblePenalty()));
        ctx.setVariable("arch_minor_penalty", String.format("+%.2f", qual.getArchitectureMinorPenalty()));
        ctx.setVariable("arch_solid_penalty", String.format("+%.2f", qual.getArchitectureSolidPenalty()));
        ctx.setVariable("arch_major_penalty", String.format("+%.2f", qual.getArchitectureMajorPenalty()));
        ctx.setVariable("quality_gate_failure_penalty", String.format("+%.2f", qual.getQualityGateFailurePenalty()));

        /**
         * ==================== QUALITY DIMENSIONS (Thresholds for Gates) ====================
         */
        ScoringConfig.QualityDimensions dims = config.getQualityDimensions();
        ctx.setVariable("arch_impact_score_threshold", dims.getArchitectureImpactScoreThreshold());
        ctx.setVariable("arch_impact_coverage_required", dims.getArchitectureImpactCoverageRequired());
        ctx.setVariable("concurrency_risk_threshold", dims.getConcurrencyRiskThreshold());
        ctx.setVariable("integration_surface_threshold", dims.getIntegrationSurfaceThreshold());
        ctx.setVariable("data_integrity_threshold", dims.getDataIntegrityThreshold());
        ctx.setVariable("security_sensitivity_threshold", dims.getSecuritySensitivityThreshold());
        ctx.setVariable("scalability_impact_threshold", dims.getScalabilityImpactThreshold());
        ctx.setVariable("observability_threshold", dims.getObservabilityThreshold());
        ctx.setVariable("resilience_threshold", dims.getResilienceThreshold());
        ctx.setVariable("performance_threshold", dims.getPerformanceThreshold());

        /**
         * ==================== PROJECT CONTEXT ====================
         */
        ctx.setVariable("project_total_lines", projectLines);
        ctx.setVariable("project_total_files", promptContext.getProjectTotalFiles());
        ctx.setVariable("project_total_methods", promptContext.getProjectTotalMethods());
        ctx.setVariable("code_units_affected", promptContext.getCodeUnitsAffected());

        /**
         * ==================== TAGS ====================
         */
        ctx.setVariable("technical_tags", String.join(", ", promptContext.getTechnicalTags()));
        ctx.setVariable("functional_tags", String.join(", ", promptContext.getFunctionalTags()));

        return ctx;
    }

    @Value
    @Builder
    public static class PromptContext {
        ScoringConfig config;

        @Builder.Default
        List<String> technicalTags = Collections.emptyList();
        @Builder.Default
        List<String> functionalTags = Collections.emptyList();

        @Builder.Default
        long projectTotalLines = 0;
        @Builder.Default
        int projectTotalFiles = 0;
        @Builder.Default
        int projectTotalMethods = 0;
        @Builder.Default
        int codeUnitsAffected = 0;

        public static PromptContext fromConfig(ScoringConfig config) {
            return PromptContext.builder().config(config).build();
        }
        public static PromptContextBuilder withFullContext(ScoringConfig config) {
            return PromptContext.builder().config(config);
        }
    }
}