package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.api.review.UnitName;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.BugModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocalAssessmentModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.client.model.SignatureChangesModel;
import io.codiqo.client.model.StaticAnalysisFindingModel;
import io.codiqo.client.model.StaticAnalysisReviewModel;
import io.codiqo.client.model.TaskTypeModel;
import io.codiqo.llm.schema.LlmScoringResponse;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class LocalReviewModelsTest {
    /** a made-up language whose units are named Container.member, as its index signature spells them */
    private static final ReviewLanguage DOTTED = new ReviewLanguage() {
        @Override
        public Collection<String> extensions() {
            return List.of("dotted");
        }
        @Override
        public String namingRule() {
            return "Write Container.member(params).";
        }
        @Override
        public UnitName labelled(String signature, String path) {
            List<String> steps = Lists.newArrayList(Splitter.on('.').split(StringUtils.substringBefore(signature, "(")));
            return new UnitName(steps.subList(0, steps.size() - 1), steps.getLast() + signature.substring(StringUtils.substringBefore(signature, "(").length()));
        }
        @Override
        public UnitName unit(String name, String signature, String path) {
            List<String> steps = Splitter.on('.').splitToList(signature);
            return new UnitName(steps.subList(0, steps.size() - 1), name);
        }
        @Override
        public Set<String> triagedTools() {
            return Set.of();
        }
    };

    @Test
    void everyBugIsTaggedAsALocalReviewFinding() {
        LlmScoringResponse.Bug bug = LlmScoringResponse.Bug.builder()
                .type(LlmScoringResponse.BugType.LOGIC)
                .title("rethrows after recovering")
                .description("throw e sits outside the if")
                .file("A.java")
                .line(42)
                .confidence(LlmScoringResponse.Confidence.HIGH)
                .build();
        LlmScoringResponse.Bugs bugs = LlmScoringResponse.Bugs.builder().blocking(List.of(bug)).minor(List.of(bug)).build();
        LocalReview review = new LocalReview("abc", bugs, List.of(
                new SessionUsage("ses_1", OpenCodeReviewConfig.COORDINATOR, "coordinator-model", 10, 100, 2, 1),
                new SessionUsage("ses_2", OpenCodeReviewConfig.REVIEWER, "reviewer-model", 5, 1_000, 3, 0)), Duration.ofSeconds(90), "{}", null, 2, List.of(), List.of());

        LocalReviewModel model = LocalReviewModels.toModel(review);

        assertEquals(90_000L, model.getTookMs());
        assertEquals(BugModel.SourceEnum.LOCAL_REVIEW, model.getBugs().getBlocking().getFirst().getSource());
        assertEquals(BugModel.SourceEnum.LOCAL_REVIEW, model.getBugs().getMinor().getFirst().getSource());
        assertEquals(42, model.getBugs().getBlocking().getFirst().getLine());
        assertEquals(2, model.getSessions().size());
        assertEquals(1_000L, model.getSessions().get(1).getCachedInputTokens());
        assertEquals("coordinator-model", model.getSessions().getFirst().getModel());
    }
    /**
     * A confirmed defect joins the review's bugs at the triage's severity; every verdict places its finding, a false
     * positive and a harmless match at info, a defect at the tool's severity; a verdict on nothing asked is dropped.
     */
    @Test
    void theTriageFoldsIntoTheBugsAndTheStaticAnalysisReview() {
        FindingTriage triage = new FindingTriage();
        triage.setFindings(List.of(
                new StaticFinding("spotbugs", "NP_NULL_ON_SOME_PATH", "error", "a/Service.java", 12, "possible null"),
                new StaticFinding("pmd", "ReturnEmptyCollectionRatherThanNull", "warning", "a/Service.java", 30, "return null"),
                new StaticFinding("pmd", "FormalParameterNamingConventions", "info", "a/Service.java", 40, "month_at")));
        triage.setVerdicts(List.of(
                verdict("spotbugs", "NP_NULL_ON_SOME_PATH", 12, FindingVerdict.Verdict.DEFECT, FindingVerdict.Severity.MAJOR),
                verdict("pmd", "ReturnEmptyCollectionRatherThanNull", 30, FindingVerdict.Verdict.FALSE_POSITIVE, null),
                verdict("pmd", "FormalParameterNamingConventions", 40, FindingVerdict.Verdict.HARMLESS, null),
                verdict("pmd", "GodClass", 1, FindingVerdict.Verdict.DEFECT, FindingVerdict.Severity.BLOCKING)));
        LocalReview review = assessed();

        LocalReviewModel model = LocalReviewModels.toModel(review, Optional.of(triage));

        assertEquals(1, model.getBugs().getMajor().size());
        BugModel defect = model.getBugs().getMajor().getFirst();
        assertEquals("NP_NULL_ON_SOME_PATH (spotbugs)", defect.getTitle());
        assertEquals(BugModel.ConfidenceEnum.HIGH, defect.getConfidence());
        assertEquals(BugModel.SourceEnum.LOCAL_REVIEW, defect.getSource());
        assertEquals(1, model.getBugs().getBlocking().size(), "a defect verdict is the review's judgment even on a finding not asked about");

        LlmScoringResponse.Bug known = LlmScoringResponse.Bug.builder().title("possible null on the error path").description("the error path reads it unchecked").file("a/Service.java").line(12).build();
        LocalReview alreadyFound = new LocalReview("abc", LlmScoringResponse.Bugs.builder().minor(List.of(known)).build(), List.of(), Duration.ofSeconds(1), "{}",
                new LlmScoringResponse(), 0, List.of(), List.of());
        LocalReviewModel deduplicated = LocalReviewModels.toModel(alreadyFound, Optional.of(triage));
        assertEquals(List.of("possible null on the error path"), deduplicated.getBugs().getMinor().stream().map(BugModel::getTitle).toList());
        assertTrue(deduplicated.getBugs().getMajor().isEmpty(), "the review already reported the bug on that line, so the confirmed finding is not listed twice");

        StaticAnalysisReviewModel placed = model.getAssessment().getStaticAnalysisReview();
        assertEquals(1, placed.getSpotbugsInChangedLines().size());
        assertEquals(StaticAnalysisFindingModel.SeverityEnum.ERROR, placed.getSpotbugsInChangedLines().getFirst().getSeverity());
        assertEquals(1, placed.getPmdFalsePositives().size());
        assertEquals(StaticAnalysisFindingModel.SeverityEnum.INFO, placed.getPmdFalsePositives().getFirst().getSeverity());
        assertEquals(StaticAnalysisFindingModel.ToolSeverityEnum.WARNING, placed.getPmdFalsePositives().getFirst().getToolSeverity());
        assertEquals(1, placed.getPmdInChangedLines().size(), "the harmless match stays listed, the invented one is not");
        assertEquals(40, placed.getPmdInChangedLines().getFirst().getLine());
    }
    /** the commit page's digest: the coordinator's areas, the reviewers' observations, and what the triage concluded */
    @Test
    void theReviewTravelsAsADigestWithItsObservations() {
        ObjectNode coordinator = JsonNodeFactory.instance.objectNode();
        ArrayNode tasks = coordinator.putArray("tasks");
        tasks.addObject().put("findings", 1).putArray("paths").add("payments/");
        tasks.addObject().put("findings", 0).putArray("paths").add("docs/").add("README.md");
        coordinator.putArray("blocking");
        coordinator.putArray("major");
        coordinator.putArray("minor");
        String answer = coordinator.toString();
        LocalReview review = new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), answer, null, 2, List.of(),
                List.of("adds a refund endpoint that writes the ledger"));
        FindingTriage triage = new FindingTriage();
        triage.setVerdicts(List.of(verdict("spotbugs", "OS_OPEN_STREAM", 19, FindingVerdict.Verdict.DEFECT, FindingVerdict.Severity.MAJOR),
                verdict("pmd", "AssignmentInOperand", 21, FindingVerdict.Verdict.HARMLESS, null)));

        LocalReviewModel model = LocalReviewModels.toModel(review, Optional.of(triage));

        assertEquals(List.of("adds a refund endpoint that writes the ledger"), model.getObservations());
        assertTrue(model.getReasoning().contains("`payments/` — 1 finding"), model.getReasoning());
        assertTrue(model.getReasoning().contains("`docs/`, `README.md` — 0 findings"), model.getReasoning());
        assertTrue(model.getReasoning().contains("**Observations**\n- adds a refund endpoint"), model.getReasoning());
        assertTrue(model.getReasoning().contains("2 — 1 defects, 0 false positives, 1 harmless"), model.getReasoning());
        assertTrue(model.getReasoning().contains("`OS_OPEN_STREAM` at a/Service.java:19"), model.getReasoning());
    }
    /** the digest is Markdown the commit page renders as it is, so every byte of it is pinned */
    @Test
    void theDigestIsRenderedExactly() {
        ObjectNode coordinator = JsonNodeFactory.instance.objectNode();
        ArrayNode tasks = coordinator.putArray("tasks");
        tasks.addObject().put("findings", 1).putArray("paths").add("payments/");
        tasks.addObject().put("findings", 3).putArray("paths").add("docs/").add("README.md");
        tasks.addObject().put("findings", 2).putArray("paths");
        LocalReview review = new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), coordinator.toString(), null, 4,
                List.of("ses_7", "ses_9"), List.of("adds a refund endpoint that writes the ledger", "retries the payment twice"));
        FindingVerdict unexplained = verdict("spotbugs", "NP_NULL_ON_SOME_PATH", 7, FindingVerdict.Verdict.DEFECT, FindingVerdict.Severity.MINOR);
        unexplained.setReason(null);
        FindingVerdict unjudged = verdict("pmd", "GodClass", 1, null, null);
        FindingTriage triage = new FindingTriage();
        triage.setVerdicts(List.of(unexplained,
                verdict("pmd", "AssignmentInOperand", 21, FindingVerdict.Verdict.HARMLESS, null),
                verdict("pmd", "ReturnEmptyCollectionRatherThanNull", 30, FindingVerdict.Verdict.FALSE_POSITIVE, null),
                unjudged,
                verdict("spotbugs", "OS_OPEN_STREAM", 19, FindingVerdict.Verdict.DEFECT, FindingVerdict.Severity.MAJOR)));

        String reasoning = LocalReviewModels.toModel(review, Optional.of(triage)).getReasoning();

        assertEquals("""
                **Areas reviewed** (4 reviewer sessions)
                - `payments/` — 1 finding
                - `docs/`, `README.md` — 3 findings
                - 2 reviewer sessions ended without an answer, so part of the commit may be unreviewed

                **Observations**
                - adds a refund endpoint that writes the ledger
                - retries the payment twice

                **Tool findings checked after the build**: 5 — 2 defects, 1 false positives, 1 harmless
                - `NP_NULL_ON_SOME_PATH` at a/Service.java:7:\s
                - `OS_OPEN_STREAM` at a/Service.java:19: read the code""", reasoning);
    }
    @Test
    void aDigestWithoutAreasStartsAtItsFirstSection() {
        LocalReview observed = new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), "{}", null, 0, List.of("ses_1"),
                List.of("adds a refund endpoint"));
        FindingTriage empty = new FindingTriage();
        empty.setVerdicts(List.of());
        LocalReview silent = new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), "{}", null, 0, List.of(), null);

        assertEquals("**Observations**\n- adds a refund endpoint", LocalReviewModels.toModel(observed).getReasoning(),
                "the unanswered sessions are reported only beside the areas");
        assertEquals("**Tool findings checked after the build**: 0 — 0 defects, 0 false positives, 0 harmless",
                LocalReviewModels.toModel(silent, Optional.of(empty)).getReasoning());
        assertNull(LocalReviewModels.toModel(silent).getReasoning(), "a review with nothing to say has no digest");
    }
    /** a plan the repair round never checked costs only the areas of the digest, never the submission */
    @Test
    void aMalformedPlanLeavesTheAreasOut() {
        for (String answer : List.of("{\"tasks\":null}", "{\"tasks\":[null]}", "{\"tasks\":[{\"paths\":\"core/\",\"findings\":2}]}")) {
            LocalReview review = new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), answer, null, 0, List.of(),
                    List.of("adds a refund endpoint"));

            assertEquals("**Observations**\n- adds a refund endpoint", LocalReviewModels.toModel(review).getReasoning(), answer);
        }
    }
    /** a label above MECHANICAL carries the reviewer's reason to the unit; a MECHANICAL one never does */
    @Test
    void aLabelsReasonReachesItsUnit() {
        FileChangeModel file = new FileChangeModel().path("a/Service.java").codeUnits(List.of(unit("pay(Order)"), unit("name()")));
        AnalysisSubmissionModel submission = new AnalysisSubmissionModel().files(List.of(file));
        LlmScoringResponse.CodeBlockCategoryView pay = label("a/Service.java", "pay(Order)", LlmScoringResponse.CodeBlockCategory.SUBSTANTIVE);
        pay.setReason("splits the refund across two ledgers in one transaction");
        LlmScoringResponse.CodeBlockCategoryView name = label("a/Service.java", "name()", LlmScoringResponse.CodeBlockCategory.MECHANICAL);
        name.setReason("an accessor");

        LocalReviewModels.applyBlockCategories(submission, assessed(pay, name), List.of());

        assertEquals("splits the refund across two ledgers in one transaction", file.getCodeUnits().get(0).getCategoryReason());
        assertNull(file.getCodeUnits().get(1).getCategoryReason());
    }
    /** a harmless verdict listed first on a line must not hide a defect on the same line */
    @Test
    void aHarmlessVerdictDoesNotClaimTheLineOfADefect() {
        FindingTriage triage = new FindingTriage();
        triage.setFindings(List.of(new StaticFinding("spotbugs", "OS_OPEN_STREAM", "warning", "a/Service.java", 19, "may fail to close stream")));
        FindingVerdict harmless = verdict("pmd", "AssignmentInOperand", 19, FindingVerdict.Verdict.HARMLESS, null);
        FindingVerdict defect = verdict("spotbugs", "OS_OPEN_STREAM", 19, FindingVerdict.Verdict.DEFECT, FindingVerdict.Severity.MAJOR);
        defect.setReason(null);
        triage.setVerdicts(List.of(harmless, defect));

        LocalReviewModel model = LocalReviewModels.toModel(assessed(), Optional.of(triage));

        assertEquals(1, model.getBugs().getMajor().size());
        assertEquals("may fail to close stream", model.getBugs().getMajor().getFirst().getDescription(), "a verdict without a reason keeps the tool's message");
    }
    /** the server requires a title and a description; a bug the model left without one is dropped, not the submission */
    @Test
    void aBugWithoutADescriptionIsLeftOut() {
        LlmScoringResponse.Bug incomplete = LlmScoringResponse.Bug.builder().title("no description").file("A.java").line(1).build();
        LocalReview review = new LocalReview("abc", LlmScoringResponse.Bugs.builder().blocking(List.of(incomplete)).build(), List.of(), Duration.ofSeconds(1), "{}", null, 0,
                List.of(), List.of());

        LocalReviewModel model = LocalReviewModels.toModel(review);

        assertTrue(model.getBugs().getBlocking().isEmpty());
        assertEquals(Boolean.FALSE, model.getBugs().getHasBlockingBugs());
    }
    private static FindingVerdict verdict(String tool, String rule, int line, FindingVerdict.Verdict verdict, FindingVerdict.Severity severity) {
        FindingVerdict toReturn = new FindingVerdict();
        toReturn.setTool(tool);
        toReturn.setRule(rule);
        toReturn.setFile("a/Service.java");
        toReturn.setLine(line);
        toReturn.setVerdict(verdict);
        toReturn.setSeverity(severity);
        toReturn.setType(LlmScoringResponse.BugType.NULL_POINTER);
        toReturn.setReason("read the code");
        return toReturn;
    }
    /**
     * The agents name a unit the way they read it in the source; the index names it with simple parameter types,
     * type arguments included, and a constructor after its class. Both must land on the same unit.
     */
    @Test
    void labelsLandOnTheCodeUnitsTheyName() {
        CodeUnitModel handler = unit("handle(List, Optional)");
        CodeUnitModel alreadyLabelled = unit("sum(List)").category(CodeUnitModel.CategoryEnum.MECHANICAL);
        CodeUnitModel unnamed = unit("helper()");
        AnalysisSubmissionModel submission = new AnalysisSubmissionModel().files(List.of(
                new FileChangeModel().path("src/main/java/com/example/Totals.java").codeUnits(List.of(handler, alreadyLabelled, unnamed))));

        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission, assessed(
                label("./src/main/java/com/example/Totals.java", "handle(List,Optional)", LlmScoringResponse.CodeBlockCategory.SUBSTANTIVE),
                label("src/main/java/com/example/Totals.java", "sum(List)", LlmScoringResponse.CodeBlockCategory.INTRICATE),
                label("src/main/java/com/example/Gone.java", "deleted()", LlmScoringResponse.CodeBlockCategory.ROUTINE)), List.of());

        assertEquals(1, labelling.getApplied());
        assertEquals(3, labelling.getReturned());
        assertEquals(3, labelling.getUnits());
        assertEquals(List.of("src/main/java/com/example/Gone.java#deleted()"), labelling.getUnmatched(), "a label naming no unit is reported, not lost silently");
        assertEquals(CodeUnitModel.CategoryEnum.SUBSTANTIVE, handler.getCategory(), "the path is normalized, and a language without a naming of its own matches the exact name");
        assertEquals(CodeUnitModel.CategoryEnum.MECHANICAL, alreadyLabelled.getCategory(), "a unit already labelled keeps its label");
        assertNull(unnamed.getCategory(), "a unit no label names stays unlabelled, which prices as MECHANICAL");
    }
    @Test
    void theCommitLevelPiecesTravelInTheSubmission() {
        LlmScoringResponse assessment = new LlmScoringResponse();
        assessment.setSummary("adds a cancel endpoint");
        assessment.setTags(new LlmScoringResponse.Tags(List.of("pubsub"), List.of("free-spins")));
        assessment.setTaskTypes(List.of(LlmScoringResponse.TaskType.FEATURE, LlmScoringResponse.TaskType.TEST));
        assessment.setTaskComplexity(5);
        LlmScoringResponse.QualityDimensions dims = new LlmScoringResponse.QualityDimensions();
        dims.setIntegrationSurface(new LlmScoringResponse.DimensionScore(6, "adds a topic", true));
        assessment.setQualityDimensions(dims);
        LocalReview review = new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), "{}", assessment, 0, List.of(), List.of());

        LocalAssessmentModel model = LocalReviewModels.toModel(review).getAssessment();

        assertEquals("adds a cancel endpoint", model.getSummary());
        assertEquals(List.of("pubsub"), model.getTags().getTechnical());
        assertEquals(List.of(TaskTypeModel.FEATURE, TaskTypeModel.TEST), model.getTaskTypes());
        assertEquals(5, model.getTaskComplexity());
        assertEquals(6, model.getQualityDimensions().getIntegrationSurface().getScore());
    }
    /** only what the review judged travels: no caller counts, no risk level, no price for the findings */
    @Test
    void theReviewsArchitectureBlastRadiusAndSeniorReviewTravel() {
        LlmScoringResponse assessment = new LlmScoringResponse();
        assessment.setQualityMultiplier(LlmScoringResponse.QualityMultiplier.builder()
                .architectureAnalysis(LlmScoringResponse.ArchitectureAnalysis.builder()
                        .solidViolations(List.of("SRP: the mapper also sends mail", " "))
                        .architectureIssues(List.of("cycle between billing and mail"))
                        .build())
                .build());
        assessment.setBlastRadiusAnalysis(LlmScoringResponse.BlastRadiusAnalysis.builder()
                .moduleType(LlmScoringResponse.ModuleType.SHARED_UTILITY)
                .signatureChanges(LlmScoringResponse.SignatureChanges.builder()
                        .hasBreakingChanges(true)
                        .changedSignatures(List.of("Mails.send(String)"))
                        .breakingChangeType(LlmScoringResponse.BreakingChangeType.PARAMETER_ADDED)
                        .build())
                .explanation("every caller of send() must pass a locale")
                .build());
        assessment.setRequiresSeniorReview(6);
        assessment.setSeniorReviewReasons(List.of("changes a shared contract"));
        LocalReview review = new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), "{}", assessment, 0, List.of(), List.of());

        LocalAssessmentModel model = LocalReviewModels.toModel(review).getAssessment();

        assertEquals(List.of("SRP: the mapper also sends mail"), model.getSolidViolations());
        assertEquals(List.of("cycle between billing and mail"), model.getArchitectureIssues());
        assertEquals(LocalAssessmentModel.ModuleTypeEnum.SHARED_UTILITY, model.getModuleType());
        assertEquals(List.of("Mails.send(String)"), model.getSignatureChanges().getChangedSignatures());
        assertEquals(SignatureChangesModel.BreakingChangeTypeEnum.PARAMETER_ADDED, model.getSignatureChanges().getBreakingChangeType());
        assertEquals("every caller of send() must pass a locale", model.getBlastRadiusExplanation());
        assertEquals(6, model.getRequiresSeniorReview());
        assertEquals(List.of("changes a shared contract"), model.getSeniorReviewReasons());
    }
    /**
     * A method moved into a nested class leaves a deleted unit and a new one with the same name in the same file: the
     * label is the new unit's, and a deleted unit, which is priced as a deletion, is never labelled.
     */
    @Test
    void aDeletedUnitNeverTakesTheLabelOfANewOneWithItsName() {
        CodeUnitModel removed = unit("report()").signature("Watchdog.report()V").operation(CodeUnitModel.OperationEnum.DELETE);
        CodeUnitModel added = unit("report()").signature("Watchdog$Task.report()V").operation(CodeUnitModel.OperationEnum.NEW);
        AnalysisSubmissionModel submission = new AnalysisSubmissionModel().files(List.of(
                new FileChangeModel().path("src/main/java/com/example/Watchdog.java").codeUnits(List.of(removed, added))));

        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission, assessed(
                label("src/main/java/com/example/Watchdog.java", "report()", LlmScoringResponse.CodeBlockCategory.SUBSTANTIVE)), List.of());

        assertEquals(CodeUnitModel.CategoryEnum.SUBSTANTIVE, added.getCategory());
        assertNull(removed.getCategory());
        assertEquals(1, labelling.getApplied());
        assertEquals(1, labelling.getUnits());
    }
    /** two live units of one name in one file, of the outer container and a nested one, each take the label naming its container */
    @Test
    void theContainerPicksBetweenUnitsOfOneName() {
        CodeUnitModel outer = unit("report()").signature("Watchdog.report");
        CodeUnitModel nested = unit("report()").signature("Watchdog.Task.report").operation(CodeUnitModel.OperationEnum.NEW);
        AnalysisSubmissionModel submission = new AnalysisSubmissionModel().files(List.of(
                new FileChangeModel().path("src/watchdog.dotted").codeUnits(List.of(outer, nested))));

        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission, assessed(
                label("src/watchdog.dotted", "Watchdog.report()", LlmScoringResponse.CodeBlockCategory.MECHANICAL),
                label("src/watchdog.dotted", "Watchdog.Task.report()", LlmScoringResponse.CodeBlockCategory.SUBSTANTIVE)), List.of(DOTTED));

        assertEquals(CodeUnitModel.CategoryEnum.MECHANICAL, outer.getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.SUBSTANTIVE, nested.getCategory());
        assertEquals(2, labelling.getApplied());
        assertEquals(List.of(), labelling.getUnmatched());
    }
    /** without a container to choose by, a label naming two units is reported rather than handed to both */
    @Test
    void aLabelThatCannotChooseBetweenUnitsOfOneNameIsNotApplied() {
        CodeUnitModel outer = unit("report()").signature("Watchdog.report");
        CodeUnitModel nested = unit("report()").signature("Watchdog.Task.report");
        AnalysisSubmissionModel submission = new AnalysisSubmissionModel().files(List.of(
                new FileChangeModel().path("src/watchdog.dotted").codeUnits(List.of(outer, nested))));

        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission, assessed(
                label("src/watchdog.dotted", "report()", LlmScoringResponse.CodeBlockCategory.SUBSTANTIVE),
                label("src/watchdog.dotted", "Other.report()", LlmScoringResponse.CodeBlockCategory.ROUTINE)), List.of(DOTTED));

        assertNull(outer.getCategory());
        assertNull(nested.getCategory());
        assertEquals(0, labelling.getApplied());
        assertEquals(List.of("src/watchdog.dotted#report()", "src/watchdog.dotted#Other.report()"), labelling.getUnmatched());
    }
    private static CodeUnitModel unit(String name) {
        return new CodeUnitModel().name(name).signature("sig:" + name).operation(CodeUnitModel.OperationEnum.MODIFY);
    }
    private static LlmScoringResponse.CodeBlockCategoryView label(String file, String signature, LlmScoringResponse.CodeBlockCategory category) {
        return LlmScoringResponse.CodeBlockCategoryView.builder().file(file).signature(signature).category(category).build();
    }
    private static LocalReview assessed(LlmScoringResponse.CodeBlockCategoryView... labels) {
        LlmScoringResponse assessment = new LlmScoringResponse();
        assessment.setBlockCategories(List.of(labels));
        return new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), "{}", assessment, 0, List.of(), List.of());
    }
}
