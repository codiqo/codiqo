package io.codiqo.llm;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.ArrayList;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;


import io.codiqo.api.logging.Log;
import io.codiqo.llm.MovedLineDetector.MoveCandidate;
import io.codiqo.llm.UnifiedDiffLines.ChangeBlock;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.DiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.FileDiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.LinePair;
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
public class DiffClassificationDeriver {
    private static final String KIND_IN_PLACE = "inplace";
    private static final String KIND_IN_PLACE_MODIFY = "inplacemodify";
    private static final String KIND_TRUE_MODIFY = "truemodify";

    private final Log log;

    public DiffClassificationDeriver(Log log) {
        this.log = log;
    }
    public void derive(LlmScoringResponse response, LlmScoringRequest request) {
        derive(response, request, Collections.emptyList());
    }
    public void derive(LlmScoringResponse response, LlmScoringRequest request, List<MoveCandidate> moveCandidates) {
        if (Objects.isNull(request) || CollectionUtils.isEmpty(request.getFileChanges())) {
            return;
        }
        List<FileChange> eligible = request
                .getFileChanges()
                .stream()
                .filter(FileChange::isLinesJustificationRequired)
                .filter(fc -> StringUtils.isNotBlank(fc.getDiff()))
                .toList();
        if (eligible.isEmpty()) {
            return;
        }

        DiffClassification classification = ensureClassification(response);
        Map<String, UnifiedDiffLines> diffLinesByFile = new LinkedHashMap<>();
        for (FileChange fc : eligible) {
            diffLinesByFile.put(fc.getPath(), UnifiedDiffLines.parse(fc.getDiff(), fc.getLineFilter()));
        }

        ConfirmedMoves confirmed = resolveConfirmedMoves(classification, moveCandidates, diffLinesByFile);
        Map<String, FileDiffClassification> llmByFile = new HashMap<>();
        for (FileDiffClassification entry : CollectionUtils.emptyIfNull(classification.getPerFile())) {
            llmByFile.put(entry.getFile(), entry);
        }

        List<FileDiffClassification> derived = new ArrayList<>(eligible.size());
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
        Set<Integer> cosmeticAdded = sanitizeCitedLines(
                CollectionUtils.emptyIfNull(Objects.nonNull(llm) ? llm.getCosmeticAdded() : null),
                diffLines.getCandidateAddedLines(), fc.getPath(), "cosmeticAdded");
        Set<Integer> cosmeticDeleted = sanitizeCitedLines(
                CollectionUtils.emptyIfNull(Objects.nonNull(llm) ? llm.getCosmeticDeleted() : null),
                diffLines.getCandidateDeletedLines(), fc.getPath(), "cosmeticDeleted");
        Set<Integer> inPlaceCollapsedAdded = sanitizeCitedLines(
                CollectionUtils.emptyIfNull(Objects.nonNull(llm) ? llm.getInPlaceCollapsedAdded() : null),
                diffLines.getCandidateAddedLines(), fc.getPath(), "inPlaceCollapsedAdded");

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
        inPlaceCollapsedAdded.removeAll(cosmeticAdded);

        List<LinePair> inPlacePairs = new ArrayList<>();
        List<LinePair> trueModifyPairs = new ArrayList<>();
        List<Integer> pureAdd = new ArrayList<>();
        List<Integer> pureDelete = new ArrayList<>();
        for (ChangeBlock block : diffLines.getBlocks()) {
            List<Integer> deleted = block.getDeletedLines().stream()
                    .filter(n -> !cosmeticDeleted.contains(n) && !movedDeleted.contains(n))
                    .toList();
            List<Integer> added = block.getAddedLines().stream()
                    .filter(n -> !cosmeticAdded.contains(n) && !movedAdded.contains(n) && !inPlaceCollapsedAdded.contains(n))
                    .toList();

            int pairCount = Math.min(deleted.size(), added.size());
            List<LinePair> target = isInPlace(llm, block.getId(), fc.getPath()) ? inPlacePairs : trueModifyPairs;
            for (int i = 0; i < pairCount; i++) {
                target.add(LinePair.builder().deleted(deleted.get(i)).added(added.get(i)).build());
            }

            pureDelete.addAll(deleted.subList(pairCount, deleted.size()));
            pureAdd.addAll(added.subList(pairCount, added.size()));
        }

        return FileDiffClassification.builder()
                .file(fc.getPath())
                .blockKinds(Objects.nonNull(llm) ? llm.getBlockKinds() : new HashMap<>())
                .cosmeticAdded(new ArrayList<>(cosmeticAdded))
                .cosmeticDeleted(new ArrayList<>(cosmeticDeleted))
                .inPlaceModifyPairs(inPlacePairs)
                .trueModifyPairs(trueModifyPairs)
                .pureAdd(pureAdd)
                .pureDelete(pureDelete)
                .inPlaceCollapsedAdded(new ArrayList<>(inPlaceCollapsedAdded))
                .movedAdded(new ArrayList<>(movedAdded))
                .movedDeleted(new ArrayList<>(movedDeleted))
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
        Map<String, MoveCandidate> byId = new HashMap<>();
        for (MoveCandidate candidate : moveCandidates) {
            byId.put(candidate.getId(), candidate);
        }

        List<String> ids = new ArrayList<>();
        List<String> pairs = new ArrayList<>();
        Map<String, Set<Integer>> deletedByFile = new HashMap<>();
        Map<String, Set<Integer>> addedByFile = new HashMap<>();
        for (String id : CollectionUtils.emptyIfNull(classification.getConfirmedMoveIds())) {
            MoveCandidate candidate = byId.get(id);
            if (Objects.nonNull(candidate)) {
                ids.add(candidate.getId());
                pairs.add(new MovedPair(candidate.getFromFile(), candidate.getFromLine(), candidate.getToFile(), candidate.getToLine()).format());
                deletedByFile.computeIfAbsent(candidate.getFromFile(), k -> new TreeSet<>()).add(candidate.getFromLine());
                addedByFile.computeIfAbsent(candidate.getToFile(), k -> new TreeSet<>()).add(candidate.getToLine());
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
            if (Objects.isNull(fromDiff) || !fromDiff.getCandidateDeletedLines().contains(pair.getFromLine())
                    || Objects.isNull(toDiff) || !toDiff.getCandidateAddedLines().contains(pair.getToLine())) {
                log.warn("diffClassification.droppedMovedPair pair='%s' — a side is not an effective changed line of an eligible file", raw);
                continue;
            }

            Set<Integer> deletedSet = deletedByFile.computeIfAbsent(pair.getFromFile(), k -> new TreeSet<>());
            Set<Integer> addedSet = addedByFile.computeIfAbsent(pair.getToFile(), k -> new TreeSet<>());
            if (BooleanUtils.or(new boolean[] { deletedSet.contains(pair.getFromLine()), addedSet.contains(pair.getToLine()) })) {
                log.warn("diffClassification.droppedMovedPair pair='%s' — line already claimed by another confirmed relocation", raw);
                continue;
            }
            deletedSet.add(pair.getFromLine());
            addedSet.add(pair.getToLine());
            pairs.add(pair.format());
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
    private DiffClassification ensureClassification(LlmScoringResponse response) {
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
    /**
     * Validation rejects unknown line citations and triggers a retry, but the retry budget is
     * finite — whatever still points outside the candidate set after the last attempt is dropped
     * here so persisted coordinates stay correct.
     */
    private Set<Integer> sanitizeCitedLines(Collection<Integer> cited, Set<Integer> candidates, String file, String bucket) {
        Set<Integer> toReturn = new TreeSet<>();
        for (Integer line : cited) {
            if (Objects.nonNull(line) && candidates.contains(line)) {
                toReturn.add(line);
            } else {
                log.warn("diffClassification.droppedCitedLine file='%s' bucket=%s line=%d — not an effective changed line", file, bucket, line);
            }
        }
        return toReturn;
    }
    private boolean isInPlace(FileDiffClassification llm, String blockId, String file) {
        if (Objects.isNull(llm) || MapUtils.isEmpty(llm.getBlockKinds())) {
            return false;
        }
        String kind = llm.getBlockKinds().get(blockId);
        if (Objects.isNull(kind)) {
            return false;
        }
        String normalized = kind.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (BooleanUtils.or(new boolean[] { KIND_IN_PLACE.equals(normalized), KIND_IN_PLACE_MODIFY.equals(normalized) })) {
            return true;
        }
        if (KIND_TRUE_MODIFY.equals(normalized)) {
            return false;
        }

        log.warn("diffClassification.unknownBlockKind file='%s' block=%s kind='%s' — defaulting to trueModify", file, blockId, kind);
        return false;
    }

    @Value
    private static class ConfirmedMoves {
        List<String> ids;
        List<String> pairs;
        Map<String, Set<Integer>> deletedByFile;
        Map<String, Set<Integer>> addedByFile;

        Set<Integer> deletedFor(String file) {
            return deletedByFile.getOrDefault(file, Collections.emptySet());
        }
        Set<Integer> addedFor(String file) {
            return addedByFile.getOrDefault(file, Collections.emptySet());
        }
    }
}
