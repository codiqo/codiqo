package io.codiqo.llm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.math3.util.Precision;

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
import io.codiqo.llm.schema.LlmScoringResponse.LineGroups;
import io.codiqo.llm.schema.LlmScoringResponse.FileEffortView;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.VolumeScore;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FinalScoreCalculator {
    private static final int ROUNDING_PRECISION = 2;

    private final RunArgs args;
    private final VolumeScoreCalculator volumeScoreCalculator;

    public FinalScoreCalculator(RunArgs args) {
        this.args = args;
        this.volumeScoreCalculator = new VolumeScoreCalculator(args);
    }

    public void apply(LlmScoringResponse response, PreComputedScores preComputed) {
        apply(response, preComputed, null);
    }
    public void apply(LlmScoringResponse response, PreComputedScores preComputed, LlmScoringRequest request) {
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
            response.getEffortBreakdown().setVolumeScore(toVolumeScore(effective, preComputed, adjustment));
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
            architectureImpactScore = response.getArchitectureEffortBonus().getArchitectureImpactScore();
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

        Map<String, FileChange> fileChangesByPath = Maps.newHashMapWithExpectedSize(request.getFileChanges().size());
        for (FileChange fc : request.getFileChanges()) {
            fileChangesByPath.put(fc.getPath(), fc);
        }

        populatePerFileScalars(classification);

        Map<String, Double> perFileFactor = Maps.newHashMap();
        int totalCosmetic = 0;
        int totalInPlaceCollapsed = 0;
        int totalRawLines = 0;
        int totalAdjustedLines = 0;
        boolean fallback = false;
        for (FileDiffClassification entry : classification.getPerFile()) {
            FileChange fc = fileChangesByPath.get(entry.getFile());
            if (Objects.isNull(fc)) {
                log.warn("diffClassification.skipReason=unknownFile file='{}'; entry skipped, valid entries still applied", entry.getFile());
                continue;
            }
            if (!fc.isLinesJustificationRequired()) {
                log.warn("diffClassification.skipReason=notEligible file='{}' language='{}'; entry skipped (config/non-code file)", entry.getFile(), fc.getLanguage());
                continue;
            }

            LineGroups added = Optional.ofNullable(entry.getAdded()).orElseGet(() -> LineGroups.builder().build());
            LineGroups deleted = Optional.ofNullable(entry.getDeleted()).orElseGet(() -> LineGroups.builder().build());

            int addedCosmetic = sizeOf(added.getCosmetic());
            int addedInPlace = sizeOf(added.getInPlaceModify());
            int addedTrue = sizeOf(added.getTrueDeleteAdd());
            int deletedCosmetic = sizeOf(deleted.getCosmetic());
            int deletedInPlace = sizeOf(deleted.getInPlaceModify());
            int deletedTrue = sizeOf(deleted.getTrueDeleteAdd());

            int addedTotal = addedCosmetic + addedInPlace + addedTrue;
            int deletedTotal = deletedCosmetic + deletedInPlace + deletedTrue;

            if (addedTotal != fc.getLinesAdded()) {
                log.warn("diffClassification.fallback=addedTotalMismatch file='{}' addedTotal={} linesAdded={}; falling back to unadjusted volume score",
                        entry.getFile(), addedTotal, fc.getLinesAdded());
                fallback = true;
                break;
            }
            if (deletedTotal != fc.getLinesDeleted()) {
                log.warn("diffClassification.fallback=deletedTotalMismatch file='{}' deletedTotal={} linesDeleted={}; falling back to unadjusted volume score",
                        entry.getFile(), deletedTotal, fc.getLinesDeleted());
                fallback = true;
                break;
            }
            if (hasDuplicateLineNumbers(added) || hasDuplicateLineNumbers(deleted)) {
                log.warn("diffClassification.fallback=duplicateLineNumber file='{}'; same line number appears in multiple buckets — falling back to unadjusted volume score", entry.getFile());
                fallback = true;
                break;
            }
            if (addedInPlace != deletedInPlace) {
                log.warn("diffClassification.fallback=inPlacePairMismatch file='{}' addedInPlace={} deletedInPlace={}; falling back to unadjusted volume score",
                        entry.getFile(), addedInPlace, deletedInPlace);
                fallback = true;
                break;
            }

            int cosmeticForFile = addedCosmetic + deletedCosmetic;
            int inPlaceForFile = addedInPlace + deletedInPlace;
            int trueForFile = addedTrue + deletedTrue;

            int rawLines = fc.getLinesAdded() + fc.getLinesDeleted();
            if (rawLines == 0) {
                continue;
            }

            double effectiveLines = inPlaceForFile / 2.0 + trueForFile;
            double factor = effectiveLines / rawLines;
            perFileFactor.put(entry.getFile(), factor);

            totalCosmetic += cosmeticForFile;
            totalInPlaceCollapsed += inPlaceForFile / 2;
            totalRawLines += rawLines;
            totalAdjustedLines += (int) Math.round(effectiveLines);
        }

        if (fallback) {
            DiffBookkeeping bookkeeping = bookkeepingFromClassification(classification, fileChangesByPath);
            return DiffAdjustment.notApplied(preComputed, bookkeeping);
        }
        if (perFileFactor.isEmpty()) {
            return DiffAdjustment.notApplied(preComputed, DiffBookkeeping.zero());
        }

        PreComputedScores adjusted = volumeScoreCalculator.recompute(preComputed, perFileFactor);
        DiffBookkeeping bookkeeping = new DiffBookkeeping(totalRawLines, totalAdjustedLines, totalCosmetic, totalInPlaceCollapsed);
        return DiffAdjustment.applied(adjusted, bookkeeping);
    }
    private static void populatePerFileScalars(DiffClassification classification) {
        for (FileDiffClassification entry : classification.getPerFile()) {
            LineGroups added = Optional.ofNullable(entry.getAdded()).orElseGet(() -> LineGroups.builder().build());
            LineGroups deleted = Optional.ofNullable(entry.getDeleted()).orElseGet(() -> LineGroups.builder().build());

            int cosmeticForFile = sizeOf(added.getCosmetic()) + sizeOf(deleted.getCosmetic());
            int inPlaceForFile = sizeOf(added.getInPlaceModify()) + sizeOf(deleted.getInPlaceModify());
            int trueForFile = sizeOf(added.getTrueDeleteAdd()) + sizeOf(deleted.getTrueDeleteAdd());

            entry.setCosmeticLines(cosmeticForFile);
            entry.setInPlaceModifyLines(inPlaceForFile);
            entry.setTrueDeleteAddLines(trueForFile);
        }

        classification.setCosmeticLines(classification.getPerFile().stream().mapToInt(FileDiffClassification::getCosmeticLines).sum());
        classification.setInPlaceModifyLines(classification.getPerFile().stream().mapToInt(FileDiffClassification::getInPlaceModifyLines).sum());
        classification.setTrueDeleteAddLines(classification.getPerFile().stream().mapToInt(FileDiffClassification::getTrueDeleteAddLines).sum());
    }
    private static DiffBookkeeping bookkeepingFromClassification(DiffClassification classification, Map<String, FileChange> fileChangesByPath) {
        int totalCosmetic = 0;
        int totalInPlaceCollapsed = 0;
        int totalRawLines = 0;
        int totalAdjustedLines = 0;
        for (FileDiffClassification entry : classification.getPerFile()) {
            FileChange fc = fileChangesByPath.get(entry.getFile());
            if (Objects.isNull(fc) || !fc.isLinesJustificationRequired()) {
                continue;
            }
            int rawLines = fc.getLinesAdded() + fc.getLinesDeleted();
            if (rawLines == 0) {
                continue;
            }

            int addedInPlace = sizeOf(Optional.ofNullable(entry.getAdded()).map(LineGroups::getInPlaceModify).orElse(null));
            int deletedInPlace = sizeOf(Optional.ofNullable(entry.getDeleted()).map(LineGroups::getInPlaceModify).orElse(null));
            int validPairs = Math.min(addedInPlace, deletedInPlace);
            int trueForFile = entry.getTrueDeleteAddLines();

            totalRawLines += rawLines;
            totalAdjustedLines += validPairs + trueForFile;
            totalCosmetic += entry.getCosmeticLines();
            totalInPlaceCollapsed += validPairs;
        }
        return new DiffBookkeeping(totalRawLines, totalAdjustedLines, totalCosmetic, totalInPlaceCollapsed);
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
    private static VolumeScore toVolumeScore(PreComputedScores effective, PreComputedScores original, DiffAdjustment adjustment) {
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
    private static int sizeOf(List<Integer> list) {
        return Objects.isNull(list) ? 0 : list.size();
    }
    private static boolean hasDuplicateLineNumbers(LineGroups groups) {
        Set<Integer> seen = Sets.newHashSet();
        return !addAllUnique(seen, groups.getCosmetic())
                || !addAllUnique(seen, groups.getInPlaceModify())
                || !addAllUnique(seen, groups.getTrueDeleteAdd());
    }
    private static boolean addAllUnique(Set<Integer> seen, List<Integer> lines) {
        if (Objects.isNull(lines)) {
            return true;
        }
        for (Integer line : lines) {
            if (Objects.isNull(line) || !seen.add(line)) {
                return false;
            }
        }
        return true;
    }

    @Value
    private static class DiffAdjustment {
        PreComputedScores scores;
        DiffBookkeeping bookkeeping;
        boolean applied;

        static DiffAdjustment unchanged(PreComputedScores preComputed) {
            return new DiffAdjustment(preComputed, DiffBookkeeping.zero(), false);
        }
        static DiffAdjustment notApplied(PreComputedScores preComputed, DiffBookkeeping bookkeeping) {
            return new DiffAdjustment(preComputed, bookkeeping, false);
        }
        static DiffAdjustment applied(PreComputedScores adjusted, DiffBookkeeping bookkeeping) {
            return new DiffAdjustment(adjusted, bookkeeping, true);
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
    private static FileEffortView toFileEffortView(FileEffort fe, Map<String, FileDiffClassification> classificationByFile) {
        FileDiffClassification fileClassification = classificationByFile.get(fe.getFile());
        return FileEffortView.builder()
                .file(fe.getFile())
                .totalEffort(Precision.round(fe.getTotalEffort(), ROUNDING_PRECISION))
                .isTest(fe.isTest())
                .codeBlockEfforts(fe.getCodeBlockEfforts().stream()
                        .map(cbe -> toCodeBlockEffortView(cbe, fileClassification))
                        .collect(Collectors.toList()))
                .blocksFlaggedAsRatioOutlier(fe.getBlocksFlaggedAsRatioOutlier())
                .blocksFlaggedAsGlobalCapDriver(fe.getBlocksFlaggedAsGlobalCapDriver())
                .maxBlockRatioDeviationNcss(Precision.round(fe.getMaxBlockRatioDeviationNcss(), ROUNDING_PRECISION))
                .maxBlockRatioDeviationInvocations(Precision.round(fe.getMaxBlockRatioDeviationInvocations(), ROUNDING_PRECISION))
                .fileFlaggedAsAbusive(fe.isFileFlaggedAsAbusive())
                .build();
    }
    private static CodeBlockEffortView toCodeBlockEffortView(CodeBlockEffort cbe, FileDiffClassification fileClassification) {
        int collapsedPairs = countFullyInBlockInPlacePairs(fileClassification, cbe.getBodyStartLine(), cbe.getBodyEndLine());
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
    private static int countFullyInBlockInPlacePairs(FileDiffClassification fileClassification, int bodyStartLine, int bodyEndLine) {
        if (Objects.isNull(fileClassification) || bodyStartLine <= 0 || bodyEndLine < bodyStartLine) {
            return 0;
        }
        List<Integer> addedInPlace = Optional.ofNullable(fileClassification.getAdded()).map(LineGroups::getInPlaceModify).orElse(null);
        List<Integer> deletedInPlace = Optional.ofNullable(fileClassification.getDeleted()).map(LineGroups::getInPlaceModify).orElse(null);
        if (CollectionUtils.isEmpty(addedInPlace) || CollectionUtils.isEmpty(deletedInPlace)) {
            return 0;
        }
        int pairCount = Math.min(addedInPlace.size(), deletedInPlace.size());
        int collapsed = 0;
        for (int i = 0; i < pairCount; i++) {
            Integer addedLine = addedInPlace.get(i);
            Integer deletedLine = deletedInPlace.get(i);
            if (Objects.isNull(addedLine) || Objects.isNull(deletedLine)) {
                continue;
            }
            if (addedLine >= bodyStartLine && addedLine <= bodyEndLine
                    && deletedLine >= bodyStartLine && deletedLine <= bodyEndLine) {
                collapsed++;
            }
        }
        return collapsed;
    }
}
