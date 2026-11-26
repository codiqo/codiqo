package io.codiqo.llm;

import static java.util.function.Predicate.not;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.diff.Spacing;
import io.codiqo.api.logging.Log;
import io.codiqo.lang.config.FileKind;
import io.codiqo.lang.config.FileKinds;
import io.codiqo.llm.MovedLineDetector.MoveCandidate;
import io.codiqo.llm.UnifiedDiffLines.ChangeBlock;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.DiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.FileDiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.LinePair;
import lombok.RequiredArgsConstructor;
import lombok.Value;

/**
 * Server-side diff classification. The LLM contributes only semantic labels — per-block
 * {@code inPlace}/{@code trueModify} kinds, cosmetic line exceptions, confirmed move ids, and
 * extra moved pairs — while every coordinate is computed here from the diff itself: confirmed
 * moved lines (relocated code, resolved from {@link MovedLineDetector} candidates and sanitized
 * {@link MovedPair} citations) leave the blocks first, then within
 * each {@link ChangeBlock} the remaining non-cosmetic lines are paired {@code min(D, A)} in diff
 * order and the longer side's excess becomes pure additions/deletions. The derived arrays
 * overwrite whatever the LLM emitted, so downstream scoring ({@code FinalScoreCalculator}) and
 * persistence ({@code LlmResponseMapper}) consume coordinates that are correct by construction —
 * even for eligible files the LLM omitted.
 */
@RequiredArgsConstructor
public class DiffClassificationDeriver {
    private static final String KIND_IN_PLACE = "inplace";
    private static final String KIND_IN_PLACE_MODIFY = "inplacemodify";
    private static final String KIND_TRUE_MODIFY = "truemodify";
    private static final Set<String> IN_PLACE_KINDS = Set.of(KIND_IN_PLACE, KIND_IN_PLACE_MODIFY);
    private static final Pattern NON_LETTER_CHARS = Pattern.compile("[^a-z]");

    /**
     * The most cells of the table a block's respaced lines are paired in: about a thousand lines on each side, an int
     * array of about four megabytes, held only while one block is read.
     */
    private static final long MAX_PAIRING_CELLS = 1_000_000L;

    private final Log log;

