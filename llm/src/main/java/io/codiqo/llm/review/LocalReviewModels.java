package io.codiqo.llm.review;

import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.thymeleaf.context.Context;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.api.code.JavaSignatures;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.BugModel;
import io.codiqo.client.model.BugsModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocalAssessmentModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.client.model.LocalReviewSessionModel;
import io.codiqo.llm.FindingKey;
import io.codiqo.llm.LlmResponseMapper;
import io.codiqo.llm.PromptTemplates;
import io.codiqo.llm.client.LlmJson;
import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;
import lombok.experimental.UtilityClass;
import tools.jackson.core.JacksonException;

/** Maps a finished local review onto the submission: its {@code localReview} block and the code units' labels. */
@UtilityClass
public class LocalReviewModels {
    private static final String TEMPLATE_DIGEST = "opencode/review-digest";
    /**
     * The units a review labels: what the commit adds or changes. A deleted unit is priced as a deletion and never
     * labelled, and it may share its name with a new one (a method moved into a nested class), which would hand the new
     * unit's label to it as well.
     */
    private static final EnumSet<CodeUnitModel.OperationEnum> LABELLED_OPERATIONS = EnumSet.of(CodeUnitModel.OperationEnum.NEW, CodeUnitModel.OperationEnum.MODIFY);

