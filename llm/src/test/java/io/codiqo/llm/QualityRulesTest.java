package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CoverageInfo;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.Bug;
import io.codiqo.llm.schema.LlmScoringResponse.Bugs;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.FindingSeverity;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import io.codiqo.llm.schema.LlmScoringResponse.QualityGateAnalysis;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisFinding;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisReview;
import tools.jackson.databind.json.JsonMapper;

class QualityRulesTest {
    private static final double DELTA = 1e-9;
    private static final Bugs NO_BUGS = Bugs.builder().build();

    private final RunArgs args = new RunArgs();
    private final QualityRules rules = new QualityRules(args);

    @Test
    void sumsThePrecomputedImpactsAndTheCoverageBand() {
        LlmScoringResponse response = new LlmScoringResponse();
        rules.apply(response, preComputed(-0.05, -0.02), withCoverage(92.0), NO_BUGS);

        QualityMultiplier multiplier = response.getQualityMultiplier();
        assertEquals(-0.05, multiplier.getCpdAnalysis().getImpact(), DELTA);
        assertEquals(-0.02, multiplier.getStaticAnalysis().getImpact(), DELTA);
        assertEquals(args.getCoverageExcellentBonus(), multiplier.getCoverageAnalysis().getImpact(), DELTA);
        assertEquals(1.0 - 0.05 - 0.02 + args.getCoverageExcellentBonus(), multiplier.getFinalMultiplier(), DELTA);
    }
    @Test
    void walksEveryCoverageBand() {
        assertEquals(args.getCoverageGoodBonus(), coverageImpact(85.0), DELTA);
        assertEquals(0.0, coverageImpact(70.0), DELTA);
        assertEquals(args.getCoverageLowPenalty(), coverageImpact(65.0), DELTA);
        assertEquals(args.getCoveragePoorPenalty(), coverageImpact(50.0), DELTA);
        assertEquals(args.getCoverageTerriblePenalty(), coverageImpact(10.0), DELTA);
    }
    @Test
    void changesWithoutExecutableLinesAreNeutral() {
        LlmScoringResponse response = withDimensions(9);
        rules.apply(response, preComputed(0.0, 0.0), LlmScoringRequest.builder().coverage(CoverageInfo.builder().build()).build(), NO_BUGS);

        assertEquals(0.0, response.getQualityMultiplier().getCoverageAnalysis().getImpact(), DELTA);
        assertTrue(response.getQualityMultiplier().getQualityGateAnalysis().getFailedGates().isEmpty(), "unknown coverage meets the gate");
        assertEquals(0.75 * 8.5 / 9, response.getArchitectureEffortBonus().getQualityFactor(), 1e-3, "unmeasured, less the half-point bonus discount");
        assertEquals(9, response.getArchitectureEffortBonus().getArchitectureImpactScore());
    }
    @Test
    void failsTheArchitectureGateOnLowCoverageOnly() {
        LlmScoringResponse high = withDimensions(args.getArchitectureImpactScoreThreshold());
        rules.apply(high, preComputed(0.0, 0.0), withCoverage(args.getArchitectureImpactCoverageRequired() - 1.0), NO_BUGS);
        assertEquals(1, high.getQualityMultiplier().getQualityGateAnalysis().getFailedGates().size());
        assertEquals(args.getQualityGateFailurePenalty(), high.getQualityMultiplier().getQualityGateAnalysis().getImpact(), DELTA);

        LlmScoringResponse covered = withDimensions(args.getArchitectureImpactScoreThreshold());
        rules.apply(covered, preComputed(0.0, 0.0), withCoverage(args.getArchitectureImpactCoverageRequired()), NO_BUGS);
        assertTrue(covered.getQualityMultiplier().getQualityGateAnalysis().getFailedGates().isEmpty());

        LlmScoringResponse low = withDimensions(args.getArchitectureImpactScoreThreshold() - 1);
        rules.apply(low, preComputed(0.0, 0.0), withCoverage(0.0), NO_BUGS);
        assertTrue(low.getQualityMultiplier().getQualityGateAnalysis().getFailedGates().isEmpty(), "below the threshold the gate does not apply");
    }
    @Test
    void pricesReportedFindingsUpToTheirCaps() {
        LlmScoringResponse response = withDimensions(2);
        response.setQualityMultiplier(QualityMultiplier.builder()
                .architectureAnalysis(ArchitectureAnalysis.builder().solidViolations(List.of("[S] a", "[D] b")).architectureIssues(List.of("god class")).build())
                .qualityGateAnalysis(QualityGateAnalysis.builder().failedGates(List.of("concurrencyRisk - a", "resilience - b", "dataIntegrity - c", "performance - d")).build())
                .build());
        rules.apply(response, preComputed(0.0, 0.0), withCoverage(75.0), NO_BUGS);

        QualityMultiplier multiplier = response.getQualityMultiplier();
        assertEquals(3 * args.getArchitectureSolidPenalty(), multiplier.getArchitectureAnalysis().getPenaltyImpact(), DELTA);
        assertEquals(-args.getQualityGatePenaltyCap(), multiplier.getQualityGateAnalysis().getImpact(), DELTA);
        assertEquals(4, multiplier.getQualityGateAnalysis().getFailedGates().size());
    }
    @Test
    void qualityFactorFollowsTheModelPerCoverageBand() {
        // impact 5 carries the half-point discount: 4.5 / 5
        assertEquals(args.getQualityFactorWellCovered() * 0.9, qualityFactor(85.0, Bugs.builder().build()), 1e-3);
        assertEquals(args.getQualityFactorLowCoverage() * 0.9, qualityFactor(65.0, Bugs.builder().build()), 1e-3);
        assertEquals(args.getQualityFactorPoorCoverage() * 0.9, qualityFactor(55.0, Bugs.builder().build()), 1e-3);
        assertEquals(args.getQualityFactorTerribleCoverage() * 0.9, qualityFactor(20.0, Bugs.builder().build()), 1e-3);
        assertEquals(args.getQualityFactorUncovered() * 0.9, qualityFactor(0.0, Bugs.builder().build()), 1e-3);
    }
    @Test
    void bugsCutTheQualityFactorWithoutZeroingIt() {
        Bugs blocking = Bugs.builder().blocking(List.of(Bug.builder().title("npe").build())).build();
        Bugs major = Bugs.builder().major(List.of(Bug.builder().title("leak").build())).build();
        assertEquals(args.getQualityFactorBlockingBug() * 0.9, qualityFactor(100.0, blocking), 1e-3);
        assertEquals(args.getQualityFactorWithBugsMax() * 0.9, qualityFactor(100.0, major), 1e-3);
        assertEquals(args.getQualityFactorTerribleCoverage() * 0.9, qualityFactor(20.0, major), 1e-3, "the bug cap never raises a lower factor");
    }
    /** the local review never sees the build: a failed one is priced as the prompt priced the compiler error it read */
    @Test
    void aFailedBuildIsPricedAsABlockingBug() {
        LlmScoringResponse response = withDimensions(5);
        LlmScoringRequest request = withCoverage(100.0);
        request.setBuildFailure(LlmScoringRequest.BuildFailureInfo.builder().reason("[ERROR] COMPILATION ERROR").build());

        rules.apply(response, preComputed(0.0, 0.0), request, NO_BUGS);

        assertEquals(args.getQualityFactorBlockingBug() * 0.9, response.getArchitectureEffortBonus().getQualityFactor(), 1e-3);
    }
    @Test
    void noArchitectureImpactEarnsNoFactor() {
        LlmScoringResponse response = withDimensions(0);
        rules.apply(response, preComputed(0.0, 0.0), withCoverage(95.0), NO_BUGS);
        assertEquals(0.0, response.getArchitectureEffortBonus().getQualityFactor(), DELTA);
    }
    @Test
    void aFailedBuildEarnsNoBonusOnMissingData() {
        LlmScoringRequest request = LlmScoringRequest.builder().buildFailure(LlmScoringRequest.BuildFailureInfo.builder().build()).build();
        LlmScoringResponse response = new LlmScoringResponse();
        rules.apply(response, preComputed(0.05, -0.04), request, NO_BUGS);

        assertEquals(0.0, response.getQualityMultiplier().getCpdAnalysis().getImpact(), DELTA, "the clean bonus is not evidence");
        assertEquals(-0.04, response.getQualityMultiplier().getStaticAnalysis().getImpact(), DELTA, "a penalty still applies");
    }
    /** a judgment gate counts when its dimension reaches the threshold and the review found the fitting test missing */
    @Test
    void aJudgmentGateTheReviewFailedCostsAtOrAboveItsThreshold() {
        LlmScoringResponse response = withDimensions(2);
        response.getQualityDimensions().setConcurrencyRisk(DimensionScore.builder()
                .score(args.getConcurrencyRiskThreshold()).rationale("no test drives the new writer pool concurrently").qualityGateMet(false).build());
        response.getQualityDimensions().setIntegrationSurface(DimensionScore.builder()
                .score(args.getIntegrationSurfaceThreshold() - 1).rationale("new endpoint, no contract test").qualityGateMet(false).build());
        rules.apply(response, preComputed(0.0, 0.0), withCoverage(85.0), NO_BUGS);

        QualityGateAnalysis gates = response.getQualityMultiplier().getQualityGateAnalysis();
        assertEquals(List.of("concurrencyRisk - no test drives the new writer pool concurrently"), gates.getFailedGates(), "integration surface is below its threshold");
        assertEquals(args.getQualityGateFailurePenalty(), gates.getImpact(), DELTA);
    }
    /** a review that leaves the flag out has not failed the gate */
    @Test
    void aGateTheAnswerLeavesOutIsMet() {
        JsonMapper mapper = JsonMapper.builder().build();
        DimensionScore parsed = mapper.treeToValue(mapper.createObjectNode().put("score", 9).put("rationale", "shared state"), DimensionScore.class);
        assertTrue(parsed.isQualityGateMet());
    }
    private double coverageImpact(double percent) {
        LlmScoringResponse response = new LlmScoringResponse();
        rules.apply(response, preComputed(0.0, 0.0), withCoverage(percent), NO_BUGS);
        return response.getQualityMultiplier().getCoverageAnalysis().getImpact();
    }
    private double qualityFactor(double percent, Bugs bugs) {
        LlmScoringResponse response = withDimensions(5);
        rules.apply(response, preComputed(0.0, 0.0), withCoverage(percent), bugs);
        return response.getArchitectureEffortBonus().getQualityFactor();
    }
    /** placed by the diff at the tool's severity, each finding once though it is attached to every block it spans */
    @Test
    void theToolFindingsArePlacedByTheDiff() {
        LlmScoringRequest.DiagnosticInfo introduced = LlmScoringRequest.DiagnosticInfo.builder()
                .tool("spotbugs").ruleId("NP_NULL_ON_SOME_PATH").message("possible null").severity(LlmScoringRequest.DiagnosticSeverity.ERROR)
                .startLine(12).endLine(12).introducedInCommit(true).build();
        LlmScoringRequest.DiagnosticInfo preExisting = LlmScoringRequest.DiagnosticInfo.builder()
                .tool("pmd").ruleId("GodClass").severity(LlmScoringRequest.DiagnosticSeverity.NOTE).startLine(1).endLine(80).build();
        LlmScoringRequest.DiagnosticInfo otherTool = LlmScoringRequest.DiagnosticInfo.builder().tool("errorprone").ruleId("MissingOverride").startLine(5).build();
        LlmScoringRequest request = withCoverage(90.0);
        request.setCodeBlockChanges(List.of(
                LlmScoringRequest.CodeBlockChange.builder().file("a/Service.java").diagnostics(List.of(introduced, preExisting, otherTool)).build(),
                LlmScoringRequest.CodeBlockChange.builder().file("a/Service.java").diagnostics(List.of(preExisting)).build()));
        LlmScoringResponse response = withDimensions(0);

        rules.apply(response, preComputed(0.0, 0.0), request, NO_BUGS);

        StaticAnalysisReview review = response.getStaticAnalysisReview();
        assertEquals(1, review.getSpotbugsInChangedLines().size());
        StaticAnalysisFinding finding = review.getSpotbugsInChangedLines().get(0);
        assertEquals("a/Service.java", finding.getFile());
        assertEquals(12, finding.getLine());
        assertEquals(FindingSeverity.ERROR, finding.getSeverity());
        assertNull(finding.getToolSeverity(), "nothing re-reviewed it, so it is not shown as verified");
        assertEquals(1, review.getPmdPreExisting().size(), "a finding attached to two blocks is listed once");
        assertEquals(FindingSeverity.INFO, review.getPmdPreExisting().get(0).getSeverity());
        assertTrue(review.getPmdInChangedLines().isEmpty());
        assertTrue(review.getPmdFalsePositives().isEmpty(), "telling a false positive is judgment the rules do not make");
    }
    /** the triage's placements are kept, and what it did not judge is added where the rules put it */
    @Test
    void aJudgedStaticAnalysisReviewKeepsItsVerdictsAndGetsTheRest() {
        LlmScoringRequest.DiagnosticInfo introduced = LlmScoringRequest.DiagnosticInfo.builder()
                .tool("spotbugs").ruleId("NP_NULL_ON_SOME_PATH").severity(LlmScoringRequest.DiagnosticSeverity.ERROR)
                .startLine(12).endLine(12).introducedInCommit(true).build();
        LlmScoringRequest.DiagnosticInfo preExisting = LlmScoringRequest.DiagnosticInfo.builder()
                .tool("pmd").ruleId("GodClass").severity(LlmScoringRequest.DiagnosticSeverity.NOTE).startLine(1).endLine(80).build();
        LlmScoringRequest request = withCoverage(90.0);
        request.setCodeBlockChanges(List.of(LlmScoringRequest.CodeBlockChange.builder().file("a/Service.java").diagnostics(List.of(introduced, preExisting)).build()));

        /** as Jackson reads a triaged review: the lists it did not write are null, the one it wrote is fixed */
        StaticAnalysisReview judged = new StaticAnalysisReview(null, null, null, null, null, List.of(StaticAnalysisFinding.builder()
                .rule("NP_NULL_ON_SOME_PATH").file("a/Service.java").line(12).assessment("the value is checked one line above")
                .severity(FindingSeverity.INFO).toolSeverity(FindingSeverity.ERROR).build()));
        LlmScoringResponse response = withDimensions(0);
        response.setStaticAnalysisReview(judged);

        rules.apply(response, preComputed(0.0, 0.0), request, NO_BUGS);

        StaticAnalysisReview review = response.getStaticAnalysisReview();
        assertEquals(1, review.getSpotbugsFalsePositives().size());
        assertEquals("the value is checked one line above", review.getSpotbugsFalsePositives().get(0).getAssessment());
        assertTrue(review.getSpotbugsInChangedLines().isEmpty(), "a judged finding is not listed a second time");
        assertEquals(1, review.getPmdPreExisting().size(), "the finding the triage was not asked about is still listed");
    }
    private static LlmScoringResponse withDimensions(int architectureImpact) {
        LlmScoringResponse toReturn = new LlmScoringResponse();
        toReturn.setQualityDimensions(QualityDimensions.builder().architectureImpact(DimensionScore.builder().score(architectureImpact).build()).build());
        return toReturn;
    }
    private static LlmScoringRequest withCoverage(double percent) {
        return LlmScoringRequest.builder().coverage(CoverageInfo.builder().changedLineCoverage(percent).build()).build();
    }
    private static PreComputedScores preComputed(double cpdImpact, double staticImpact) {
        return PreComputedScores.builder().cpdRecommendedImpact(cpdImpact).staticAnalysisRecommendedImpact(staticImpact).build();
    }
}
