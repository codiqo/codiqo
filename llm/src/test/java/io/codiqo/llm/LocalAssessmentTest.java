package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockCategory;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockCategorySource;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockCategoryView;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import io.codiqo.llm.schema.LlmScoringResponse.TaskType;

/** The local review's assessment over the prompt's: what it replaces, what it must leave alone. */
class LocalAssessmentTest {
    /** with nothing to keep, as on the rules path, the review's own gate verdict is what the response carries */
    @Test
    void theReviewsGateVerdictFillsADimensionTheResponseDidNotJudge() {
        LlmScoringResponse local = new LlmScoringResponse();
        local.setQualityDimensions(QualityDimensions.builder()
                .resilience(DimensionScore.builder().score(8).rationale("retry path untested").qualityGateMet(false).build())
                .build());
        LlmScoringResponse target = new LlmScoringResponse();

        LocalAssessment.overlay(target, local);

        assertFalse(target.getQualityDimensions().getResilience().isQualityGateMet());
    }
    @Test
    void theReviewsJudgmentReplacesThePromptsForTheSamePieces() {
        LlmScoringResponse prompt = prompt();
        LocalAssessment.overlay(prompt, review());

        assertEquals("review summary", prompt.getSummary());
        assertEquals(List.of("pubsub"), prompt.getTags().getTechnical());
        assertEquals(List.of(TaskType.FEATURE, TaskType.TEST), prompt.getTaskTypes());
        assertEquals(5, prompt.getTaskComplexity());
        assertEquals(4, prompt.getTaskComplexityNew());
        assertEquals(6, prompt.getQualityDimensions().getIntegrationSurface().getScore());
        assertEquals("adds a topic", prompt.getQualityDimensions().getIntegrationSurface().getRationale());
        assertEquals(List.of(reviewed(block(CodeBlockCategory.SUBSTANTIVE))), prompt.getBlockCategories());
    }
    /** the review cannot see coverage, so gates and testing coverage are the prompt's; so is a dimension it left out */
    @Test
    void whatTheReviewCannotJudgeStaysThePrompts() {
        LlmScoringResponse prompt = prompt();
        LocalAssessment.overlay(prompt, review());

        assertFalse(prompt.getQualityDimensions().getIntegrationSurface().isQualityGateMet(), "the prompt's gate verdict survives the new score");
        assertEquals(9, prompt.getQualityDimensions().getTestingCoverage().getScore());
        assertEquals(7, prompt.getQualityDimensions().getDataIntegrity().getScore(), "a dimension the review did not score keeps the prompt's");
    }
    @Test
    void aReviewWithoutAPieceLeavesThePromptsPieceInPlace() {
        LlmScoringResponse prompt = prompt();
        LlmScoringResponse partial = new LlmScoringResponse();
        partial.setBlockCategories(List.of(block(CodeBlockCategory.ROUTINE)));

        LocalAssessment.overlay(prompt, partial);

        assertEquals("prompt summary", prompt.getSummary());
        assertEquals(8, prompt.getTaskComplexity());
        assertEquals(List.of(reviewed(block(CodeBlockCategory.ROUTINE))), prompt.getBlockCategories());
    }
    /** a unit the review left unlabelled keeps the prompt's label, marked as the prompt's, and is scored by it */
    @Test
    void aUnitTheReviewDidNotLabelKeepsThePromptsLabel() {
        LlmScoringResponse prompt = prompt();
        CodeBlockCategoryView other = CodeBlockCategoryView.builder().file("Foo.java").signature("other()").category(CodeBlockCategory.INTRICATE).build();
        prompt.setBlockCategories(List.of(block(CodeBlockCategory.MECHANICAL), other));

        LocalAssessment.overlay(prompt, review());

        assertEquals(List.of(reviewed(block(CodeBlockCategory.SUBSTANTIVE)), other.toBuilder().source(CodeBlockCategorySource.PROMPT).build()), prompt.getBlockCategories());
    }
    /** a review that labelled nothing leaves every label the prompt's, so its score keeps the prompt's difficulty */
    @Test
    void aReviewWithoutLabelsIsScoredWithThePromptsLabels() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args, NoopLog.INSTANCE);

        LlmScoringResponse prompt = prompt();
        prompt.setBlockCategories(List.of(block(CodeBlockCategory.SUBSTANTIVE)));
        LlmScoringResponse unlabelled = review();
        unlabelled.setBlockCategories(List.of());
        LlmScoringResponse local = LocalAssessment.copy(prompt);
        LocalAssessment.overlay(local, unlabelled);
        calculator.apply(prompt, FinalScoreCalculatorTest.scoresWithFileEffort("Foo.java", 100.0), null);
        calculator.apply(local, FinalScoreCalculatorTest.scoresWithFileEffort("Foo.java", 100.0), null);

        assertEquals(CodeBlockCategorySource.PROMPT, local.getBlockCategories().getFirst().getSource());
        assertEquals(Math.round(Math.pow(100.0 * args.getCategorySubstantiveCoeff(), args.getVolumeExponent())), local.getScore(), 0.001);
    }
    /**
     * A unit the prompt labelled twice is scored with its last label, and so must the review-less copy be: the overlay
     * passing on the first made the two scores differ (67.0 against 37.0) for a review that labelled nothing.
     */
    @Test
    void aRepeatedPromptLabelKeepsItsLastCategoryAsTheScoringDoes() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args, NoopLog.INSTANCE);

        LlmScoringResponse prompt = prompt();
        prompt.setBlockCategories(List.of(block(CodeBlockCategory.MECHANICAL), block(CodeBlockCategory.INTRICATE)));
        LlmScoringResponse unlabelled = review();
        unlabelled.setBlockCategories(List.of());
        LlmScoringResponse local = LocalAssessment.copy(prompt);
        LocalAssessment.overlay(local, unlabelled);
        calculator.apply(prompt, FinalScoreCalculatorTest.scoresWithFileEffort("Foo.java", 100.0), null);
        calculator.apply(local, FinalScoreCalculatorTest.scoresWithFileEffort("Foo.java", 100.0), null);

        assertEquals(List.of(block(CodeBlockCategory.INTRICATE).toBuilder().source(CodeBlockCategorySource.PROMPT).build()), local.getBlockCategories());
        assertEquals(prompt.getScore(), local.getScore(), 0.001);
    }
    /** the triage's static-analysis review replaces the prompt's; without one the prompt's stays */
    @Test
    void theTriagedStaticAnalysisReviewReplacesThePrompts() {
        LlmScoringResponse prompt = prompt();
        LlmScoringResponse.StaticAnalysisReview promptReview = LlmScoringResponse.StaticAnalysisReview.builder().build();
        prompt.setStaticAnalysisReview(promptReview);

        LocalAssessment.overlay(prompt, new LlmScoringResponse());
        assertEquals(promptReview, prompt.getStaticAnalysisReview());

        LlmScoringResponse triaged = new LlmScoringResponse();
        triaged.setStaticAnalysisReview(LlmScoringResponse.StaticAnalysisReview.builder()
                .pmdFalsePositives(List.of(LlmScoringResponse.StaticAnalysisFinding.builder().rule("GodClass").build()))
                .build());
        LocalAssessment.overlay(prompt, triaged);
        assertEquals("GodClass", prompt.getStaticAnalysisReview().getPmdFalsePositives().getFirst().getRule());
    }
    /** the review names the findings and judges the module; the server's caller counts and the findings' price are not its */
    @Test
    void theReviewsArchitectureAndBlastRadiusJudgmentKeepTheGroundedCounts() {
        LlmScoringResponse prompt = prompt();
        prompt.setQualityMultiplier(LlmScoringResponse.QualityMultiplier.builder()
                .architectureAnalysis(LlmScoringResponse.ArchitectureAnalysis.builder().solidViolations(List.of("prompt finding")).penaltyImpact(-0.05).build())
                .build());
        prompt.setBlastRadiusAnalysis(LlmScoringResponse.BlastRadiusAnalysis.builder()
                .totalCallers(42)
                .productionCallers(30)
                .moduleType(LlmScoringResponse.ModuleType.LEAF_APPLICATION)
                .explanation("prompt explanation")
                .build());

        LlmScoringResponse local = new LlmScoringResponse();
        local.setQualityMultiplier(LlmScoringResponse.QualityMultiplier.builder()
                .architectureAnalysis(LlmScoringResponse.ArchitectureAnalysis.builder().solidViolations(List.of("SRP: the mapper also sends mail")).build())
                .build());
        local.setBlastRadiusAnalysis(LlmScoringResponse.BlastRadiusAnalysis.builder()
                .moduleType(LlmScoringResponse.ModuleType.CORE_LIBRARY)
                .signatureChanges(LlmScoringResponse.SignatureChanges.builder().hasBreakingChanges(true).changedSignatures(List.of("Api.send(String)")).build())
                .explanation("the client library's send() gained a parameter")
                .build());

        LocalAssessment.overlay(prompt, local);

        assertEquals(List.of("SRP: the mapper also sends mail"), prompt.getQualityMultiplier().getArchitectureAnalysis().getSolidViolations());
        assertEquals(0.0, prompt.getQualityMultiplier().getArchitectureAnalysis().getPenaltyImpact(), "the price is computed from the findings, never copied");
        assertEquals(LlmScoringResponse.ModuleType.CORE_LIBRARY, prompt.getBlastRadiusAnalysis().getModuleType());
        assertTrue(prompt.getBlastRadiusAnalysis().getSignatureChanges().isHasBreakingChanges());
        assertEquals("the client library's send() gained a parameter", prompt.getBlastRadiusAnalysis().getExplanation());
        assertEquals(42, prompt.getBlastRadiusAnalysis().getTotalCallers());
        assertEquals(30, prompt.getBlastRadiusAnalysis().getProductionCallers());
    }
    /** the score is a primitive: a review that never judged senior review reads 0 and must not clear the prompt's */
    @Test
    void anUnjudgedSeniorReviewLeavesThePromptsInPlace() {
        LlmScoringResponse prompt = prompt();
        prompt.setRequiresSeniorReview(7);
        prompt.setSeniorReviewReasons(List.of("prompt reason"));

        LocalAssessment.overlay(prompt, new LlmScoringResponse());

        assertEquals(7, prompt.getRequiresSeniorReview());
        assertEquals(List.of("prompt reason"), prompt.getSeniorReviewReasons());

        LlmScoringResponse local = new LlmScoringResponse();
        local.setRequiresSeniorReview(5);
        local.setSeniorReviewReasons(List.of("changes the retry contract"));
        LocalAssessment.overlay(prompt, local);

        assertEquals(5, prompt.getRequiresSeniorReview());
        assertEquals(List.of("changes the retry contract"), prompt.getSeniorReviewReasons());
    }
    /** the comparison scores a copy, so neither computation may leak into the other */
    @Test
    void theCopyIsIndependentOfTheOriginal() {
        LlmScoringResponse prompt = prompt();
        LlmScoringResponse copy = LocalAssessment.copy(prompt);
        LocalAssessment.overlay(copy, review());

        assertNotSame(prompt, copy);
        assertEquals("prompt summary", prompt.getSummary());
        assertEquals(List.of(block(CodeBlockCategory.MECHANICAL)), prompt.getBlockCategories());
    }
    /** the labels drive the score: the same block scores higher once the review calls it SUBSTANTIVE */
    @Test
    void theReviewsLabelsAreWhatTheScoreIsComputedFrom() {
        RunArgs args = new RunArgs();
        FinalScoreCalculator calculator = new FinalScoreCalculator(args, NoopLog.INSTANCE);

        LlmScoringResponse prompt = prompt();
        LlmScoringResponse local = LocalAssessment.copy(prompt);
        LocalAssessment.overlay(local, review());
        calculator.apply(prompt, FinalScoreCalculatorTest.scoresWithFileEffort("Foo.java", 100.0), null);
        calculator.apply(local, FinalScoreCalculatorTest.scoresWithFileEffort("Foo.java", 100.0), null);

        double expected = Math.round(Math.pow(100.0 * args.getCategorySubstantiveCoeff(), args.getVolumeExponent()));
        assertEquals(expected, local.getScore(), 0.001);
        assertTrue(local.getScore() > prompt.getScore(), local.getScore() + " vs " + prompt.getScore());
        assertNull(prompt.getQualityDimensions().getPerformance(), "untouched dimensions stay untouched");
    }
    private static LlmScoringResponse prompt() {
        LlmScoringResponse toReturn = new LlmScoringResponse();
        toReturn.setSummary("prompt summary");
        toReturn.setTags(new LlmScoringResponse.Tags(List.of("rest-api"), List.of("gaming")));
        toReturn.setTaskTypes(List.of(TaskType.FEATURE));
        toReturn.setTaskComplexity(8);
        QualityDimensions dims = new QualityDimensions();
        dims.setIntegrationSurface(new DimensionScore(7, "prompt rationale", false));
        dims.setDataIntegrity(new DimensionScore(7, "prompt rationale", true));
        dims.setTestingCoverage(new DimensionScore(9, "89% covered", true));
        toReturn.setQualityDimensions(dims);
        toReturn.setBlockCategories(List.of(block(CodeBlockCategory.MECHANICAL)));
        return toReturn;
    }
    private static LlmScoringResponse review() {
        LlmScoringResponse toReturn = new LlmScoringResponse();
        toReturn.setSummary("review summary");
        toReturn.setTags(new LlmScoringResponse.Tags(List.of("pubsub"), List.of("free-spins")));
        toReturn.setTaskTypes(List.of(TaskType.FEATURE, TaskType.TEST));
        toReturn.setTaskComplexity(5);
        toReturn.setTaskComplexityNew(4);
        QualityDimensions dims = new QualityDimensions();
        dims.setIntegrationSurface(new DimensionScore(6, "adds a topic", true));
        toReturn.setQualityDimensions(dims);
        toReturn.setBlockCategories(List.of(block(CodeBlockCategory.SUBSTANTIVE)));
        return toReturn;
    }
    private static CodeBlockCategoryView reviewed(CodeBlockCategoryView view) {
        return view.toBuilder().source(CodeBlockCategorySource.LOCAL_REVIEW).build();
    }
    private static CodeBlockCategoryView block(CodeBlockCategory category) {
        return CodeBlockCategoryView.builder().file("Foo.java").signature("doStuff()").category(category).build();
    }
}