    public void derive(LlmScoringResponse response, LlmScoringRequest request) {
        derive(response, request, Collections.emptyList());
    }
    public void derive(LlmScoringResponse response, LlmScoringRequest request, List<MoveCandidate> moveCandidates) {
        List<FileChange> eligible = CollectionUtils.emptyIfNull(request.getFileChanges()).stream()
                .filter(FileChange::isLinesJustificationRequired)
                .filter(fc -> StringUtils.isNotBlank(fc.getDiff()))
                .toList();
        if (eligible.isEmpty()) {
            return;
        }

        DiffClassification classification = ensureClassification(response);
        Map<String, UnifiedDiffLines> diffLinesByFile = Maps.newLinkedHashMap();
        for (FileChange fc : eligible) {
            diffLinesByFile.put(fc.getPath(), UnifiedDiffLines.parse(fc.getDiff(), fc.getLineFilter()));
        }

        ConfirmedMoves confirmed = resolveConfirmedMoves(classification, moveCandidates, diffLinesByFile);
        Map<String, FileDiffClassification> llmByFile = Maps.newHashMap();
        for (FileDiffClassification entry : CollectionUtils.emptyIfNull(classification.getPerFile())) {
            llmByFile.put(entry.getFile(), entry);
        }

        List<FileDiffClassification> derived = Lists.newArrayListWithCapacity(eligible.size());
        int totalAdded = 0;
        int totalDeleted = 0;
        for (FileChange fc : eligible) {
            derived.add(deriveFile(fc, diffLinesByFile.get(fc.getPath()), llmByFile.get(fc.getPath()), confirmed));
            totalAdded += fc.getLinesAdded();
            totalDeleted += fc.getLinesDeleted();
        }
        classification.setPerFile(derived);
        classification.setConfirmedMoveIds(confirmed.getIds());
        classification.setMovedPairs(confirmed.getPairs());
        classification.setTotalLinesAddedRaw(totalAdded);
        classification.setTotalLinesDeletedRaw(totalDeleted);
    }
    private FileDiffClassification deriveFile(FileChange fc, UnifiedDiffLines diffLines, FileDiffClassification llm, ConfirmedMoves confirmed) {
        Collection<Integer> citedCosmeticAdded = Collections.emptyList();
        Collection<Integer> citedCosmeticDeleted = Collections.emptyList();
        Collection<Integer> citedInPlaceCollapsedAdded = Collections.emptyList();
        Map<String, String> blockKinds = Maps.newHashMap();
        if (Objects.nonNull(llm)) {
            citedCosmeticAdded = CollectionUtils.emptyIfNull(llm.getCosmeticAdded());
            citedCosmeticDeleted = CollectionUtils.emptyIfNull(llm.getCosmeticDeleted());
            citedInPlaceCollapsedAdded = CollectionUtils.emptyIfNull(llm.getInPlaceCollapsedAdded());
            blockKinds = Optional.ofNullable(llm.getBlockKinds()).orElse(Maps.newHashMap());
        }

        Set<Integer> cosmeticAdded = sanitizeCitedLines(citedCosmeticAdded, diffLines.getCandidateAddedLines(), fc.getPath(), "cosmeticAdded");
        Set<Integer> cosmeticDeleted = sanitizeCitedLines(citedCosmeticDeleted, diffLines.getCandidateDeletedLines(), fc.getPath(), "cosmeticDeleted");
        Set<Integer> inPlaceCollapsedAdded = sanitizeCitedLines(citedInPlaceCollapsedAdded, diffLines.getCandidateAddedLines(), fc.getPath(), "inPlaceCollapsedAdded");

        /**
         * a line lives in exactly one bucket: a confirmed moved line beats any cited 0-effort
         * bucket, and cosmetic beats an inPlaceCollapsedAdded citation of the same line —
         * otherwise the added/deleted totals double-count and the per-file factor invariant in
         * FinalScoreCalculator.computePerFileFactors breaks
         */
        Set<Integer> movedAdded = confirmed.addedFor(fc.getPath());
        Set<Integer> movedDeleted = confirmed.deletedFor(fc.getPath());
        dropMovedLines(cosmeticAdded, movedAdded, fc.getPath(), "cosmeticAdded");
        dropMovedLines(cosmeticDeleted, movedDeleted, fc.getPath(), "cosmeticDeleted");
        dropMovedLines(inPlaceCollapsedAdded, movedAdded, fc.getPath(), "inPlaceCollapsedAdded");
        RespacedLines respaced = respacedLines(fc.getPath(), diffLines, movedDeleted, movedAdded);
        cosmeticDeleted.addAll(respaced.getDeleted());
        cosmeticAdded.addAll(respaced.getAdded());
        inPlaceCollapsedAdded.removeAll(cosmeticAdded);

        List<LinePair> inPlacePairs = Lists.newArrayList();
        List<LinePair> trueModifyPairs = Lists.newArrayList();
        List<Integer> pureAdd = Lists.newArrayList();
        List<Integer> pureDelete = Lists.newArrayList();
        for (ChangeBlock block : diffLines.getBlocks()) {
            List<Integer> deleted = block.getDeletedLines().stream()
                    .filter(not(cosmeticDeleted::contains))
                    .filter(not(movedDeleted::contains))
                    .toList();
            List<Integer> added = block.getAddedLines().stream()
                    .filter(not(cosmeticAdded::contains))
                    .filter(not(movedAdded::contains))
                    .filter(not(inPlaceCollapsedAdded::contains))
                    .toList();

            int pairCount = Math.min(deleted.size(), added.size());
            List<LinePair> target = isInPlace(blockKinds, block.getId(), fc.getPath()) ? inPlacePairs : trueModifyPairs;
            for (int i = 0; i < pairCount; i++) {
                target.add(LinePair.builder().deleted(deleted.get(i)).added(added.get(i)).build());
            }

            pureDelete.addAll(deleted.subList(pairCount, deleted.size()));
            pureAdd.addAll(added.subList(pairCount, added.size()));
        }

        return FileDiffClassification.builder()
                .file(fc.getPath())
                .blockKinds(blockKinds)
                .cosmeticAdded(Lists.newArrayList(cosmeticAdded))
                .cosmeticDeleted(Lists.newArrayList(cosmeticDeleted))
                .inPlaceModifyPairs(inPlacePairs)
                .trueModifyPairs(trueModifyPairs)
                .pureAdd(pureAdd)
                .pureDelete(pureDelete)
                .inPlaceCollapsedAdded(Lists.newArrayList(inPlaceCollapsedAdded))
                .movedAdded(Lists.newArrayList(movedAdded))
                .movedDeleted(Lists.newArrayList(movedDeleted))
                .build();
    }
    /**
     * Sanitizes {@code confirmedMoveIds} against the server-offered candidates and
     * {@code movedPairs} against the effective changed lines (validation rejects both, but the
     * retry budget is finite) and resolves the survivors into per-file added/deleted line sets —
     * a cross-file candidate or pair splits its sides across two files. The returned pair list
     * materializes ALL confirmed relocations (candidates first, then extra pairs), so the field
     * written back to the response is the complete persisted linkage.
     */
    private ConfirmedMoves resolveConfirmedMoves(DiffClassification classification, List<MoveCandidate> moveCandidates, Map<String, UnifiedDiffLines> diffLinesByFile) {
        Map<String, MoveCandidate> byId = Maps.newHashMap();
        for (MoveCandidate candidate : moveCandidates) {
            byId.put(candidate.getId(), candidate);
        }

        List<String> ids = Lists.newArrayList();
        List<String> pairs = Lists.newArrayList();
        Map<String, Set<Integer>> deletedByFile = Maps.newHashMap();
        Map<String, Set<Integer>> addedByFile = Maps.newHashMap();
        for (String id : CollectionUtils.emptyIfNull(classification.getConfirmedMoveIds())) {
            MoveCandidate candidate = byId.get(id);
            if (Objects.nonNull(candidate)) {
                ids.add(candidate.getId());
                pairs.add(new MovedPair(candidate.getFromFile(), candidate.getFromLine(), candidate.getToFile(), candidate.getToLine()).format());
                deletedByFile.computeIfAbsent(candidate.getFromFile(), k -> Sets.newTreeSet()).add(candidate.getFromLine());
                addedByFile.computeIfAbsent(candidate.getToFile(), k -> Sets.newTreeSet()).add(candidate.getToLine());
            } else {
                log.warn("diffClassification.droppedMoveId id='%s' — not a server-offered move candidate", id);
            }
        }

        for (String raw : CollectionUtils.emptyIfNull(classification.getMovedPairs())) {
            Optional<MovedPair> parsed = MovedPair.parse(raw);
            if (parsed.isEmpty()) {
                log.warn("diffClassification.droppedMovedPair pair='%s' — unparseable, expected fromFile:fromLine->toFile:toLine", raw);
                continue;
            }

            MovedPair pair = parsed.get();
            UnifiedDiffLines fromDiff = diffLinesByFile.get(pair.getFromFile());
            UnifiedDiffLines toDiff = diffLinesByFile.get(pair.getToFile());
            boolean fromSideEffective = Objects.nonNull(fromDiff) && fromDiff.getCandidateDeletedLines().contains(pair.getFromLine());
            boolean toSideEffective = Objects.nonNull(toDiff) && toDiff.getCandidateAddedLines().contains(pair.getToLine());
            if (BooleanUtils.and(new boolean[] { fromSideEffective, toSideEffective })) {
                Set<Integer> deletedSet = deletedByFile.computeIfAbsent(pair.getFromFile(), k -> Sets.newTreeSet());
                Set<Integer> addedSet = addedByFile.computeIfAbsent(pair.getToFile(), k -> Sets.newTreeSet());
                if (BooleanUtils.or(new boolean[] { deletedSet.contains(pair.getFromLine()), addedSet.contains(pair.getToLine()) })) {
                    log.warn("diffClassification.droppedMovedPair pair='%s' — line already claimed by another confirmed relocation", raw);
                } else {
                    deletedSet.add(pair.getFromLine());
                    addedSet.add(pair.getToLine());
                    pairs.add(pair.format());
                }
            } else {
                log.warn("diffClassification.droppedMovedPair pair='%s' — a side is not an effective changed line of an eligible file", raw);
            }
        }
        return new ConfirmedMoves(ids, pairs, deletedByFile, addedByFile);
    }
    private void dropMovedLines(Set<Integer> cited, Set<Integer> moved, String file, String bucket) {
        List<Integer> overlap = cited.stream().filter(moved::contains).toList();
        for (Integer line : overlap) {
            cited.remove(line);
            log.warn("diffClassification.movedBeatsCited file='%s' bucket=%s line=%d — confirmed moved line dropped from its cited bucket", file, bucket, line);
        }
    }
    /**
     * Validation rejects unknown line citations and triggers a retry, but the retry budget is
     * finite — whatever still points outside the candidate set after the last attempt is dropped
     * here so persisted coordinates stay correct.
     */
    private Set<Integer> sanitizeCitedLines(Collection<Integer> cited, Set<Integer> candidates, String file, String bucket) {
        Set<Integer> toReturn = Sets.newTreeSet();
        for (Integer line : cited) {
            if (Objects.nonNull(line) && candidates.contains(line)) {
                toReturn.add(line);
            } else {
                log.warn("diffClassification.droppedCitedLine file='%s' bucket=%s line=%d — not an effective changed line", file, bucket, line);
            }
        }
        return toReturn;
    }
    private boolean isInPlace(Map<String, String> blockKinds, String blockId, String file) {
        String kind = blockKinds.get(blockId);
        if (Objects.isNull(kind)) {
            return false;
        }

        String normalized = NON_LETTER_CHARS.matcher(kind.toLowerCase(Locale.ROOT)).replaceAll(StringUtils.EMPTY);
        if (IN_PLACE_KINDS.contains(normalized)) {
            return true;
        }
        if (KIND_TRUE_MODIFY.equals(normalized)) {
            return false;
        }

        log.warn("diffClassification.unknownBlockKind file='%s' block=%s kind='%s' — defaulting to trueModify", file, blockId, kind);
        return false;
    }
    /**
     * The lines of a free-form file whose only change is their spacing ({@link Spacing}): a deleted and an added line of
     * one block that read the same once their spacing is set aside, so {@code foo(a,  b)} to {@code foo(a, b)} costs no
     * effort. The diff ignores a re-indent of a free-form file but not the spacing inside a line, and nothing else marks
     * such a line. A file of any other kind has none: in Python or YAML the same whitespace changes the program.
     *
     * <p>A confirmed move keeps its lines, so a moved line takes no part. The move detector compares a line's tokens and
     * offers a line re-spaced in place as moved onto itself, so a run of such lines, each beside another, is confirmed as
     * a relocation and keeps costing the moved-line fraction of a line rather than nothing: taking those moves back
     * would have to decide again which lines relocate, after move confirmation already has. Lines already cited as
     * cosmetic do take part, so a classification derived again from its own output finds the same lines.
     *
     * <p>A block's lines pair by the longest run they share in diff order, so a block that reorders lines keeps the
     * reordering as work. A block with more than {@link #MAX_PAIRING_CELLS} cells to compare pairs only in diff order,
     * line by line, and only when both sides have as many lines, which is what a formatter pass over a whole file is.
     */
    private static RespacedLines respacedLines(String file, UnifiedDiffLines diffLines, Set<Integer> movedDeleted, Set<Integer> movedAdded) {
        RespacedLines toReturn = new RespacedLines(Sets.newTreeSet(), Sets.newTreeSet());
        if (FileKinds.of(file).filter(FileKind::isFreeFormWhitespace).isPresent()) {
            for (ChangeBlock block : diffLines.getBlocks()) {
                List<Integer> deleted = block.getDeletedLines().stream().filter(not(movedDeleted::contains)).toList();
                List<Integer> added = block.getAddedLines().stream().filter(not(movedAdded::contains)).toList();
                /** a block that only adds or only deletes pairs nothing, so its lines are neither read nor tabled */
                if (BooleanUtils.and(new boolean[] { CollectionUtils.isNotEmpty(deleted), CollectionUtils.isNotEmpty(added) })) {
                    List<String> deletedReads = deleted.stream().map(diffLines.getCandidateDeletedContent()::get).map(Spacing::withoutSpacing).toList();
                    List<String> addedReads = added.stream().map(diffLines.getCandidateAddedContent()::get).map(Spacing::withoutSpacing).toList();
                    for (Pair<Integer, Integer> pair : pairReads(deletedReads, addedReads)) {
                        toReturn.getDeleted().add(deleted.get(pair.getLeft()));
                        toReturn.getAdded().add(added.get(pair.getRight()));
                    }
                }
            }
        }
        return toReturn;
    }
    /**
     * The indexes of the deleted and added lines that read the same, paired by their longest common subsequence, or, above
     * {@link #MAX_PAIRING_CELLS}, in diff order when both sides are as long. The comparison table is one flat array of
     * {@code (deleted + 1) * (added + 1)} cells.
     */
    private static List<Pair<Integer, Integer>> pairReads(List<String> deleted, List<String> added) {
        List<Pair<Integer, Integer>> toReturn = Lists.newArrayList();
        long cells = (long) (deleted.size() + 1) * (added.size() + 1);
        if (cells > MAX_PAIRING_CELLS) {
            if (deleted.size() == added.size()) {
                for (int i = 0; i < deleted.size(); i++) {
                    if (deleted.get(i).equals(added.get(i))) {
                        toReturn.add(Pair.of(i, i));
                    }
                }
            }
            return toReturn;
        }

        int width = added.size() + 1;
        int[] common = new int[(int) cells];
        for (int i = deleted.size() - 1; i >= 0; i--) {
            for (int j = added.size() - 1; j >= 0; j--) {
                common[i * width + j] = deleted.get(i).equals(added.get(j)) ? common[(i + 1) * width + j + 1] + 1 : Math.max(common[(i + 1) * width + j], common[i * width + j + 1]);
            }
        }

        int i = 0;
        int j = 0;
        while (BooleanUtils.and(new boolean[] { i < deleted.size(), j < added.size() })) {
            if (deleted.get(i).equals(added.get(j))) {
                toReturn.add(Pair.of(i++, j++));
            } else if (common[(i + 1) * width + j] >= common[i * width + j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return toReturn;
    }
    private static DiffClassification ensureClassification(LlmScoringResponse response) {
        EffortBreakdown breakdown = response.getEffortBreakdown();
        if (Objects.isNull(breakdown)) {
            breakdown = EffortBreakdown.builder().build();
            response.setEffortBreakdown(breakdown);
        }

        DiffClassification classification = breakdown.getDiffClassification();
        if (Objects.isNull(classification)) {
            classification = DiffClassification.builder().build();
            breakdown.setDiffClassification(classification);
        }
        return classification;
    }

    /** the deleted (old-file) and added (new-file) lines of a file whose only change is their spacing */
    private static final class RespacedLines extends ImmutablePair<Set<Integer>, Set<Integer>> {
        private static final long serialVersionUID = 1L;

        private RespacedLines(Set<Integer> deleted, Set<Integer> added) {
            super(deleted, added);
        }
        private Set<Integer> getDeleted() {
            return getLeft();
        }
        private Set<Integer> getAdded() {
            return getRight();
        }
    }

    @Value
    private static class ConfirmedMoves {
        List<String> ids;
        List<String> pairs;
        Map<String, Set<Integer>> deletedByFile;
        Map<String, Set<Integer>> addedByFile;

        private Set<Integer> deletedFor(String file) {
            return deletedByFile.getOrDefault(file, Collections.emptySet());
        }
        private Set<Integer> addedFor(String file) {
            return addedByFile.getOrDefault(file, Collections.emptySet());
        }
    }
}