    public LocalReviewModel toModel(LocalReview review) {
        return toModel(review, Optional.empty());
    }
    public LocalReviewModel toModel(LocalReview review, Optional<FindingTriage> triage) {
        LlmScoringResponse.Bugs found = LlmScoringResponse.Bugs.builder()
                .blocking(Lists.newArrayList(CollectionUtils.emptyIfNull(review.getBugs().getBlocking())))
                .major(Lists.newArrayList(CollectionUtils.emptyIfNull(review.getBugs().getMajor())))
                .minor(Lists.newArrayList(CollectionUtils.emptyIfNull(review.getBugs().getMinor())))
                .build();
        triage.ifPresent(t -> addDefects(found, t));

        BugsModel bugs = LlmResponseMapper.mapBugs(found);
        bugs.setBlocking(complete(bugs.getBlocking()));
        bugs.setMajor(complete(bugs.getMajor()));
        bugs.setMinor(complete(bugs.getMinor()));
        bugs.setHasBlockingBugs(CollectionUtils.isNotEmpty(bugs.getBlocking()));
        Stream.of(bugs.getBlocking(), bugs.getMajor(), bugs.getMinor())
                .flatMap(List::stream)
                .forEach(bug -> bug.setSource(BugModel.SourceEnum.LOCAL_REVIEW));

        LocalReviewModel toReturn = new LocalReviewModel();
        toReturn.setTookMs(review.getTook().toMillis());
        toReturn.setBugs(bugs);
        for (SessionUsage session : review.getSessions()) {
            toReturn.addSessionsItem(toModel(session));
        }
        toReturn.setObservations(CollectionUtils.emptyIfNull(review.getObservations()).stream().toList());
        toReturn.setReasoning(reasoning(review, triage));
        if (Objects.nonNull(review.getAssessment())) {
            LocalAssessmentModel assessment = toModel(review.getAssessment());
            triage.ifPresent(t -> assessment.setStaticAnalysisReview(LlmResponseMapper.mapStaticAnalysisReview(staticAnalysisReview(t))));
            toReturn.setAssessment(assessment);
        }
        return toReturn;
    }
    public Labelling applyBlockCategories(AnalysisSubmissionModel submission, LocalReview review) {
        Map<Pair<String, String>, LlmScoringResponse.CodeBlockCategoryView> labels = Maps.newLinkedHashMap();
        for (LlmScoringResponse.CodeBlockCategoryView view : CollectionUtils.emptyIfNull(review.getAssessment().getBlockCategories())) {
            if (Objects.nonNull(view.getCategory())) {
                labels.putIfAbsent(key(view.getFile(), view.getSignature()), view);
            }
        }

        Map<CodeUnitModel, LlmScoringResponse.CodeBlockCategoryView> resolved = new IdentityHashMap<>();
        Set<Pair<String, String>> matched = Sets.newHashSet();
        int units = 0;
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (LABELLED_OPERATIONS.contains(unit.getOperation())) {
                    units++;
                    Pair<String, String> unitKey = key(file.getPath(), unit.getName());
                    LlmScoringResponse.CodeBlockCategoryView label = labels.get(unitKey);
                    if (Objects.nonNull(label)) {
                        matched.add(unitKey);
                        if (Objects.isNull(unit.getCategory())) {
                            resolved.put(unit, label);
                        }
                    }
                }
            }
        }
        resolved.forEach((unit, label) -> {
            unit.setCategory(CodeUnitModel.CategoryEnum.fromValue(label.getCategory().name()));
            /** a reason is asked for only above MECHANICAL; one the model gave anyway says nothing a reader needs */
            if (label.getCategory() != LlmScoringResponse.CodeBlockCategory.MECHANICAL) {
                unit.setCategoryReason(StringUtils.trimToNull(label.getReason()));
            }
        });

        List<String> unmatched = labels.keySet().stream()
                .filter(labelKey -> BooleanUtils.isFalse(matched.contains(labelKey)))
                .map(labelKey -> labelKey.getLeft() + "#" + labelKey.getRight())
                .toList();
        return new Labelling(labels.size(), resolved.size(), units, unmatched);
    }
    private static String reasoning(LocalReview review, Optional<FindingTriage> triage) {
        Context ctx = new Context();
        ctx.setVariable("tasks", tasks(review.getAnswer()));
        ctx.setVariable("reviewers", review.getReviewers());
        ctx.setVariable("unanswered", CollectionUtils.size(review.getUnansweredReviewers()));
        ctx.setVariable("observations", CollectionUtils.emptyIfNull(review.getObservations()));
        triage.ifPresent(t -> ctx.setVariable("triage", TriageDigest.of(t.getVerdicts())));
        return StringUtils.trimToNull(PromptTemplates.process(TEMPLATE_DIGEST, ctx));
    }
    /**
     * The coordinator's work split, read only for the digest. Nothing checks its shape before this point: the repair
     * round asks only for readable findings and assessment, so a {@code "tasks": null} or a {@code "paths": "core/"}
     * reaches here from an otherwise good answer. Such a plan leaves the areas out of the digest; letting it throw would
     * fail the whole submission over a summary the commit page shows for information only.
     */
    private static List<Task> tasks(String answer) {
        try {
            return CollectionUtils.emptyIfNull(LlmJson.readAnswer(answer, Plan.class).getTasks())
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(task -> CollectionUtils.isNotEmpty(task.getPaths()))
                    .toList();
        } catch (JacksonException err) {
            return List.of();
        }
    }
    private static void addDefects(LlmScoringResponse.Bugs bugs, FindingTriage triage) {
        Map<Pair<String, Integer>, StaticFinding> asked = Maps.newHashMap();
        for (StaticFinding finding : triage.getFindings()) {
            asked.putIfAbsent(bugKey(finding.getFile(), finding.getLine()), finding);
        }
        Set<Pair<String, Integer>> reported = Sets.newHashSet();
        Stream.of(bugs.getBlocking(), bugs.getMajor(), bugs.getMinor())
                .flatMap(List::stream)
                .forEach(bug -> reported.add(bugKey(bug.getFile(), bug.getLine())));
        for (FindingVerdict verdict : triage.getVerdicts()) {
            if (verdict.getVerdict() == FindingVerdict.Verdict.DEFECT && reported.add(bugKey(verdict.getFile(), verdict.getLine()))) {
                LlmScoringResponse.Bug bug = LlmScoringResponse.Bug.builder()
                        .type(verdict.getType())
                        .title(verdict.getRule() + " (" + verdict.getTool() + ")")
                        .description(StringUtils.defaultIfBlank(verdict.getReason(), toolMessage(asked, verdict)))
                        .file(verdict.getFile())
                        .line(verdict.getLine())
                        .suggestedFix(verdict.getSuggestedFix())
                        .confidence(LlmScoringResponse.Confidence.HIGH)
                        .build();
                switch (Optional.ofNullable(verdict.getSeverity()).orElse(FindingVerdict.Severity.MINOR)) {
                    case BLOCKING -> bugs.getBlocking().add(bug);
                    case MAJOR -> bugs.getMajor().add(bug);
                    case MINOR -> bugs.getMinor().add(bug);
                    default -> throw new IllegalArgumentException("unknown defect severity: " + verdict.getSeverity());
                }
            }
        }
    }
    private static LlmScoringResponse.StaticAnalysisReview staticAnalysisReview(FindingTriage triage) {
        Map<FindingKey, StaticFinding> asked = Maps.newHashMap();
        for (StaticFinding finding : triage.getFindings()) {
            asked.putIfAbsent(FindingKey.of(finding.getTool(), finding.getRule(), finding.getFile(), finding.getLine()), finding);
        }

        LlmScoringResponse.StaticAnalysisReview toReturn = new LlmScoringResponse.StaticAnalysisReview();
        for (FindingVerdict verdict : triage.getVerdicts()) {
            StaticFinding finding = asked.remove(FindingKey.of(verdict.getTool(), verdict.getRule(), verdict.getFile(), verdict.getLine()));
            if (BooleanUtils.and(new boolean[] { Objects.nonNull(finding), Objects.nonNull(verdict.getVerdict()) })) {
                boolean pmd = DiagnosticModel.ToolEnum.PMD.getValue().equals(finding.getTool());
                LlmScoringResponse.FindingSeverity toolSeverity = findingSeverity(finding.getSeverity());
                LlmScoringResponse.FindingSeverity severity = toolSeverity;
                List<LlmScoringResponse.StaticAnalysisFinding> list = pmd ? toReturn.getPmdInChangedLines() : toReturn.getSpotbugsInChangedLines();
                if (verdict.getVerdict() == FindingVerdict.Verdict.FALSE_POSITIVE) {
                    list = pmd ? toReturn.getPmdFalsePositives() : toReturn.getSpotbugsFalsePositives();
                    severity = LlmScoringResponse.FindingSeverity.INFO;
                } else if (verdict.getVerdict() == FindingVerdict.Verdict.HARMLESS) {
                    severity = LlmScoringResponse.FindingSeverity.INFO;
                }
                list.add(LlmScoringResponse.StaticAnalysisFinding.builder()
                        .rule(finding.getRule())
                        .file(finding.getFile())
                        .line(finding.getLine())
                        .assessment(verdict.getReason())
                        .severity(severity)
                        .toolSeverity(toolSeverity)
                        .build());
            }
        }
        return toReturn;
    }
    private static LocalAssessmentModel toModel(LlmScoringResponse assessment) {
        LocalAssessmentModel toReturn = new LocalAssessmentModel();
        toReturn.setSummary(assessment.getSummary());
        if (Objects.nonNull(assessment.getTags())) {
            toReturn.setTags(LlmResponseMapper.mapTags(assessment.getTags()));
        }
        if (CollectionUtils.isNotEmpty(assessment.getTaskTypes())) {
            toReturn.setTaskTypes(LlmResponseMapper.mapTaskTypes(assessment.getTaskTypes().stream().filter(Objects::nonNull).toList()));
        }
        /**
         * The 1-10 scale the submission schema declares for every task complexity. The agents are asked for null when a
         * part does not apply, yet one writes 0 or 11 now and then; such a value is not a judgment on the scale, so it is
         * left out rather than sent to a server that may reject the whole submission over it.
         */
        Optional.ofNullable(assessment.getTaskComplexity()).filter(RunArgs.COMPLEXITY_SCALE::contains).ifPresent(toReturn::setTaskComplexity);
        toReturn.setTaskComplexityRationale(assessment.getTaskComplexityRationale());
        Optional.ofNullable(assessment.getTaskComplexityNew()).filter(RunArgs.COMPLEXITY_SCALE::contains).ifPresent(toReturn::setTaskComplexityNew);
        Optional.ofNullable(assessment.getTaskComplexityModified()).filter(RunArgs.COMPLEXITY_SCALE::contains).ifPresent(toReturn::setTaskComplexityModified);
        if (Objects.nonNull(assessment.getQualityDimensions())) {
            toReturn.setQualityDimensions(LlmResponseMapper.mapQualityDimensions(assessment.getQualityDimensions()));
        }

        if (Objects.nonNull(assessment.getQualityMultiplier()) && Objects.nonNull(assessment.getQualityMultiplier().getArchitectureAnalysis())) {
            LlmScoringResponse.ArchitectureAnalysis architecture = assessment.getQualityMultiplier().getArchitectureAnalysis();
            toReturn.setSolidViolations(CollectionUtils.emptyIfNull(architecture.getSolidViolations()).stream().filter(StringUtils::isNotBlank).toList());
            toReturn.setArchitectureIssues(CollectionUtils.emptyIfNull(architecture.getArchitectureIssues()).stream().filter(StringUtils::isNotBlank).toList());
        }

        LlmScoringResponse.BlastRadiusAnalysis blast = assessment.getBlastRadiusAnalysis();
        if (Objects.nonNull(blast)) {
            if (Objects.nonNull(blast.getModuleType())) {
                toReturn.setModuleType(LocalAssessmentModel.ModuleTypeEnum.fromValue(blast.getModuleType().name().toLowerCase(Locale.ROOT)));
            }
            if (Objects.nonNull(blast.getSignatureChanges())) {
                toReturn.setSignatureChanges(LlmResponseMapper.mapSignatureChanges(blast.getSignatureChanges()));
            }
            toReturn.setBlastRadiusExplanation(StringUtils.trimToNull(blast.getExplanation()));
        }

        if (RunArgs.SCORE_SCALE.contains(assessment.getRequiresSeniorReview())) {
            toReturn.setRequiresSeniorReview(assessment.getRequiresSeniorReview());
        }
        toReturn.setSeniorReviewReasons(CollectionUtils.emptyIfNull(assessment.getSeniorReviewReasons()).stream().filter(StringUtils::isNotBlank).toList());
        return toReturn;
    }
    private static LlmScoringResponse.FindingSeverity findingSeverity(String toolSeverity) {
        if (DiagnosticModel.SeverityEnum.ERROR.getValue().equalsIgnoreCase(toolSeverity)) {
            return LlmScoringResponse.FindingSeverity.ERROR;
        }
        if (DiagnosticModel.SeverityEnum.WARNING.getValue().equalsIgnoreCase(toolSeverity)) {
            return LlmScoringResponse.FindingSeverity.WARNING;
        }
        return LlmScoringResponse.FindingSeverity.INFO;
    }
    private static List<BugModel> complete(List<BugModel> bugs) {
        return bugs.stream().filter(bug -> StringUtils.isNoneBlank(bug.getTitle(), bug.getDescription())).toList();
    }
    private static String toolMessage(Map<Pair<String, Integer>, StaticFinding> asked, FindingVerdict verdict) {
        StaticFinding finding = asked.get(bugKey(verdict.getFile(), verdict.getLine()));
        return Objects.isNull(finding) ? null : finding.getMessage();
    }
    private static Pair<String, Integer> bugKey(String file, Integer line) {
        return Pair.of(FilenameUtils.separatorsToUnix(StringUtils.defaultString(file)), line);
    }
    private static LocalReviewSessionModel toModel(SessionUsage session) {
        LocalReviewSessionModel toReturn = new LocalReviewSessionModel();
        toReturn.setAgent(session.getAgent());
        toReturn.setModel(session.getModel());
        toReturn.setInputTokens(session.getInputTokens());
        toReturn.setCachedInputTokens(session.getCachedInputTokens());
        toReturn.setOutputTokens(session.getOutputTokens());
        toReturn.setReasoningTokens(session.getReasoningTokens());
        return toReturn;
    }
    private static Pair<String, String> key(String file, String signature) {
        String path = FilenameUtils.separatorsToUnix(Paths.get(StringUtils.defaultString(file)).normalize().toString());
        return Pair.of(path, JavaSignatures.comparable(StringUtils.defaultString(signature), FilenameUtils.getBaseName(path)));
    }

    /**
     * What applying the review's labels did: the labels the reviewers returned, the code units they labelled, the
     * submission's units a review labels (added or changed), and the labels that named no such unit
     * (file#signature, as compared), which is the evidence for signature or path drift between the reviewers and the
     * analysis.
     */
    @Value
    public static class Labelling {
        int returned;
        int applied;
        int units;
        List<String> unmatched;
    }
    @Value
    public static class TriageDigest {
        int checked;
        long defects;
        long falsePositives;
        long harmless;
        List<FindingVerdict> defectVerdicts;

        private static TriageDigest of(List<FindingVerdict> verdicts) {
            Map<FindingVerdict.Verdict, Long> counts = verdicts.stream()
                    .filter(verdict -> Objects.nonNull(verdict.getVerdict()))
                    .collect(Collectors.groupingBy(FindingVerdict::getVerdict, () -> Maps.newEnumMap(FindingVerdict.Verdict.class), Collectors.counting()));
            return new TriageDigest(verdicts.size(),
                    counts.getOrDefault(FindingVerdict.Verdict.DEFECT, 0L),
                    counts.getOrDefault(FindingVerdict.Verdict.FALSE_POSITIVE, 0L),
                    counts.getOrDefault(FindingVerdict.Verdict.HARMLESS, 0L),
                    verdicts.stream().filter(verdict -> verdict.getVerdict() == FindingVerdict.Verdict.DEFECT).toList());
        }
    }

    @Data
    @NoArgsConstructor
    private static class Plan {
        private List<Task> tasks = Lists.newArrayList();
    }

    @Data
    @NoArgsConstructor
    public static class Task {
        private List<String> paths = Lists.newArrayList();
        private int findings;
    }
}
