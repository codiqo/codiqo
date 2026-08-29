package io.codiqo.llm;

import static java.util.function.Predicate.not;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.thymeleaf.context.Context;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.StdDateFormat;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.Value;

public class ThymeleafPromptBuilder implements PromptBuilder {
    private static final String TEMPLATE_SYSTEM_PROMPT = "system-prompt";
    private static final String TEMPLATE_USER_PROMPT = "user-message";
    private static final String TEMPLATE_WEB_SEARCH_RESULTS = "web-search-results";
    private static final String TEMPLATE_PRE_COMPUTED_SCORES = "pre-computed-scores";
    private static final String TEMPLATE_VALIDATION_FEEDBACK = "validation-feedback";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true))
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL))
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Log log;
    private final VolumeScoreCalculator volumeCalculator;
    private final MovedLineDetector movedLineDetector;
    private final DefaultLlmTokenizers tokenizers;

    public ThymeleafPromptBuilder(RunArgs args, Log log) {
        this(args, log, new DefaultLlmTokenizers(log));
    }
    public ThymeleafPromptBuilder(RunArgs args, Log log, DefaultLlmTokenizers tokenizers) {
        this.log = log;
        this.tokenizers = tokenizers;
        volumeCalculator = new VolumeScoreCalculator(args);
        movedLineDetector = new MovedLineDetector(args);
    }
    @Override
    public String buildSystemPrompt(PromptContext context) {
        Context ctx = createContext(context);
        return PromptTemplates.process(TEMPLATE_SYSTEM_PROMPT, ctx);
    }
    @SneakyThrows
    @Override
    public UserMessageResult buildUserMessageWithScores(LlmScoringRequest request, PromptContext context) {
        Context ctx = createContext(context);
        ctx.setVariable("request", request);

        int guidanceTokens = 0;
        if (StringUtils.isNotBlank(context.getConventionGuidance())) {
            guidanceTokens = estimateTokens(context.getArgs().getLlmModel(), context.getConventionGuidance());
        }

        Map<LlmScoringRequest.DuplicationInfo.CloneLocation, String> savedSlices = stripSourceSlices(request);
        Map<LlmScoringRequest.FileChange, String> savedDiffs = annotateDiffs(request);
        Map<LlmScoringRequest.CallerInfo, String> savedCallerBodies = stripCallerBodies(request);
        BudgetedRequest budgeted = enforceCallerBudget(context.getArgs(), request, guidanceTokens);
        restoreCallerBodies(savedCallerBodies);
        restoreDiffs(savedDiffs);
        restoreSourceSlices(savedSlices);

        ctx.setVariable("requestJson", budgeted.getJson());
        ctx.setVariable("moveCandidates", movedLineDetector.detect(request));

        PreComputedScores preComputedScores = volumeCalculator.calculate(
                request,
                context.getProjectTotalStatements(),
                context.getProjectTotalMethods(),
                context.getMethodCapQuantileProd(),
                context.getMethodCapQuantileTest(),
                context.getConstructorCapQuantileProd(),
                context.getConstructorCapQuantileTest());
        logPromptMetrics(context.getArgs().getLlmModel(), request, preComputedScores, budgeted.getJson(), budgeted.getTokens());
        ctx.setVariable("preComputedScores", preComputedScores);
        ctx.setVariable("preComputedScoresSection", buildPreComputedScoresSection(preComputedScores));

        String message = PromptTemplates.process(TEMPLATE_USER_PROMPT, ctx);
        return new UserMessageResult(message, preComputedScores);
    }
    private void logPromptMetrics(String model, LlmScoringRequest request, PreComputedScores scores, String requestJson, int requestTokens) {
        LlmScoringRequest.ChangeSummary cs = request.getChangeSummary();
        log.info("prompt: files=%d, methods=%d, lines=%d (effectiveStmts=%d) | json=%d chars (~%d tokens)",
                cs.getTotalFilesChanged(),
                cs.getCodeBlocksModified() + cs.getCodeBlocksAdded(),
                cs.getTotalLinesChanged(),
                scores.getTotalEffectiveStatements(),
                requestJson.length(),
                requestTokens);

        if (CollectionUtils.isNotEmpty(request.getFileChanges())) {
            List<FileTokens> perFile = new ArrayList<>();
            for (LlmScoringRequest.FileChange file : request.getFileChanges()) {
                int tokens = estimateTokens(model, MAPPER.writeValueAsString(file));
                perFile.add(FileTokens.builder().path(file.getPath()).tokens(tokens).linesChanged(file.getLinesAdded() + file.getLinesDeleted()).build());
            }
            perFile.sort(Comparator.comparingInt(FileTokens::getTokens).reversed());

            log.info("token breakdown by file (largest first):");
            for (FileTokens entry : perFile) {
                log.info("  %s: ~%d tokens (%d lines changed)", entry.getPath(), entry.getTokens(), entry.getLinesChanged());
            }
        }
    }
    @Override
    public String buildWebSearchResults(String query, List<WebSearchResultItem> results) {
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("query", query);
        ctx.setVariable("results", Optional.ofNullable(results).orElse(Collections.emptyList()));
        return PromptTemplates.process(TEMPLATE_WEB_SEARCH_RESULTS, ctx);
    }
    @Override
    public String buildValidationFeedback(FinalScoreCalculator.ValidationReport report) {
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("failures", report.getFailures());
        return PromptTemplates.process(TEMPLATE_VALIDATION_FEEDBACK, ctx);
    }
    @Override
    public int estimateTokens(String model, String text) {
        return tokenizers.estimateTokens(model, text);
    }
    private static Map<LlmScoringRequest.DuplicationInfo.CloneLocation, String> stripSourceSlices(LlmScoringRequest request) {
        Map<LlmScoringRequest.DuplicationInfo.CloneLocation, String> saved = new IdentityHashMap<>();
        if (Objects.nonNull(request.getDuplication())) {
            for (LlmScoringRequest.DuplicationInfo.CloneDetail cd
                    : CollectionUtils.emptyIfNull(request.getDuplication().getCloneDetails())) {
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
    private BudgetedRequest enforceCallerBudget(RunArgs args, LlmScoringRequest request, int reservedTokens) {
        Map<LlmScoringRequest.CodeBlockChange, List<LlmScoringRequest.CallerInfo>> originals = snapshotCallerLists(request);
        int ceiling = args.getLlmMaxCallersPerBlock();
        applyCallerCap(request, ceiling, originals);
        String requestJson = MAPPER.writeValueAsString(request);

        String model = args.getLlmModel();
        // convention guidance shares the user message with the request JSON, so it comes out of the same budget
        int budget = Math.max(0, effectivePromptTokenBudget(args) - reservedTokens);
        int tokens = estimateTokens(model, requestJson);
        if (tokens > budget) {
            int appliedCap = ceiling;
            for (int cap : fibonacciDescentCaps(ceiling)) {
                applyCallerCap(request, cap, originals);
                requestJson = MAPPER.writeValueAsString(request);
                tokens = estimateTokens(model, requestJson);
                appliedCap = cap;
                if (tokens <= budget) {
                    break;
                }
            }
            if (tokens > budget) {
                log.warn("prompt still over budget (%d > %d tokens, %d reserved for agent instructions) after capping callers to %d per block; the diff dominates the prompt and the request may be rejected by the model",
                        tokens,
                        budget,
                        reservedTokens,
                        appliedCap);
            } else {
                log.info("prompt over budget; capped callers to %d per block (~%d tokens, %d reserved for agent instructions)",
                        appliedCap,
                        tokens,
                        reservedTokens);
            }
        }

        restoreCallerLists(originals);
        return new BudgetedRequest(requestJson, tokens);
    }
    private static int effectivePromptTokenBudget(RunArgs args) {
        int numCtx = Optional.ofNullable(args.getLlmNumCtx()).orElse(RunArgs.DEFAULT_NUM_CTX);
        // a window at or below the reserve cannot fit any request; clamp to 0 so callers are fully trimmed rather than leaving a negative budget
        int window = Math.max(0, numCtx - RunArgs.PROMPT_TOKEN_RESERVE);
        return Optional.ofNullable(args.getLlmPromptTokenBudget()).map(cap -> Math.min(cap, window)).orElse(window);
    }
    /**
     * Per-block caller caps to try when the prompt is over budget, descending along the Fibonacci
     * sequence from just below the already-applied ceiling down to 0. The ~1.618 ratio between steps
     * over-trims less than halving would when landing on the largest caller set that still fits the
     * budget. Accumulates in long so an extreme ceiling cannot overflow the sequence into a negative cap.
     */
    private static List<Integer> fibonacciDescentCaps(int ceiling) {
        List<Integer> caps = new ArrayList<>();
        long a = 1;
        long b = 2;
        while (a < ceiling) {
            caps.add((int) a);
            long next = a + b;
            a = b;
            b = next;
        }
        Collections.reverse(caps);
        caps.add(0);
        return caps;
    }
    private static Map<LlmScoringRequest.CallerInfo, String> stripCallerBodies(LlmScoringRequest request) {
        Map<LlmScoringRequest.CallerInfo, String> saved = new IdentityHashMap<>();
        if (CollectionUtils.isNotEmpty(request.getCodeBlockChanges())) {
            for (LlmScoringRequest.CodeBlockChange block : request.getCodeBlockChanges()) {
                if (CollectionUtils.isNotEmpty(block.getCallers())) {
                    for (LlmScoringRequest.CallerInfo caller : block.getCallers()) {
                        if (Objects.nonNull(caller.getCallerBody())) {
                            saved.put(caller, caller.getCallerBody());
                            caller.setCallerBody(null);
                        }
                    }
                }
            }
        }
        return saved;
    }
    private static void restoreCallerBodies(Map<LlmScoringRequest.CallerInfo, String> saved) {
        saved.forEach(LlmScoringRequest.CallerInfo::setCallerBody);
    }
    private static Map<LlmScoringRequest.CodeBlockChange, List<LlmScoringRequest.CallerInfo>> snapshotCallerLists(LlmScoringRequest request) {
        Map<LlmScoringRequest.CodeBlockChange, List<LlmScoringRequest.CallerInfo>> saved = new IdentityHashMap<>();
        if (CollectionUtils.isNotEmpty(request.getCodeBlockChanges())) {
            for (LlmScoringRequest.CodeBlockChange block : request.getCodeBlockChanges()) {
                if (CollectionUtils.isNotEmpty(block.getCallers())) {
                    saved.put(block, block.getCallers());
                }
            }
        }
        return saved;
    }
    private static void applyCallerCap(LlmScoringRequest request, int cap, Map<LlmScoringRequest.CodeBlockChange, List<LlmScoringRequest.CallerInfo>> originals) {
        originals.forEach((block, full) -> {
            if (full.size() <= cap) {
                block.setCallers(full);
                block.setOmittedCallerCount(0);
                block.setOmittedProductionCallerCount(0);
                return;
            }

            List<LlmScoringRequest.CallerInfo> ranked = new ArrayList<>(full);
            ranked.sort(callerPriority(full));
            List<LlmScoringRequest.CallerInfo> kept = new ArrayList<>(ranked.subList(0, cap));
            long fullProduction = full.stream().filter(not(LlmScoringRequest.CallerInfo::isTestCaller)).count();
            long keptProduction = kept.stream().filter(not(LlmScoringRequest.CallerInfo::isTestCaller)).count();

            block.setCallers(kept);
            block.setOmittedCallerCount(full.size() - cap);
            block.setOmittedProductionCallerCount((int) (fullProduction - keptProduction));
        });
    }
    /**
     * Retain the highest-signal callers first when trimming to fit the token budget: production before test,
     * then callers from whichever classes contribute the most of them, then higher call-site coupling,
     * non-deprecated before deprecated.
     *
     * <p>Test callers are ranked last rather than dropped: they only take a slot once every production
     * caller has one, and a change to a test file has nothing but test callers, so excluding them would
     * leave those blocks looking uncalled. Each entry carries its own {@code isTestCaller} flag, so the
     * model can tell which kind it is reading.
     *
     * <p>The class-concentration term keeps a truncated list legible — ranking on coupling alone leaves the
     * survivors scattered one-per-class, which tells the model only that the changed code is used, where
     * keeping whole classes together shows how a caller uses it. It sits BELOW callSiteCount deliberately:
     * promoting it above cost the single most-coupled caller its slot whenever a class of weakly-coupled
     * callers outnumbered it, which is the opposite of what the cap is for. Grouping is by file rather than
     * by parsing the signature, so it carries to languages where a file is not one class.
     */
    private static Comparator<LlmScoringRequest.CallerInfo> callerPriority(List<LlmScoringRequest.CallerInfo> callers) {
        Map<String, Long> perClass = callers.stream()
                .collect(Collectors.groupingBy(caller -> StringUtils.defaultString(caller.getFile()), Collectors.counting()));

        return Comparator.comparing(LlmScoringRequest.CallerInfo::isTestCaller)
                .thenComparing(Comparator.comparingInt(LlmScoringRequest.CallerInfo::getCallSiteCount).reversed())
                .thenComparing(Comparator.comparingLong(
                        (LlmScoringRequest.CallerInfo caller) -> perClass.getOrDefault(StringUtils.defaultString(caller.getFile()), 0L)).reversed())
                .thenComparing(LlmScoringRequest.CallerInfo::isDeprecated);
    }
    private static void restoreCallerLists(Map<LlmScoringRequest.CodeBlockChange, List<LlmScoringRequest.CallerInfo>> originals) {
        originals.forEach((block, full) -> {
            block.setCallers(full);
            block.setOmittedCallerCount(0);
            block.setOmittedProductionCallerCount(0);
        });
    }
    private static Map<LlmScoringRequest.FileChange, String> annotateDiffs(LlmScoringRequest request) {
        Map<LlmScoringRequest.FileChange, String> saved = new IdentityHashMap<>();
        if (CollectionUtils.isNotEmpty(request.getFileChanges())) {
            for (LlmScoringRequest.FileChange fc : request.getFileChanges()) {
                if (Objects.nonNull(fc.getDiff())) {
                    saved.put(fc, fc.getDiff());
                    fc.setDiff(UnifiedDiffLines.parse(fc.getDiff(), fc.getLineFilter()).getAnnotated());
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
        return PromptTemplates.process(TEMPLATE_PRE_COMPUTED_SCORES, ctx);
    }
    private static Context createContext(PromptContext promptContext) {
        Context ctx = new Context(Locale.ENGLISH);
        RunArgs args = promptContext.getArgs();
        String conventionGuidance = promptContext.getConventionGuidance();

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
        ctx.setVariable("test_code_penalty_weight", String.format("%.2f", args.getTestCodePenaltyWeight()));
        ctx.setVariable("test_code_penalty_percent", Math.round(args.getTestCodePenaltyWeight() * 100));
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
        ctx.setVariable("tags_vocabulary_cap", promptContext.getTagsVocabularyCap());
        ctx.setVariable("web_search_enabled", args.isLlmEnableWebSearchTool());
        ctx.setVariable("convention_guidance", conventionGuidance);
        ctx.setVariable("convention_guidance_present", StringUtils.isNotBlank(conventionGuidance));
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

    @Value
    private static class BudgetedRequest {
        String json;
        int tokens;
    }
}
