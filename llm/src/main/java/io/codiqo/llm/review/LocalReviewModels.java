package io.codiqo.llm.review;

import static java.util.function.Predicate.not;

import java.nio.file.Paths;
import java.util.Collection;
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

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.api.review.UnitName;
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
import io.codiqo.llm.StaticAnalysisLists;
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
    /**
     * A label names its unit by file and member, compared in the form the file's language gives both, and the containers
     * the reviewer wrote choose among the file's added or changed units of that member, in three rounds: first the
     * labels whose containers name exactly one unit, then those that name one once an anonymous class is read as any
     * name, and last a label whose member only one unit has, whatever containers it wrote. A unit an earlier round
     * labelled is not offered again, so a label naming a unit exactly is never displaced by one that only guessed it.
     * Several anonymous units the containers cannot tell apart (two enum constants' bodies) carry nothing in their
     * descriptors a reviewer could write, but the compiler numbers them in source order, the order reviewers list them
     * in: each label takes the first of them still free, and the ones no label took share the last label given to the
     * group. Several named ones take nothing, rather than a label each was not given. A label repeated for one name
     * keeps its last category, as the scoring does.
     */
    public Labelling applyBlockCategories(AnalysisSubmissionModel submission, LocalReview review, Collection<ReviewLanguage> languages) {
        ListMultimap<Pair<String, String>, Pair<UnitName, CodeUnitModel>> byMember = MultimapBuilder.hashKeys().arrayListValues().build();
        int units = 0;
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            String path = normalized(file.getPath());
            ReviewLanguage language = ReviewLanguages.of(path, languages);
            for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (LABELLED_OPERATIONS.contains(unit.getOperation())) {
                    units++;
                    UnitName name = language.unit(unit.getName(), unit.getSignature(), path);
                    byMember.put(Pair.of(path, name.getMember()), Pair.of(name, unit));
                }
            }
        }

        Map<Pair<String, UnitName>, LlmScoringResponse.CodeBlockCategoryView> labels = Maps.newLinkedHashMap();
        for (LlmScoringResponse.CodeBlockCategoryView view : CollectionUtils.emptyIfNull(review.getAssessment().getBlockCategories())) {
            if (Objects.nonNull(view.getCategory())) {
                String path = normalized(view.getFile());
                labels.put(Pair.of(path, ReviewLanguages.of(path, languages).labelled(view.getSignature(), path)), view);
            }
        }

        Map<CodeUnitModel, LlmScoringResponse.CodeBlockCategoryView> resolved = new IdentityHashMap<>();
        Set<CodeUnitModel> claimed = Sets.newIdentityHashSet();
        Map<CodeUnitModel, LlmScoringResponse.CodeBlockCategoryView> shared = new IdentityHashMap<>();
        Map<Pair<String, UnitName>, LlmScoringResponse.CodeBlockCategoryView> pending = Maps.newLinkedHashMap(labels);
        for (EnumSet<UnitName.Match> accepted : List.of(EnumSet.of(UnitName.Match.EXACT), EnumSet.of(UnitName.Match.EXACT, UnitName.Match.LOOSE))) {
            pending.entrySet().removeIf(entry -> {
                UnitName named = entry.getKey().getRight();
                List<Pair<UnitName, CodeUnitModel>> candidates = byMember.get(Pair.of(entry.getKey().getLeft(), named.getMember())).stream()
                        .filter(candidate -> accepted.contains(candidate.getLeft().match(named)))
                        .filter(not(candidate -> claimed.contains(candidate.getRight())))
                        .toList();
                boolean chosen = BooleanUtils.or(new boolean[] { candidates.size() == 1, candidates.size() > 1 && candidates.stream().allMatch(candidate -> candidate.getLeft().isAnonymous()) });
                if (chosen) {
                    claim(candidates.getFirst().getRight(), entry.getValue(), claimed, resolved);
                    candidates.stream().skip(1).forEach(candidate -> shared.put(candidate.getRight(), entry.getValue()));
                }
                return chosen;
            });
        }

        List<String> unmatched = Lists.newArrayList();
        pending.forEach((labelKey, label) -> {
            UnitName named = labelKey.getRight();
            List<Pair<UnitName, CodeUnitModel>> candidates = byMember.get(Pair.of(labelKey.getLeft(), named.getMember()));
            if (candidates.size() == 1 && claim(candidates.getFirst().getRight(), label, claimed, resolved)) {
                return;
            }
            String container = named.getContainer().stream().map(step -> step + '.').collect(Collectors.joining());
            unmatched.add(labelKey.getLeft() + "#" + container + named.getMember());
        });
        shared.forEach((unit, label) -> claim(unit, label, claimed, resolved));
        resolved.forEach((unit, label) -> {
            unit.setCategory(CodeUnitModel.CategoryEnum.fromValue(label.getCategory().name()));
            /** a reason is asked for only above MECHANICAL; one the model gave anyway says nothing a reader needs */
            if (label.getCategory() != LlmScoringResponse.CodeBlockCategory.MECHANICAL) {
                unit.setCategoryReason(StringUtils.trimToNull(label.getReason()));
            }
        });
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
                StaticAnalysisLists.of(finding.getTool()).ifPresent(lists -> place(toReturn, lists, finding, verdict));
            }
        }
        return toReturn;
    }
    /**
     * A verdict goes into its tool's list, a false positive into the tool's false-positive list. A finding of a tool
     * without lists ({@link StaticAnalysisLists}) has no place: {@link StaticFindings} never asks about one, and one a
     * user-supplied findings file carries is left out of the review rather than failing the submission after the triage.
     */
    private static void place(LlmScoringResponse.StaticAnalysisReview review, StaticAnalysisLists.ToolLists lists, StaticFinding finding, FindingVerdict verdict) {
        LlmScoringResponse.FindingSeverity toolSeverity = findingSeverity(finding.getSeverity());
        LlmScoringResponse.FindingSeverity severity = toolSeverity;
        boolean falsePositive = verdict.getVerdict() == FindingVerdict.Verdict.FALSE_POSITIVE;
        if (falsePositive) {
            severity = LlmScoringResponse.FindingSeverity.INFO;
        } else if (verdict.getVerdict() == FindingVerdict.Verdict.HARMLESS) {
            severity = LlmScoringResponse.FindingSeverity.INFO;
        }
        (falsePositive ? lists.getFalsePositives() : lists.getInChangedLines()).apply(review).add(LlmScoringResponse.StaticAnalysisFinding.builder()
                .rule(finding.getRule())
                .file(finding.getFile())
                .line(finding.getLine())
                .assessment(verdict.getReason())
                .severity(severity)
                .toolSeverity(toolSeverity)
                .build());
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
    /**
     * A unit a label names is not offered to another label, and takes the label unless it was labelled before the
     * review. Whether the unit was still free to claim is returned.
     */
    private static boolean claim(CodeUnitModel unit, LlmScoringResponse.CodeBlockCategoryView label, Set<CodeUnitModel> claimed, Map<CodeUnitModel, LlmScoringResponse.CodeBlockCategoryView> resolved) {
        boolean toReturn = claimed.add(unit);
        if (BooleanUtils.and(new boolean[] { toReturn, Objects.isNull(unit.getCategory()) })) {
            resolved.put(unit, label);
        }
        return toReturn;
    }
    private static String normalized(String file) {
        return FilenameUtils.separatorsToUnix(Paths.get(StringUtils.defaultString(file)).normalize().toString());
    }

    /**
     * What applying the review's labels did: the labels the reviewers returned, the code units they labelled, the
     * submission's units a review labels (added or changed), and the labels that named no such unit, or several without
     * a container to choose between them (file#signature, as compared), which is the evidence for signature or
     * path drift between the reviewers and the analysis.
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
