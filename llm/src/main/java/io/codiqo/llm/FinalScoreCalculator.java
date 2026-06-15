package io.codiqo.llm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Precision;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.VolumeScoreCalculator.CodeBlockEffort;
import io.codiqo.llm.VolumeScoreCalculator.FileEffort;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockEffortView;
import io.codiqo.llm.schema.LlmScoringResponse.DiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.FileDiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.FileEffortView;
import io.codiqo.llm.schema.LlmScoringResponse.LinePair;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.VolumeScore;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FinalScoreCalculator {
    private static final int ROUNDING_PRECISION = 2;
    private static final int MAX_ARCHITECTURE_IMPACT = 10;

    private final RunArgs args;
    private final VolumeScoreCalculator volumeScoreCalculator;

    public FinalScoreCalculator(RunArgs args) {
        this.args = Objects.requireNonNull(args);
        this.volumeScoreCalculator = new VolumeScoreCalculator(args);
    }

    public void apply(LlmScoringResponse response, PreComputedScores preComputed) {
        apply(response, preComputed, null);
    }
    public void apply(LlmScoringResponse response, PreComputedScores preComputed, LlmScoringRequest request) {
        DiffClassificationDeriver.derive(response, request);
        DiffAdjustment adjustment = computeDiffAdjustment(response, preComputed, request);
        PreComputedScores effective = adjustment.getScores();
        Map<String, FileDiffClassification> classificationByFile = buildClassificationByFile(response);

        double baseEffort = effective.getBaseEffort();
        if (Objects.nonNull(response.getEffortBreakdown()) && Objects.nonNull(response.getEffortBreakdown().getComplexityMultiplier())) {
            double llmComplexity = response.getEffortBreakdown().getComplexityMultiplier().getCombinedMultiplier();
            if (llmComplexity > 0) {
                baseEffort = effective.getVolumeScore() * llmComplexity;
            }
        }
        if (Objects.nonNull(response.getEffortBreakdown())) {
            response.getEffortBreakdown().setBaseEffortScore(Precision.round(baseEffort, ROUNDING_PRECISION));
            response.getEffortBreakdown().setVolumeScore(toVolumeScore(effective, adjustment));
            response.getEffortBreakdown().setFileEfforts(
                    effective.getFileEfforts().stream()
                            .map(fe -> toFileEffortView(fe, classificationByFile))
                            .collect(Collectors.toList()));
        }

        double rawQualityMultiplier = 1.0;
        if (Objects.nonNull(response.getQualityMultiplier())) {
            rawQualityMultiplier = response.getQualityMultiplier().getFinalMultiplier();
        }

        double clampedQualityMultiplier = Math.max(
                args.getQualityMultiplierMin(),
                Math.min(args.getQualityMultiplierMax(), rawQualityMultiplier));
        if (Objects.isNull(response.getQualityMultiplier())) {
            response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(clampedQualityMultiplier).build());
        } else {
            response.getQualityMultiplier().setFinalMultiplier(clampedQualityMultiplier);
        }

        int architectureImpactScore = 0;
        double qualityFactor = 1.0;
        if (Objects.nonNull(response.getArchitectureEffortBonus())) {
            architectureImpactScore = Math.max(0, Math.min(MAX_ARCHITECTURE_IMPACT, response.getArchitectureEffortBonus().getArchitectureImpactScore()));
            qualityFactor = response.getArchitectureEffortBonus().getQualityFactor();
            qualityFactor = Math.max(0.0, Math.min(1.0, qualityFactor));
        }

        double architectureBonus = architectureImpactScore * baseEffort * args.getArchitectureBonusFactor() * qualityFactor;
        architectureBonus = Precision.round(architectureBonus, ROUNDING_PRECISION);
        String bonusCalculation = String.format(
                "Impact Score (%d/10) × Base Effort (%.2f) × Bonus Factor (%.3f) × Quality Factor (%.2f) = +%.2f",
                architectureImpactScore,
                baseEffort,
                args.getArchitectureBonusFactor(),
                qualityFactor,
                architectureBonus);

        if (Objects.isNull(response.getArchitectureEffortBonus())) {
            response.setArchitectureEffortBonus(ArchitectureEffortBonus.builder()
                    .architectureImpactScore(architectureImpactScore)
                    .qualityFactor(qualityFactor)
                    .baseEffort(Precision.round(baseEffort, ROUNDING_PRECISION))
                    .bonusCalculation(bonusCalculation)
                    .bonusPoints(architectureBonus)
                    .build());
        } else {
            ArchitectureEffortBonus bonus = response.getArchitectureEffortBonus();
            bonus.setQualityFactor(qualityFactor);
            bonus.setBaseEffort(Precision.round(baseEffort, ROUNDING_PRECISION));
            bonus.setBonusCalculation(bonusCalculation);
            bonus.setBonusPoints(architectureBonus);
        }

        double finalScore = baseEffort * clampedQualityMultiplier + architectureBonus;
        finalScore = Math.round(finalScore);
        String scoreCalculation = String.format("%.2f × %.2f + %.2f = %.2f ≈ %.0f",
                baseEffort,
                clampedQualityMultiplier,
                architectureBonus,
                baseEffort * clampedQualityMultiplier + architectureBonus,
                finalScore);
        response.setScore(finalScore);
        response.setScoreCalculation(scoreCalculation);
    }
    private DiffAdjustment computeDiffAdjustment(LlmScoringResponse response, PreComputedScores preComputed, LlmScoringRequest request) {
        if (Objects.isNull(response.getEffortBreakdown()) || Objects.isNull(response.getEffortBreakdown().getDiffClassification())) {
            return DiffAdjustment.unchanged(preComputed);
        }
        if (Objects.isNull(request) || CollectionUtils.isEmpty(request.getFileChanges())) {
            return DiffAdjustment.unchanged(preComputed);
        }

        DiffClassification classification = response.getEffortBreakdown().getDiffClassification();
        if (CollectionUtils.isEmpty(classification.getPerFile())) {
            return DiffAdjustment.unchanged(preComputed);
        }

        Map<String, FileChange> fileChangesByPath = Maps.newHashMapWithExpectedSize(request.getFileChanges().size());
        for (FileChange fc : request.getFileChanges()) {
            fileChangesByPath.put(fc.getPath(), fc);
        }

        populatePerFileScalars(classification);

        Map<String, Double> perFileFactor = Maps.newHashMap();
        int totalCosmetic = 0;
        int totalPairsCollapsed = 0;
        int totalRawLines = 0;
        int totalAdjustedLines = 0;
        for (FileDiffClassification entry : classification.getPerFile()) {
            FileChange fc = fileChangesByPath.get(entry.getFile());
            if (Objects.isNull(fc)) {
                log.debug("diffClassification.skipReason=unknownFile file='{}'", entry.getFile());
                continue;
            }
            if (!fc.isLinesJustificationRequired()) {
                log.debug("diffClassification.skipReason=notEligible file='{}' language='{}'", entry.getFile(), fc.getLanguage());
                continue;
            }

            int cosmeticAddedSize = sizeOfInts(entry.getCosmeticAdded());
            int cosmeticDeletedSize = sizeOfInts(entry.getCosmeticDeleted());
            int inPlacePairs = sizeOfPairs(entry.getInPlaceModifyPairs());
            int trueModifyPairs = sizeOfPairs(entry.getTrueModifyPairs());
            int pureAddSize = sizeOfInts(entry.getPureAdd());
            int pureDeleteSize = sizeOfInts(entry.getPureDelete());

            int addedTotal = cosmeticAddedSize + inPlacePairs + trueModifyPairs + pureAddSize;
            int deletedTotal = cosmeticDeletedSize + inPlacePairs + trueModifyPairs + pureDeleteSize;

            // entries are server-derived from the diff (DiffClassificationDeriver), so totals match
            // the effective targets by construction — a mismatch means candidate filtering drifted
            // from DiffStats.categorize and the file's factor can't be trusted
            if (addedTotal != fc.getLinesAdded()) {
                log.warn("diffClassification.skipReason=addedTotalMismatch file='{}' addedTotal={} linesAdded={}",
                        entry.getFile(),
                        addedTotal,
                        fc.getLinesAdded());
                continue;
            }
            if (deletedTotal != fc.getLinesDeleted()) {
                log.warn("diffClassification.skipReason=deletedTotalMismatch file='{}' deletedTotal={} linesDeleted={}",
                        entry.getFile(),
                        deletedTotal,
                        fc.getLinesDeleted());
                continue;
            }

            int rawLines = fc.getLinesAdded() + fc.getLinesDeleted();
            if (rawLines == 0) {
                continue;
            }

            int pairsCount = inPlacePairs + trueModifyPairs;
            int effectiveLines = pairsCount + pureAddSize + pureDeleteSize;
            double factor = (double) effectiveLines / rawLines;
            perFileFactor.put(entry.getFile(), factor);

            totalCosmetic += cosmeticAddedSize + cosmeticDeletedSize;
            totalPairsCollapsed += pairsCount;
            totalRawLines += rawLines;
            totalAdjustedLines += effectiveLines;
        }

        if (perFileFactor.isEmpty()) {
            return DiffAdjustment.unchanged(preComputed);
        }

        populatePerFileScalars(classification);

        PreComputedScores adjusted = volumeScoreCalculator.recompute(preComputed, perFileFactor);
        DiffBookkeeping bookkeeping = new DiffBookkeeping(totalRawLines, totalAdjustedLines, totalCosmetic, totalPairsCollapsed);
        return DiffAdjustment.applied(adjusted, bookkeeping);
    }
    private static void populatePerFileScalars(DiffClassification classification) {
        for (FileDiffClassification entry : classification.getPerFile()) {
            int cosmeticForFile = sizeOfInts(entry.getCosmeticAdded()) + sizeOfInts(entry.getCosmeticDeleted());
            int pairsForFile = sizeOfPairs(entry.getInPlaceModifyPairs()) + sizeOfPairs(entry.getTrueModifyPairs());
            int pureAddDeleteForFile = sizeOfInts(entry.getPureAdd()) + sizeOfInts(entry.getPureDelete());

            entry.setCosmeticLines(cosmeticForFile);
            entry.setPairsCollapsed(pairsForFile);
            entry.setPureAddDeleteLines(pureAddDeleteForFile);
        }

        classification.setCosmeticLines(classification.getPerFile().stream().mapToInt(FileDiffClassification::getCosmeticLines).sum());
        classification.setPairsCollapsed(classification.getPerFile().stream().mapToInt(FileDiffClassification::getPairsCollapsed).sum());
        classification.setPureAddDeleteLines(classification.getPerFile().stream().mapToInt(FileDiffClassification::getPureAddDeleteLines).sum());
    }
    private static Map<String, FileDiffClassification> buildClassificationByFile(LlmScoringResponse response) {
        if (Objects.isNull(response.getEffortBreakdown()) || Objects.isNull(response.getEffortBreakdown().getDiffClassification())) {
            return Maps.newHashMap();
        }
        List<FileDiffClassification> perFile = response.getEffortBreakdown().getDiffClassification().getPerFile();
        if (CollectionUtils.isEmpty(perFile)) {
            return Maps.newHashMap();
        }
        Map<String, FileDiffClassification> toReturn = Maps.newHashMapWithExpectedSize(perFile.size());
        for (FileDiffClassification entry : perFile) {
            toReturn.put(entry.getFile(), entry);
        }
        return toReturn;
    }
    private static VolumeScore toVolumeScore(PreComputedScores effective, DiffAdjustment adjustment) {
        VolumeScore toReturn = VolumeScore.builder()
                .linesChanged(effective.getLinesChanged())
                .filesChanged(effective.getFilesChanged())
                .filesScopeMultiplier(Precision.round(effective.getFilesScopeMultiplier(), ROUNDING_PRECISION))
                .codeBlocksModified(effective.getCodeBlocksModified())
                .codeBlocksAdded(effective.getCodeBlocksAdded())
                .classesModified(effective.getClassesModified())
                .classesAdded(effective.getClassesAdded())
                .blockEffortSum(Precision.round(effective.getBlockEffortSum(), ROUNDING_PRECISION))
                .totalEffortRaw(Precision.round(effective.getTotalEffortRaw(), ROUNDING_PRECISION))
                .totalBaseline(Precision.round(effective.getTotalBaseline(), ROUNDING_PRECISION))
                .globalCap(Precision.round(effective.getGlobalCap(), ROUNDING_PRECISION))
                .globalCapApplied(effective.isGlobalCapApplied())
                .globalCapDryRun(effective.isGlobalCapDryRun())
                .sizeFactor(Precision.round(effective.getSizeFactor(), ROUNDING_PRECISION))
                .modifyMultiplier(Precision.round(effective.getModifyMult(), ROUNDING_PRECISION))
                .addMultiplier(Precision.round(effective.getAddMult(), ROUNDING_PRECISION))
                .totalVolumeScore(Precision.round(effective.getVolumeScore(), ROUNDING_PRECISION))
                .build();
        DiffBookkeeping bookkeeping = adjustment.getBookkeeping();
        toReturn.setLinesChangedRaw(bookkeeping.getLinesChangedRaw());
        toReturn.setLinesChangedAdjusted(bookkeeping.getLinesChangedAdjusted());
        toReturn.setCosmeticLinesDropped(bookkeeping.getCosmeticLinesDropped());
        toReturn.setInPlaceLinesCollapsed(bookkeeping.getInPlaceLinesCollapsed());
        return toReturn;
    }
    private static FileEffortView toFileEffortView(FileEffort fe, Map<String, FileDiffClassification> classificationByFile) {
        FileDiffClassification fileClassification = classificationByFile.get(fe.getFile());
        List<CodeBlockEffort> blocks = fe.getCodeBlockEfforts();
        int[] collapsedPairs = collapsedPairsPerBlock(fileClassification, blocks);

        List<CodeBlockEffortView> blockViews = Lists.newArrayListWithCapacity(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            blockViews.add(toCodeBlockEffortView(blocks.get(i), collapsedPairs[i]));
        }

        return FileEffortView.builder()
                .file(fe.getFile())
                .totalEffort(Precision.round(fe.getTotalEffort(), ROUNDING_PRECISION))
                .isTest(fe.isTest())
                .codeBlockEfforts(blockViews)
                .blocksFlaggedAsRatioOutlier(fe.getBlocksFlaggedAsRatioOutlier())
                .blocksFlaggedAsGlobalCapDriver(fe.getBlocksFlaggedAsGlobalCapDriver())
                .maxBlockRatioDeviationNcss(Precision.round(fe.getMaxBlockRatioDeviationNcss(), ROUNDING_PRECISION))
                .maxBlockRatioDeviationInvocations(Precision.round(fe.getMaxBlockRatioDeviationInvocations(), ROUNDING_PRECISION))
                .fileFlaggedAsAbusive(fe.isFileFlaggedAsAbusive())
                .build();
    }
    private static CodeBlockEffortView toCodeBlockEffortView(CodeBlockEffort cbe, int collapsedPairs) {
        int effectiveLinesChanged = Math.max(0, cbe.getEffectiveLinesChanged() - collapsedPairs);
        double changeRatio = cbe.getChangeRatio();
        if (collapsedPairs > 0 && cbe.getBodyCodeLines() > 0) {
            changeRatio = Math.min(1.0, (double) effectiveLinesChanged / cbe.getBodyCodeLines());
            changeRatio = Precision.round(changeRatio, ROUNDING_PRECISION);
        }
        return CodeBlockEffortView.builder()
                .name(cbe.getName())
                .signature(cbe.getSignature())
                .operation(cbe.getOperation().name())
                .nonCommentCodeStatements(cbe.getNonCommentCodeStatements())
                .directInvocationCount(cbe.getDirectInvocationCount())
                .effectiveInvocationsChanged(cbe.getEffectiveInvocationsChanged())
                .nonCommentCodeLines(cbe.getNonCommentCodeLines())
                .commentLines(cbe.getCommentLines())
                .effectiveLinesChanged(effectiveLinesChanged)
                .changeRatio(changeRatio)
                .scaledLines(cbe.getScaledLines())
                .scaledNcss(cbe.getScaledNcss())
                .scaledInvocations(cbe.getScaledInvocations())
                .driverScore(Precision.round(cbe.getDriverScore(), ROUNDING_PRECISION))
                .cappedStatements(cbe.getCappedStatements())
                .effort(Precision.round(cbe.getEffort(), ROUNDING_PRECISION))
                .isTest(cbe.isTest())
                .blockRatioDeviationNcss(cbe.getBlockRatioDeviationNcss())
                .blockRatioDeviationInvocations(cbe.getBlockRatioDeviationInvocations())
                .blockRatioOutlier(cbe.isBlockRatioOutlier())
                .effortShare(Precision.round(cbe.getEffortShare(), ROUNDING_PRECISION))
                .globalCapDriver(cbe.isGlobalCapDriver())
                .build();
    }
    private static int[] collapsedPairsPerBlock(FileDiffClassification fileClassification, List<CodeBlockEffort> blocks) {
        int[] toReturn = new int[blocks.size()];
        if (Objects.isNull(fileClassification)) {
            return toReturn;
        }
        assignPairsToInnermostBlock(fileClassification.getInPlaceModifyPairs(), blocks, toReturn);
        assignPairsToInnermostBlock(fileClassification.getTrueModifyPairs(), blocks, toReturn);
        return toReturn;
    }
    /** A pair inside a nested block also falls within the enclosing block's body range — only the innermost block may collapse it. */
    private static void assignPairsToInnermostBlock(List<LinePair> pairs, List<CodeBlockEffort> blocks, int[] counts) {
        for (LinePair pair : CollectionUtils.emptyIfNull(pairs)) {
            int innermost = -1;
            for (int i = 0; i < blocks.size(); i++) {
                if (blockContainsPair(blocks.get(i), pair)) {
                    if (innermost < 0 || blockBodySpan(blocks.get(i)) < blockBodySpan(blocks.get(innermost))) {
                        innermost = i;
                    }
                }
            }
            if (innermost >= 0) {
                counts[innermost]++;
            }
        }
    }
    private static boolean blockContainsPair(CodeBlockEffort block, LinePair pair) {
        int bodyStartLine = block.getBodyStartLine();
        int bodyEndLine = block.getBodyEndLine();
        if (bodyStartLine <= 0 || bodyEndLine < bodyStartLine) {
            return false;
        }
        return pair.getAdded() >= bodyStartLine && pair.getAdded() <= bodyEndLine
                && pair.getDeleted() >= bodyStartLine && pair.getDeleted() <= bodyEndLine;
    }
    private static int blockBodySpan(CodeBlockEffort block) {
        return block.getBodyEndLine() - block.getBodyStartLine();
    }
    private static int sizeOfInts(List<Integer> list) {
        return Objects.isNull(list) ? 0 : list.size();
    }
    private static int sizeOfPairs(List<LinePair> list) {
        return Objects.isNull(list) ? 0 : list.size();
    }

    /**
     * Validates the LLM's semantic labels against the diff's change blocks: every cited block id
     * must exist and every cosmetic line number must be an effective changed line. Pairing and
     * pure buckets are server-derived ({@link DiffClassificationDeriver}), so the old count and
     * duplicate invariants cannot be violated and are no longer checked.
     */
    public static ValidationReport validate(LlmScoringResponse response, LlmScoringRequest request) {
        List<ValidationFailure> failures = Lists.newArrayList();

        if (Objects.isNull(response.getEffortBreakdown()) || Objects.isNull(response.getEffortBreakdown().getDiffClassification())) {
            return new ValidationReport(failures);
        }
        // explicit "perFile": null from the LLM bypasses the @Builder.Default empty list
        if (CollectionUtils.isEmpty(response.getEffortBreakdown().getDiffClassification().getPerFile())) {
            return new ValidationReport(failures);
        }
        if (CollectionUtils.isEmpty(request.getFileChanges())) {
            return new ValidationReport(failures);
        }

        Map<String, FileChange> fileChangesByPath = Maps.newHashMapWithExpectedSize(request.getFileChanges().size());
        for (FileChange fc : request.getFileChanges()) {
            fileChangesByPath.put(fc.getPath(), fc);
        }

        for (FileDiffClassification entry : response.getEffortBreakdown().getDiffClassification().getPerFile()) {
            FileChange fc = fileChangesByPath.get(entry.getFile());
            if (Objects.isNull(fc) || !fc.isLinesJustificationRequired() || StringUtils.isBlank(fc.getDiff())) {
                continue;
            }

            UnifiedDiffLines diffLines = UnifiedDiffLines.parse(fc.getDiff(), fc.isLinesJustificationRequired());

            Set<String> validBlockIds = Sets.newLinkedHashSet();
            for (UnifiedDiffLines.ChangeBlock block : diffLines.getBlocks()) {
                validBlockIds.add(block.getId());
            }
            List<String> unknownBlocks = Lists.newArrayList();
            if (MapUtils.isNotEmpty(entry.getBlockKinds())) {
                for (String blockId : entry.getBlockKinds().keySet()) {
                    if (!validBlockIds.contains(blockId)) {
                        unknownBlocks.add(blockId);
                    }
                }
            }
            if (CollectionUtils.isNotEmpty(unknownBlocks)) {
                failures.add(new ValidationFailure(entry.getFile(), FailureReason.UNKNOWN_BLOCK,
                        unknownBlocks, Lists.newArrayList(validBlockIds)));
            }

            List<String> unknownAdded = unknownLines(entry.getCosmeticAdded(), diffLines.getCandidateAddedLines());
            if (CollectionUtils.isNotEmpty(unknownAdded)) {
                failures.add(new ValidationFailure(entry.getFile(), FailureReason.UNKNOWN_ADDED_LINE,
                        unknownAdded, asStrings(diffLines.getCandidateAddedLines())));
            }
            List<String> unknownDeleted = unknownLines(entry.getCosmeticDeleted(), diffLines.getCandidateDeletedLines());
            if (CollectionUtils.isNotEmpty(unknownDeleted)) {
                failures.add(new ValidationFailure(entry.getFile(), FailureReason.UNKNOWN_DELETED_LINE,
                        unknownDeleted, asStrings(diffLines.getCandidateDeletedLines())));
            }
        }

        return new ValidationReport(failures);
    }
    private static List<String> unknownLines(List<Integer> cited, Set<Integer> valid) {
        List<String> unknown = Lists.newArrayList();
        for (Integer line : CollectionUtils.emptyIfNull(cited)) {
            if (Objects.isNull(line) || !valid.contains(line)) {
                unknown.add(String.valueOf(line));
            }
        }
        return unknown;
    }
    private static List<String> asStrings(Set<Integer> lines) {
        return lines.stream().map(String::valueOf).collect(Collectors.toList());
    }

    public enum FailureReason {
        UNKNOWN_BLOCK,
        UNKNOWN_ADDED_LINE,
        UNKNOWN_DELETED_LINE,
    }

    @Value
    public static class ValidationFailure {
        String filePath;
        FailureReason reason;
        // what the LLM cited that does not exist (block ids or line numbers, as strings), and the
        // complete valid set for that dimension — both rendered into the retry feedback
        List<String> offending;
        List<String> valid;
    }

    @Value
    public static class ValidationReport {
        List<ValidationFailure> failures;

        public boolean hasFailures() {
            return CollectionUtils.isNotEmpty(failures);
        }
    }

    @Value
    private static class DiffAdjustment {
        PreComputedScores scores;
        DiffBookkeeping bookkeeping;

        static DiffAdjustment unchanged(PreComputedScores preComputed) {
            return new DiffAdjustment(preComputed, DiffBookkeeping.zero());
        }
        static DiffAdjustment applied(PreComputedScores adjusted, DiffBookkeeping bookkeeping) {
            return new DiffAdjustment(adjusted, bookkeeping);
        }
    }

    @Value
    private static class DiffBookkeeping {
        int linesChangedRaw;
        int linesChangedAdjusted;
        int cosmeticLinesDropped;
        int inPlaceLinesCollapsed;

        static DiffBookkeeping zero() {
            return new DiffBookkeeping(0, 0, 0, 0);
        }
    }
}
