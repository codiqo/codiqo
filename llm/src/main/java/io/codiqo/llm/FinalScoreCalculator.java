package io.codiqo.llm;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Precision;
import org.slf4j.event.Level;


import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.JavaInvocationCounter;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.MovedLineDetector.MoveCandidate;
import io.codiqo.llm.VolumeScoreCalculator.CodeBlockEffort;
import io.codiqo.llm.VolumeScoreCalculator.FileEffort;
import io.codiqo.llm.VolumeScoreCalculator.PreComputedScores;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.ArchitectureEffortBonus;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockCategory;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockCategoryView;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockEffortView;
import io.codiqo.llm.schema.LlmScoringResponse.DiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.FileDiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.FileEffortView;
import io.codiqo.llm.schema.LlmScoringResponse.LinePair;
import io.codiqo.llm.schema.LlmScoringResponse.QualityMultiplier;
import io.codiqo.llm.schema.LlmScoringResponse.VolumeScore;
import lombok.Value;

public class FinalScoreCalculator {
    private static final int ROUNDING_PRECISION = 2;
    private static final int MAX_ARCHITECTURE_IMPACT = 10;
    private static final String COMMIT_SCOPE = "(commit)";
    private static final double DEGRADED_QUALITY_MULTIPLIER_MAX = 1.0;

    private final RunArgs args;
    private final VolumeScoreCalculator volumeScoreCalculator;
    private final MovedLineDetector movedLineDetector;
    private final Log log;

    public FinalScoreCalculator(RunArgs args, Log log) {
        this.args = Objects.requireNonNull(args);
        this.log = log;
        this.volumeScoreCalculator = new VolumeScoreCalculator(args);
        this.movedLineDetector = new MovedLineDetector(args);
    }

