package io.codiqo.llm;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.math3.util.Precision;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.CoverageAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.CpdAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.FindingSeverity;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import io.codiqo.llm.schema.LlmScoringResponse.QualityGateAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisFinding;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisImpact;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisReview;

/**
 * The quality multiplier and the architecture bonus inputs, computed from the rules the scoring prompt states rather
 * than asked of the model: the pre-computed CPD and static-analysis impacts, the coverage band of the changed lines,
 * a fixed price per architecture finding, and the quality gates; and the tool findings placed by the diff. Only the
 * findings and the dimension scores are judgment, and they come from the local assessment already on the response.
 *
 * <p>Where the prompt left the number to the model (the quality factor and the bonus impact), the values come from
 * {@link RunArgs}, and their defaults are what the model actually gave on scored commits, so the bonus a commit earns
 * does not move when the prompt is skipped.
 *
 * <p>{@link FinalScoreCalculator} still clamps the multiplier and turns impact and quality factor into bonus points.
 */
public class QualityRules {
    /** the dimensions whose gate is judgment (is there a fitting test?); architecture's is coverage, testing coverage has none */
    private static final List<Gate> JUDGMENT_GATES = List.of(
            new Gate("concurrencyRisk", QualityDimensions::getConcurrencyRisk, RunArgs::getConcurrencyRiskThreshold),
            new Gate("integrationSurface", QualityDimensions::getIntegrationSurface, RunArgs::getIntegrationSurfaceThreshold),
            new Gate("dataIntegrity", QualityDimensions::getDataIntegrity, RunArgs::getDataIntegrityThreshold),
            new Gate("securitySensitivity", QualityDimensions::getSecuritySensitivity, RunArgs::getSecuritySensitivityThreshold),
            new Gate("scalabilityImpact", QualityDimensions::getScalabilityImpact, RunArgs::getScalabilityImpactThreshold),
            new Gate("observability", QualityDimensions::getObservability, RunArgs::getObservabilityThreshold),
            new Gate("resilience", QualityDimensions::getResilience, RunArgs::getResilienceThreshold),
            new Gate("performance", QualityDimensions::getPerformance, RunArgs::getPerformanceThreshold));

    private final RunArgs args;

    public QualityRules(RunArgs args) {
        this.args = Objects.requireNonNull(args);
    }
    /**
     * Replaces the response's quality multiplier and architecture bonus inputs. Architecture findings and failed
     * judgment gates already on the response (from a local agent turn) are kept and priced; everything else is
     * recomputed. The bugs are the local review's, which are stored with the submission rather than on the response.
     */
    public void apply(LlmScoringResponse response, PreComputedScores preComputed, LlmScoringRequest request, LlmScoringResponse.Bugs bugs) {
        Optional<Double> coverage = changedLineCoverage(request);
        QualityMultiplier previous = Optional.ofNullable(response.getQualityMultiplier()).orElseGet(QualityMultiplier::new);

        /**
         * a commit that failed the build carries no coverage, duplication or diagnostics, so no bonus may be earned on
         * their absence; the prompt's degraded mode says the same, and the model followed it
         */
        boolean degraded = Objects.nonNull(request.getBuildFailure());
        CpdAnalysis cpd = CpdAnalysis.builder().impact(penaltyOnlyWhen(degraded, preComputed.getCpdRecommendedImpact())).build();
        StaticAnalysisImpact staticAnalysis = StaticAnalysisImpact.builder()
                .spotbugsIssuesInChanges(preComputed.getStaticAnalysisIntroducedCount())
                .impact(penaltyOnlyWhen(degraded, preComputed.getStaticAnalysisRecommendedImpact()))
                .build();
        CoverageAnalysis coverageAnalysis = CoverageAnalysis.builder()
                .coveragePercent(coverage.orElse(0.0))
                .impact(penaltyOnlyWhen(degraded, coverage.map(this::coverageImpact).orElse(0.0)))
                .build();
        ArchitectureAnalysis architecture = priceArchitecture(previous.getArchitectureAnalysis());
        QualityGateAnalysis gates = qualityGates(response.getQualityDimensions(), previous.getQualityGateAnalysis(), coverage);

        double multiplier = 1.0 + cpd.getImpact() + staticAnalysis.getImpact() + coverageAnalysis.getImpact() + architecture.getPenaltyImpact() + gates.getImpact();
        response.setQualityMultiplier(QualityMultiplier.builder()
                .cpdAnalysis(cpd)
                .staticAnalysis(staticAnalysis)
                .coverageAnalysis(coverageAnalysis)
                .architectureAnalysis(architecture)
                .qualityGateAnalysis(gates)
                .finalMultiplier(Precision.round(multiplier, RunArgs.SCORE_PRECISION))
                .build());

        int impact = architectureImpact(response.getQualityDimensions());
        double qualityFactor = 0.0;
        if (impact > 0) {
            /** The discount is applied to the factor because the impact score is a whole number. */
            qualityFactor = qualityFactor(coverage, bugs, degraded) * (impact - args.getArchitectureBonusImpactDiscount()) / impact;
        }
        response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                .architectureImpactScore(impact)
                .qualityFactor(Precision.round(qualityFactor, RunArgs.SCORE_PRECISION + 1))
                .build());