    public void apply(LlmScoringResponse response, PreComputedScores preComputed) {
        apply(response, preComputed, null);
    }
    public void apply(LlmScoringResponse response, PreComputedScores preComputed, LlmScoringRequest request) {
        dropMovedPairsWhenDetectionDisabled(response);
        new DiffClassificationDeriver(log).derive(response, request, movedLineDetector.detect(request));
        DiffAdjustment adjustment = computeDiffAdjustment(response, preComputed, request);
        PreComputedScores effective = adjustment.getScores();
        Map<String, FileDiffClassification> classificationByFile = buildClassificationByFile(response);

        double baseEffort = effective.getBaseEffort();
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

        /**
         * a diff-only degraded analysis (build failure) has no coverage/static-analysis/duplication data,
         * so quality bonuses are unverifiable — and the commit broke the build. cap the multiplier at 1.0
         */
        double qualityMultiplierMax = args.getQualityMultiplierMax();
        if (Objects.nonNull(request) && Objects.nonNull(request.getBuildFailure())) {
            qualityMultiplierMax = Math.min(qualityMultiplierMax, DEGRADED_QUALITY_MULTIPLIER_MAX);
        }

        double clampedQualityMultiplier = Math.max(
                args.getQualityMultiplierMin(),
                Math.min(qualityMultiplierMax, rawQualityMultiplier));
        if (Objects.isNull(response.getQualityMultiplier())) {
            response.setQualityMultiplier(QualityMultiplier.builder().finalMultiplier(clampedQualityMultiplier).build());
        } else {
            response.getQualityMultiplier().setFinalMultiplier(clampedQualityMultiplier);
        }

        /**
         * the architecture bonus rewards effort on actual code — a commit that touches only
         * build/config descriptors (e.g. pom.xml, .proto) produces no code blocks and must not
         * earn it. language-agnostic: any structured language contributes codeBlockChanges, so a
         * mechanical pom-only version bump scores zero here regardless of what the LLM returned.
         * a null request (test-only overload) preserves legacy behaviour
         */
        boolean hasCodeChanges = Objects.isNull(request) || CollectionUtils.isNotEmpty(request.getCodeBlockChanges());

        int architectureImpactScore = 0;
        double qualityFactor = 1.0;
        if (hasCodeChanges && Objects.nonNull(response.getArchitectureEffortBonus())) {
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
            bonus.setArchitectureImpactScore(architectureImpactScore);
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
    /**
     * moveDetectionEnabled=false must be a full off switch for relocation discounts: a disabled
     * detector offers no candidates (so confirmed ids resolve to nothing), but the LLM knows the
     * movedPairs contract from the system prompt and may still cite pairs — the deriver sanitizes
     * pairs only against effective lines, so they must be stripped here before derivation.
     */
    private void dropMovedPairsWhenDetectionDisabled(LlmScoringResponse response) {
        if (args.isMoveDetectionEnabled()) {
            return;
        }
        if (Objects.nonNull(response.getEffortBreakdown()) && Objects.nonNull(response.getEffortBreakdown().getDiffClassification())
                && CollectionUtils.isNotEmpty(response.getEffortBreakdown().getDiffClassification().getMovedPairs())) {
            log.warn("diffClassification.droppedMovedPairs count=%d — move detection is disabled",
                    response.getEffortBreakdown().getDiffClassification().getMovedPairs().size());
            response.getEffortBreakdown().getDiffClassification().setMovedPairs(new ArrayList<>());
        }
    }
    private DiffAdjustment computeDiffAdjustment(LlmScoringResponse response, PreComputedScores preComputed, LlmScoringRequest request) {
        PerFileResult perFile = computePerFileFactors(response, request);
        Map<String, Double> perBlockCoeff = buildPerBlockCoeff(response);
        Map<String, Double> perBlockMovedFactor = buildPerBlockMovedFactor(response, preComputed, request);

        if (perFile.getFactors().isEmpty() && perBlockCoeff.isEmpty() && perBlockMovedFactor.isEmpty()) {
            return DiffAdjustment.unchanged(preComputed);
        }

        PreComputedScores adjusted = volumeScoreCalculator.recompute(preComputed, perFile.getFactors(), perBlockCoeff, perBlockMovedFactor);
        return DiffAdjustment.applied(adjusted, perFile.getBookkeeping());
    }
    /**
     * Relocated lines still bill their invocation counts to the block whose new-file span absorbed
     * them: deleted lines anchor to the next surviving line (so a moved-out body lands on the
     * surviving block), and moved-in additions sit in the destination body. The line term is
     * bounded by the bodyCodeLines cap and discounted by the per-file factor, but the invocation
     * term of the driver score is uncapped — this factor removes each block's moved invocation
     * share at movedLineCoefficient/2, mirroring the per-line relocation charge.
     */
    private Map<String, Double> buildPerBlockMovedFactor(LlmScoringResponse response, PreComputedScores preComputed, LlmScoringRequest request) {
        Map<String, Double> toReturn = new HashMap<>();
        if (Objects.isNull(request) || CollectionUtils.isEmpty(request.getFileChanges()) || CollectionUtils.isEmpty(preComputed.getCodeBlockEfforts())) {
            return toReturn;
        }
        if (Objects.isNull(response.getEffortBreakdown()) || Objects.isNull(response.getEffortBreakdown().getDiffClassification())) {
            return toReturn;
        }

        Map<String, List<CodeBlockEffort>> blocksByFile = new HashMap<>();
        for (CodeBlockEffort cbe : preComputed.getCodeBlockEfforts()) {
            if (!cbe.isConfig()) {
                blocksByFile.computeIfAbsent(cbe.getFile(), k -> new ArrayList<>()).add(cbe);
            }
        }

        Map<String, FileChange> fileChangesByPath = new HashMap<>(request.getFileChanges().size());
        for (FileChange fc : request.getFileChanges()) {
            fileChangesByPath.put(fc.getPath(), fc);
        }

        for (FileDiffClassification entry : CollectionUtils.emptyIfNull(response.getEffortBreakdown().getDiffClassification().getPerFile())) {
            List<CodeBlockEffort> blocks = blocksByFile.get(entry.getFile());
            FileChange fc = fileChangesByPath.get(entry.getFile());
            boolean hasMovedLines = CollectionUtils.isNotEmpty(entry.getMovedAdded()) || CollectionUtils.isNotEmpty(entry.getMovedDeleted());
            if (CollectionUtils.isEmpty(blocks) || Objects.isNull(fc) || StringUtils.isBlank(fc.getDiff()) || !hasMovedLines) {
                continue;
            }

            UnifiedDiffLines diffLines = UnifiedDiffLines.parse(fc.getDiff(), fc.getLineFilter());

            int[] movedInvocations = new int[blocks.size()];
            for (Integer line : CollectionUtils.emptyIfNull(entry.getMovedDeleted())) {
                Integer anchor = diffLines.getCandidateDeletedAnchor().get(line);
                String content = diffLines.getCandidateDeletedContent().get(line);
                /**
                 * deleted lines are billed only to MODIFY units (EffectiveChangePopulator), with
                 * the anchor allowed to sit one line past the body end — mirror both rules here
                 */
                if (Objects.nonNull(anchor) && Objects.nonNull(content)) {
                    int idx = innermostBlockForLine(blocks, anchor, 1, true);
                    if (idx >= 0) {
                        movedInvocations[idx] += JavaInvocationCounter.countInLine(content);
                    }
                }
            }
            for (Integer line : CollectionUtils.emptyIfNull(entry.getMovedAdded())) {
                String content = diffLines.getCandidateAddedContent().get(line);
                if (Objects.nonNull(content)) {
                    int idx = innermostBlockForLine(blocks, line, 0, false);
                    if (idx >= 0) {
                        movedInvocations[idx] += JavaInvocationCounter.countInLine(content);
                    }
                }
            }

            for (int i = 0; i < blocks.size(); i++) {
                if (movedInvocations[i] > 0) {
                    double factor = movedFactor(blocks.get(i), movedInvocations[i], args.getMovedLineCoefficient());
                    if (factor < 1.0) {
                        toReturn.put(VolumeScoreCalculator.blockKey(blocks.get(i).getFile(), blocks.get(i).getSignature()), factor);
                    }
                }
            }
        }
        return toReturn;
    }
    private Map<String, Double> buildPerBlockCoeff(LlmScoringResponse response) {
        Map<String, Double> toReturn = new HashMap<>();
        for (CodeBlockCategoryView view : CollectionUtils.emptyIfNull(response.getBlockCategories())) {
            if (Objects.isNull(view.getCategory()) || StringUtils.isBlank(view.getSignature())) {
                continue;
            }
            toReturn.put(VolumeScoreCalculator.blockKey(view.getFile(), view.getSignature()), categoryCoeff(view.getCategory()));
        }
        return toReturn;
    }
    private double categoryCoeff(CodeBlockCategory category) {
        return switch (category) {
            case MECHANICAL -> args.getCategoryMechanicalCoeff();
            case ROUTINE -> args.getCategoryRoutineCoeff();
            case SUBSTANTIVE -> args.getCategorySubstantiveCoeff();
            case INTRICATE -> args.getCategoryIntricateCoeff();
            default -> throw new IllegalArgumentException("Unknown code block category: " + category);
        };
    }
    private PerFileResult computePerFileFactors(LlmScoringResponse response, LlmScoringRequest request) {
        if (Objects.isNull(response.getEffortBreakdown()) || Objects.isNull(response.getEffortBreakdown().getDiffClassification())) {
            return PerFileResult.empty();
        }
        if (Objects.isNull(request) || CollectionUtils.isEmpty(request.getFileChanges())) {
            return PerFileResult.empty();
        }

        DiffClassification classification = response.getEffortBreakdown().getDiffClassification();
        if (CollectionUtils.isEmpty(classification.getPerFile())) {
            return PerFileResult.empty();
        }

        Map<String, FileChange> fileChangesByPath = new HashMap<>(request.getFileChanges().size());
        for (FileChange fc : request.getFileChanges()) {
            fileChangesByPath.put(fc.getPath(), fc);
        }

        populatePerFileScalars(classification);

        Map<String, Double> perFileFactor = new HashMap<>();
        int totalCosmetic = 0;
        int totalPairsCollapsed = 0;
        int totalMovedLines = 0;
        int totalRawLines = 0;
        double totalAdjustedLines = 0.0;
        for (FileDiffClassification entry : classification.getPerFile()) {
            FileChange fc = fileChangesByPath.get(entry.getFile());
            if (Objects.isNull(fc)) {
                log.log(Level.DEBUG, "diffClassification.skipReason=unknownFile file='%s'", entry.getFile());
                continue;
            }
            if (!fc.isLinesJustificationRequired()) {
                log.log(Level.DEBUG, "diffClassification.skipReason=notEligible file='%s' language='%s'", entry.getFile(), fc.getLanguage());
                continue;
            }

            int cosmeticAddedSize = sizeOfInts(entry.getCosmeticAdded());
            int cosmeticDeletedSize = sizeOfInts(entry.getCosmeticDeleted());
            int inPlacePairs = sizeOfPairs(entry.getInPlaceModifyPairs());
            int trueModifyPairs = sizeOfPairs(entry.getTrueModifyPairs());
            int pureAddSize = sizeOfInts(entry.getPureAdd());
            int pureDeleteSize = sizeOfInts(entry.getPureDelete());
            int inPlaceCollapsedAddedSize = sizeOfInts(entry.getInPlaceCollapsedAdded());
            int movedAddedSize = sizeOfInts(entry.getMovedAdded());
            int movedDeletedSize = sizeOfInts(entry.getMovedDeleted());

            int addedTotal = cosmeticAddedSize + inPlacePairs + trueModifyPairs + pureAddSize + inPlaceCollapsedAddedSize + movedAddedSize;
            int deletedTotal = cosmeticDeletedSize + inPlacePairs + trueModifyPairs + pureDeleteSize + movedDeletedSize;

            /**
             * entries are server-derived from the diff (DiffClassificationDeriver), so totals match
             * the effective targets by construction — a mismatch means candidate filtering drifted
             * from DiffStats.categorize and the file's factor can't be trusted
             */
            if (addedTotal != fc.getLinesAdded()) {
                log.warn("diffClassification.skipReason=addedTotalMismatch file='%s' addedTotal=%d linesAdded=%d",
                        entry.getFile(),
                        addedTotal,
                        fc.getLinesAdded());
                continue;
            }
            if (deletedTotal != fc.getLinesDeleted()) {
                log.warn("diffClassification.skipReason=deletedTotalMismatch file='%s' deletedTotal=%d linesDeleted=%d",
                        entry.getFile(),
                        deletedTotal,
                        fc.getLinesDeleted());
                continue;
            }

            int rawLines = fc.getLinesAdded() + fc.getLinesDeleted();
            if (rawLines == 0) {
                continue;
            }

            /**
             * each side of a moved pair charges movedLineCoefficient/2 to its own file — a
             * same-file move costs the full coefficient, a cross-file move splits it between the
             * source and destination files, and the sum over files is pairs × coefficient either way
             */
            int pairsCount = inPlacePairs + trueModifyPairs;
            double effectiveLines = pairsCount + pureAddSize + pureDeleteSize
                    + (movedAddedSize + movedDeletedSize) * args.getMovedLineCoefficient() / 2.0;
            double factor = effectiveLines / rawLines;
            perFileFactor.put(entry.getFile(), factor);

            totalCosmetic += cosmeticAddedSize + cosmeticDeletedSize;
            totalPairsCollapsed += pairsCount;
            totalMovedLines += movedAddedSize + movedDeletedSize;
            totalRawLines += rawLines;
            totalAdjustedLines += effectiveLines;
        }

        if (perFileFactor.isEmpty()) {
            return PerFileResult.empty();
        }

        /**
         * a moved-only commit yields a fractional adjusted total below 0.5 (e.g. one pair at the
         * default coefficient = 0.25) — reporting 0 effective lines for a non-empty change would
         * mislead API consumers, so clamp any positive fraction to at least 1
         */
        int adjustedLines = (int) Math.round(totalAdjustedLines);
        if (totalAdjustedLines > 0) {
            adjustedLines = Math.max(1, adjustedLines);
        }

        DiffBookkeeping bookkeeping = new DiffBookkeeping(
                totalRawLines,
                adjustedLines,
                totalCosmetic,
                totalPairsCollapsed,
                totalMovedLines);
        return new PerFileResult(perFileFactor, bookkeeping);
    }
    private static void populatePerFileScalars(DiffClassification classification) {
        for (FileDiffClassification entry : classification.getPerFile()) {
            int cosmeticForFile = sizeOfInts(entry.getCosmeticAdded()) + sizeOfInts(entry.getCosmeticDeleted());
            int pairsForFile = sizeOfPairs(entry.getInPlaceModifyPairs()) + sizeOfPairs(entry.getTrueModifyPairs());
            int pureAddDeleteForFile = sizeOfInts(entry.getPureAdd()) + sizeOfInts(entry.getPureDelete());
            int movedForFile = sizeOfInts(entry.getMovedAdded()) + sizeOfInts(entry.getMovedDeleted());

            entry.setCosmeticLines(cosmeticForFile);
            entry.setPairsCollapsed(pairsForFile);
            entry.setPureAddDeleteLines(pureAddDeleteForFile);
            entry.setMovedLines(movedForFile);
        }

        classification.setCosmeticLines(classification.getPerFile().stream().mapToInt(FileDiffClassification::getCosmeticLines).sum());
        classification.setPairsCollapsed(classification.getPerFile().stream().mapToInt(FileDiffClassification::getPairsCollapsed).sum());
        classification.setPureAddDeleteLines(classification.getPerFile().stream().mapToInt(FileDiffClassification::getPureAddDeleteLines).sum());
        classification.setMovedLines(classification.getPerFile().stream().mapToInt(FileDiffClassification::getMovedLines).sum());
    }
    private static Map<String, FileDiffClassification> buildClassificationByFile(LlmScoringResponse response) {
        if (Objects.isNull(response.getEffortBreakdown()) || Objects.isNull(response.getEffortBreakdown().getDiffClassification())) {
            return new HashMap<>();
        }
        List<FileDiffClassification> perFile = response.getEffortBreakdown().getDiffClassification().getPerFile();
        if (CollectionUtils.isEmpty(perFile)) {
            return new HashMap<>();
        }
        Map<String, FileDiffClassification> toReturn = new HashMap<>(perFile.size());
        for (FileDiffClassification entry : perFile) {
            toReturn.put(entry.getFile(), entry);
        }
        return toReturn;
    }
    private static VolumeScore toVolumeScore(PreComputedScores effective, DiffAdjustment adjustment) {
        VolumeScore toReturn = VolumeScore.builder()
                .linesChanged(effective.getLinesChanged())
                .linesNew(effective.getLinesNew())
                .linesModified(effective.getLinesModified())
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
        toReturn.setMovedLinesDiscounted(bookkeeping.getMovedLinesDiscounted());
        return toReturn;
    }
    private static FileEffortView toFileEffortView(FileEffort fe, Map<String, FileDiffClassification> classificationByFile) {
        FileDiffClassification fileClassification = classificationByFile.get(fe.getFile());
        List<CodeBlockEffort> blocks = fe.getCodeBlockEfforts();
        int[] collapsed = collapsedLinesPerBlock(fileClassification, blocks);

        List<CodeBlockEffortView> blockViews = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            blockViews.add(toCodeBlockEffortView(blocks.get(i), collapsed[i]));
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
    private static CodeBlockEffortView toCodeBlockEffortView(CodeBlockEffort cbe, int collapsed) {
        int effectiveLinesChanged = Math.max(0, cbe.getEffectiveLinesChanged() - collapsed);
        double changeRatio = cbe.getChangeRatio();
        if (collapsed > 0 && cbe.getBodyCodeLines() > 0) {
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
    private static int[] collapsedLinesPerBlock(FileDiffClassification fileClassification, List<CodeBlockEffort> blocks) {
        int[] toReturn = new int[blocks.size()];
        if (Objects.isNull(fileClassification)) {
            return toReturn;
        }
        assignPairsToInnermostBlock(fileClassification.getInPlaceModifyPairs(), blocks, toReturn);
        assignPairsToInnermostBlock(fileClassification.getTrueModifyPairs(), blocks, toReturn);

        for (Integer line : CollectionUtils.emptyIfNull(fileClassification.getInPlaceCollapsedAdded())) {
            int innermost = innermostBlockForLine(blocks, line, 0, false);
            if (innermost >= 0) {
                toReturn[innermost]++;
            }
        }
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
    private static int innermostBlockForLine(List<CodeBlockEffort> blocks, int line, int endSlack, boolean modifyOnly) {
        int innermost = -1;
        for (int i = 0; i < blocks.size(); i++) {
            CodeBlockEffort block = blocks.get(i);
            if (modifyOnly && block.getOperation() != LlmScoringRequest.Operation.MODIFY) {
                continue;
            }
            int bodyStartLine = block.getBodyStartLine();
            int bodyEndLine = block.getBodyEndLine();
            if (bodyStartLine <= 0 || bodyEndLine < bodyStartLine) {
                continue;
            }
            if (line >= bodyStartLine && line <= bodyEndLine + endSlack) {
                if (innermost < 0 || blockBodySpan(block) < blockBodySpan(blocks.get(innermost))) {
                    innermost = i;
                }
            }
        }
        return innermost;
    }
    /**
     * Expresses the moved-invocation subtraction as a multiplier on the stored driver score. The
     * driver is the weighted mean of the scaled components (lines [+ ncss] + invocations), so
     * removing (1 − coeff/2) of the moved invocations' scaled weight from the component sum
     * scales the driver by exactly this ratio — the operation-specific weight cancels out. The
     * moved share is recounted by regex ({@link JavaInvocationCounter}) — identical to how the
     * deleted side was billed, an approximation of the AST count on the added side — and clamped
     * at the block's whole scaled invocation term.
     */
    private static double movedFactor(CodeBlockEffort block, int movedInvocations, double movedLineCoefficient) {
        int billedInvocations = block.getOperation() == LlmScoringRequest.Operation.MODIFY
                ? block.getEffectiveInvocationsChanged()
                : block.getDirectInvocationCount();
        double driverComponents = block.getScaledLines() + block.getScaledNcss() + block.getScaledInvocations();
        if (billedInvocations <= 0 || block.getScaledInvocations() <= 0 || driverComponents <= 0) {
            return 1.0;
        }

        double invocationsFactor = block.getScaledInvocations() / billedInvocations;
        double movedInvocationsScaled = Math.min(movedInvocations * invocationsFactor, block.getScaledInvocations());
        return (driverComponents - movedInvocationsScaled * (1.0 - movedLineCoefficient / 2.0)) / driverComponents;
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
     * must exist, every cosmetic line number must be an effective changed line, every confirmed
     * move id must be a server-offered candidate, and every moved pair must cite effective lines
     * not already claimed. Pairing and pure buckets are server-derived
     * ({@link DiffClassificationDeriver}), so the old count and duplicate invariants cannot be
     * violated and are no longer checked.
     */
    public ValidationReport validate(LlmScoringResponse response, LlmScoringRequest request) {
        List<ValidationFailure> failures = new ArrayList<>();

        if (Objects.isNull(response.getEffortBreakdown()) || Objects.isNull(response.getEffortBreakdown().getDiffClassification())) {
            return new ValidationReport(failures);
        }
        if (CollectionUtils.isEmpty(request.getFileChanges())) {
            return new ValidationReport(failures);
        }

        List<MoveCandidate> moveCandidates = movedLineDetector.detect(request);
        validateConfirmedMoveIds(response.getEffortBreakdown().getDiffClassification(), moveCandidates, failures);
        // when detection is disabled, apply() strips movedPairs — don't burn retries on them here
        if (args.isMoveDetectionEnabled()) {
            validateMovedPairs(response.getEffortBreakdown().getDiffClassification(), request, moveCandidates, failures);
        }

        // explicit "perFile": null from the LLM bypasses the @Builder.Default empty list
        if (CollectionUtils.isEmpty(response.getEffortBreakdown().getDiffClassification().getPerFile())) {
            return new ValidationReport(failures);
        }

        Map<String, FileChange> fileChangesByPath = new HashMap<>(request.getFileChanges().size());
        for (FileChange fc : request.getFileChanges()) {
            fileChangesByPath.put(fc.getPath(), fc);
        }

        for (FileDiffClassification entry : response.getEffortBreakdown().getDiffClassification().getPerFile()) {
            FileChange fc = fileChangesByPath.get(entry.getFile());
            if (Objects.isNull(fc) || !fc.isLinesJustificationRequired() || StringUtils.isBlank(fc.getDiff())) {
                continue;
            }

            UnifiedDiffLines diffLines = UnifiedDiffLines.parse(fc.getDiff(), fc.getLineFilter());

            Set<String> validBlockIds = new LinkedHashSet<>();
            for (UnifiedDiffLines.ChangeBlock block : diffLines.getBlocks()) {
                validBlockIds.add(block.getId());
            }
            List<String> unknownBlocks = new ArrayList<>();
            if (MapUtils.isNotEmpty(entry.getBlockKinds())) {
                for (String blockId : entry.getBlockKinds().keySet()) {
                    if (!validBlockIds.contains(blockId)) {
                        unknownBlocks.add(blockId);
                    }
                }
            }
            if (CollectionUtils.isNotEmpty(unknownBlocks)) {
                failures.add(new ValidationFailure(entry.getFile(), FailureReason.UNKNOWN_BLOCK,
                        unknownBlocks, new ArrayList<>(validBlockIds)));
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
    /**
     * Confirmed move ids are commit-scoped (a candidate can pair lines across two files), so the
     * failure carries the {@link #COMMIT_SCOPE} sentinel instead of a file path.
     */
    private static void validateConfirmedMoveIds(DiffClassification classification, List<MoveCandidate> moveCandidates, List<ValidationFailure> failures) {
        if (CollectionUtils.isNotEmpty(classification.getConfirmedMoveIds())) {
            List<String> validIds = moveCandidates.stream().map(MoveCandidate::getId).toList();
            Set<String> validSet = new HashSet<>(validIds);

            List<String> unknownIds = new ArrayList<>();
            for (String id : classification.getConfirmedMoveIds()) {
                if (Objects.isNull(id) || !validSet.contains(id)) {
                    unknownIds.add(String.valueOf(id));
                }
            }
            if (CollectionUtils.isNotEmpty(unknownIds)) {
                failures.add(new ValidationFailure(COMMIT_SCOPE, FailureReason.UNKNOWN_MOVE_ID, unknownIds, validIds));
            }
        }
    }
    /**
     * Moved pairs are commit-scoped like confirmed move ids. Each pair must parse as
     * fromFile:fromLine->toFile:toLine, cite block-tagged effective lines of eligible files, use
     * each line at most once, and not repeat a line already claimed by a confirmed candidate.
     */
    private static void validateMovedPairs(DiffClassification classification, LlmScoringRequest request, List<MoveCandidate> moveCandidates, List<ValidationFailure> failures) {
        if (CollectionUtils.isNotEmpty(classification.getMovedPairs())) {
            Map<String, UnifiedDiffLines> diffLinesByFile = new HashMap<>();
            for (FileChange fc : request.getFileChanges()) {
                if (fc.isLinesJustificationRequired() && StringUtils.isNotBlank(fc.getDiff())) {
                    diffLinesByFile.put(fc.getPath(), UnifiedDiffLines.parse(fc.getDiff(), fc.getLineFilter()));
                }
            }

            Map<String, MoveCandidate> candidatesById = new HashMap<>();
            for (MoveCandidate candidate : moveCandidates) {
                candidatesById.put(candidate.getId(), candidate);
            }
            Set<String> claimedDeleted = new HashSet<>();
            Set<String> claimedAdded = new HashSet<>();
            for (String id : CollectionUtils.emptyIfNull(classification.getConfirmedMoveIds())) {
                MoveCandidate candidate = candidatesById.get(id);
                if (Objects.nonNull(candidate)) {
                    claimedDeleted.add(candidate.getFromFile() + ":" + candidate.getFromLine());
                    claimedAdded.add(candidate.getToFile() + ":" + candidate.getToLine());
                }
            }

            List<String> offending = new ArrayList<>();
            for (String raw : classification.getMovedPairs()) {
                Optional<MovedPair> parsed = MovedPair.parse(raw);
                if (parsed.isEmpty()) {
                    offending.add(String.valueOf(raw));
                    continue;
                }

                MovedPair pair = parsed.get();
                UnifiedDiffLines fromDiff = diffLinesByFile.get(pair.getFromFile());
                UnifiedDiffLines toDiff = diffLinesByFile.get(pair.getToFile());
                if (Objects.isNull(fromDiff) || !fromDiff.getCandidateDeletedLines().contains(pair.getFromLine())
                        || Objects.isNull(toDiff) || !toDiff.getCandidateAddedLines().contains(pair.getToLine())) {
                    offending.add(raw);
                    continue;
                }
                if (!claimedDeleted.add(pair.getFromFile() + ":" + pair.getFromLine())
                        || !claimedAdded.add(pair.getToFile() + ":" + pair.getToLine())) {
                    offending.add(raw);
                }
            }
            if (CollectionUtils.isNotEmpty(offending)) {
                failures.add(new ValidationFailure(COMMIT_SCOPE, FailureReason.INVALID_MOVED_PAIR, offending, Collections.emptyList()));
            }
        }
    }
    private static List<String> unknownLines(List<Integer> cited, Set<Integer> valid) {
        List<String> unknown = new ArrayList<>();
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
        UNKNOWN_MOVE_ID,
        INVALID_MOVED_PAIR,
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
    private static class PerFileResult {
        Map<String, Double> factors;
        DiffBookkeeping bookkeeping;

        static PerFileResult empty() {
            return new PerFileResult(new HashMap<>(), DiffBookkeeping.zero());
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
        int movedLinesDiscounted;

        static DiffBookkeeping zero() {
            return new DiffBookkeeping(0, 0, 0, 0, 0);
        }
    }
}