        StaticAnalysisReview placed = staticAnalysisReview(request);
        if (Objects.nonNull(response.getStaticAnalysisReview())) {
            response.setStaticAnalysisReview(withUnjudged(response.getStaticAnalysisReview(), placed));
        } else {
            response.setStaticAnalysisReview(placed);
        }
    }
    private ArchitectureAnalysis priceArchitecture(ArchitectureAnalysis findings) {
        List<String> solid = Lists.newArrayList();
        List<String> issues = Lists.newArrayList();
        if (Objects.nonNull(findings)) {
            solid.addAll(CollectionUtils.emptyIfNull(findings.getSolidViolations()));
            issues.addAll(CollectionUtils.emptyIfNull(findings.getArchitectureIssues()));
        }

        /** The model priced a lone architecture issue at 0.024 on average, nearest the SOLID price, so every finding costs that. */
        double penalty = (solid.size() + issues.size()) * args.getArchitectureSolidPenalty();
        return ArchitectureAnalysis.builder()
                .solidViolations(solid)
                .architectureIssues(issues)
                .penaltyImpact(Precision.round(Math.max(-args.getArchitecturePenaltyCap(), penalty), RunArgs.SCORE_PRECISION))
                .build();
    }
    /**
     * The architecture gate is purely coverage-based and decided here. The other gates need to know whether a fitting
     * test exists, which is judgment: the review's verdict counts for a dimension that scores at or above its
     * threshold, and a gate it did not judge is met, as the prompt says to decide when in doubt.
     */
    private QualityGateAnalysis qualityGates(QualityDimensions dimensions, QualityGateAnalysis reported, Optional<Double> coverage) {
        List<String> failed = Lists.newArrayList();
        if (Objects.nonNull(reported)) {
            failed.addAll(CollectionUtils.emptyIfNull(reported.getFailedGates()));
        }

        if (Objects.nonNull(dimensions)) {
            for (Gate gate : JUDGMENT_GATES) {
                DimensionScore dimension = gate.getDimension().apply(dimensions);
                /**
                 * The {@code &&} must stay short-circuiting, not become BooleanUtils.and: a dimension the review left
                 * out has no score and no verdict, and isFailed would read the verdict of a dimension that is null.
                 */
                if (score(dimension).filter(value -> value >= gate.getThreshold().applyAsInt(args)).isPresent() && isFailed(dimension)) {
                    failed.add(gate.getName() + " - " + StringUtils.defaultString(dimension.getRationale()));
                }
            }
            if (score(dimensions.getArchitectureImpact()).filter(architecture -> architecture >= args.getArchitectureImpactScoreThreshold()).isPresent()) {
                coverage.filter(percent -> percent < args.getArchitectureImpactCoverageRequired())
                        .ifPresent(percent -> failed.add(String.format(Locale.ROOT, "architectureImpact - changed-line coverage %.0f%% is below %d%%", percent,
                                args.getArchitectureImpactCoverageRequired())));
            }
        }

        double penalty = Math.max(-args.getQualityGatePenaltyCap(), failed.size() * args.getQualityGateFailurePenalty());
        return QualityGateAnalysis.builder().failedGates(failed).impact(Precision.round(penalty, RunArgs.SCORE_PRECISION)).build();
    }
    private double coverageImpact(double percent) {
        double toReturn;
        if (percent >= args.getCoverageImpactExcellentMin()) {
            toReturn = args.getCoverageExcellentBonus();
        } else if (percent >= args.getCoverageImpactGoodMin()) {
            toReturn = args.getCoverageGoodBonus();
        } else if (percent >= args.getCoverageImpactAcceptableMin()) {
            toReturn = 0.0;
        } else if (percent >= args.getCoverageImpactLowMin()) {
            toReturn = args.getCoverageLowPenalty();
        } else if (percent >= args.getCoverageImpactPoorMin()) {
            toReturn = args.getCoveragePoorPenalty();
        } else {
            toReturn = args.getCoverageTerriblePenalty();
        }
        return toReturn;
    }
    /**
     * The prompt's guidelines name bands, not values; the factor per changed-line coverage band, and what a bug does
     * to it, come from {@link RunArgs#getQualityFactorWellCovered()} and its siblings. A blocking bug sets the factor
     * rather than zeroing it, and any other bug caps it, as the model did.
     *
     * <p>A commit that does not build is priced as one with a blocking bug: the prompt read the compiler output and reported
     * it as one (failed builds with a bonus averaged 0.40 on production), and the local review never sees the build.
     */
    private double qualityFactor(Optional<Double> coverage, LlmScoringResponse.Bugs bugs, boolean degraded) {
        double toReturn = args.getQualityFactorUnmeasured();
        if (coverage.isPresent()) {
            double percent = coverage.get();
            if (percent >= args.getCoverageImpactGoodMin()) {
                toReturn = args.getQualityFactorWellCovered();
            } else if (percent >= args.getCoverageImpactLowMin()) {
                toReturn = args.getQualityFactorLowCoverage();
            } else if (percent >= args.getCoverageImpactPoorMin()) {
                toReturn = args.getQualityFactorPoorCoverage();
            } else if (percent > 0.0) {
                toReturn = args.getQualityFactorTerribleCoverage();
            } else {
                toReturn = args.getQualityFactorUncovered();
            }
        }

        if (BooleanUtils.or(new boolean[] { degraded, CollectionUtils.isNotEmpty(bugs.getBlocking()) })) {
            toReturn = args.getQualityFactorBlockingBug();
        } else if (BooleanUtils.or(new boolean[] { CollectionUtils.isNotEmpty(bugs.getMajor()), CollectionUtils.isNotEmpty(bugs.getMinor()) })) {
            toReturn = Math.min(toReturn, args.getQualityFactorWithBugsMax());
        }
        return toReturn;
    }
    /**
     * The tool findings on the changed code blocks, placed by the server's {@code introducedInCommit} as the prompt was
     * told to place them, at the tool's own severity. Telling a false positive and suggesting a fix is judgment that
     * needs the code read after the build: the local triage does it for the findings on added lines, and its
     * placements win (see {@link #withUnjudged}); here nothing is called a false positive and no fix is offered. No
     * tool severity is recorded either: it names the level before a re-review, and nothing re-reviewed these, so the
     * dashboard does not show them as verified. Only PMD and SpotBugs have lists; the review never covered other tools.
     */
    private static StaticAnalysisReview staticAnalysisReview(LlmScoringRequest request) {
        StaticAnalysisReview toReturn = new StaticAnalysisReview();
        Set<FindingKey> seen = Sets.newHashSet();
        for (LlmScoringRequest.CodeBlockChange block : CollectionUtils.emptyIfNull(request.getCodeBlockChanges())) {
            for (LlmScoringRequest.DiagnosticInfo diagnostic : CollectionUtils.emptyIfNull(block.getDiagnostics())) {
                List<StaticAnalysisFinding> list = null;
                if (DiagnosticModel.ToolEnum.PMD.getValue().equals(diagnostic.getTool())) {
                    list = diagnostic.isIntroducedInCommit() ? toReturn.getPmdInChangedLines() : toReturn.getPmdPreExisting();
                } else if (DiagnosticModel.ToolEnum.SPOTBUGS.getValue().equals(diagnostic.getTool())) {
                    list = diagnostic.isIntroducedInCommit() ? toReturn.getSpotbugsInChangedLines() : toReturn.getSpotbugsPreExisting();
                }
                /** a diagnostic spanning several blocks is attached to each of them */
                if (Objects.nonNull(list) && seen.add(FindingKey.of(diagnostic.getTool(), diagnostic.getRuleId(), block.getFile(), diagnostic.getStartLine()))) {
                    FindingSeverity severity = severity(diagnostic.getSeverity());
                    list.add(StaticAnalysisFinding.builder()
                            .rule(diagnostic.getRuleId())
                            .file(block.getFile())
                            .line(diagnostic.getStartLine())
                            .assessment(diagnostic.getMessage())
                            .severity(severity)
                            .build());
                }
            }
        }
        return toReturn;
    }
    /**
     * A judged review keeps every placement it made, and each finding it did not judge is added where the rules put it:
     * the local triage judges only the findings on added lines, so the pre-existing ones would otherwise go unlisted.
     * Built on a copy: a list the JSON left out reads null, and a list the review was given may not be modifiable.
     */
    private static StaticAnalysisReview withUnjudged(StaticAnalysisReview judged, StaticAnalysisReview placed) {
        StaticAnalysisReview toReturn = StaticAnalysisReview.builder()
                .pmdInChangedLines(Lists.newArrayList(CollectionUtils.emptyIfNull(judged.getPmdInChangedLines())))
                .pmdPreExisting(Lists.newArrayList(CollectionUtils.emptyIfNull(judged.getPmdPreExisting())))
                .pmdFalsePositives(Lists.newArrayList(CollectionUtils.emptyIfNull(judged.getPmdFalsePositives())))
                .spotbugsInChangedLines(Lists.newArrayList(CollectionUtils.emptyIfNull(judged.getSpotbugsInChangedLines())))
                .spotbugsPreExisting(Lists.newArrayList(CollectionUtils.emptyIfNull(judged.getSpotbugsPreExisting())))
                .spotbugsFalsePositives(Lists.newArrayList(CollectionUtils.emptyIfNull(judged.getSpotbugsFalsePositives())))
                .build();
        for (StaticAnalysisLists.ToolLists tool : StaticAnalysisLists.all()) {
            Set<Triple<String, String, Integer>> seen = Sets.newHashSet();
            for (Function<StaticAnalysisReview, List<StaticAnalysisFinding>> list : tool.lists()) {
                for (StaticAnalysisFinding finding : list.apply(toReturn)) {
                    seen.add(findingKey(finding));
                }
            }
            for (Function<StaticAnalysisReview, List<StaticAnalysisFinding>> list : tool.lists()) {
                for (StaticAnalysisFinding finding : list.apply(placed)) {
                    if (seen.add(findingKey(finding))) {
                        list.apply(toReturn).add(finding);
                    }
                }
            }
        }
        return toReturn;
    }
    private static Triple<String, String, Integer> findingKey(StaticAnalysisFinding finding) {
        return Triple.of(finding.getRule(), finding.getFile(), finding.getLine());
    }
    private static FindingSeverity severity(LlmScoringRequest.DiagnosticSeverity severity) {
        if (Objects.isNull(severity)) {
            return FindingSeverity.INFO;
        }
        switch (severity) {
            case ERROR:
                return FindingSeverity.ERROR;
            case WARNING:
                return FindingSeverity.WARNING;
            case INFO:
            case NOTE:
            case NONE:
                return FindingSeverity.INFO;
            default:
                throw new IllegalArgumentException("unknown diagnostic severity: " + severity);
        }
    }
    private static int architectureImpact(QualityDimensions dimensions) {
        int toReturn = 0;
        if (Objects.nonNull(dimensions)) {
            toReturn = score(dimensions.getArchitectureImpact()).orElse(0);
        }
        return RunArgs.SCORE_SCALE.fit(toReturn);
    }
    private static double penaltyOnlyWhen(boolean degraded, double impact) {
        double toReturn = impact;
        if (degraded) {
            toReturn = Math.min(0.0, impact);
        }
        return toReturn;
    }
    private static Optional<Double> changedLineCoverage(LlmScoringRequest request) {
        Optional<Double> toReturn = Optional.empty();
        if (Objects.nonNull(request.getCoverage())) {
            toReturn = Optional.ofNullable(request.getCoverage().getChangedLineCoverage());
        }
        return toReturn;
    }
    private static boolean isFailed(DimensionScore dimension) {
        return BooleanUtils.negate(dimension.isQualityGateMet());
    }
    private static Optional<Integer> score(DimensionScore dimension) {
        return Optional.ofNullable(dimension).map(DimensionScore::getScore);
    }

    private static final class Gate extends ImmutableTriple<String, Function<QualityDimensions, DimensionScore>, ToIntFunction<RunArgs>> {
        private Gate(String name, Function<QualityDimensions, DimensionScore> dimension, ToIntFunction<RunArgs> threshold) {
            super(name, dimension, threshold);
        }
        private String getName() {
            return getLeft();
        }
        private Function<QualityDimensions, DimensionScore> getDimension() {
            return getMiddle();
        }
        private ToIntFunction<RunArgs> getThreshold() {
            return getRight();
        }
    }
}
