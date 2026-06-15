package io.codiqo.llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.llm.UnifiedDiffLines.ChangeBlock;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.DiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.FileDiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.LinePair;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Server-side diff classification. The LLM contributes only semantic labels — per-block
 * {@code inPlace}/{@code trueModify} kinds and cosmetic line exceptions — while every coordinate
 * is computed here from the diff itself: within each {@link ChangeBlock} the non-cosmetic lines
 * are paired {@code min(D, A)} in diff order and the longer side's excess becomes pure
 * additions/deletions. The derived six arrays overwrite whatever the LLM emitted, so downstream
 * scoring ({@code FinalScoreCalculator}) and persistence ({@code LlmResponseMapper}) consume
 * coordinates that are correct by construction — even for eligible files the LLM omitted.
 */
@Slf4j
@UtilityClass
public class DiffClassificationDeriver {
    private static final String KIND_IN_PLACE = "inplace";
    private static final String KIND_IN_PLACE_MODIFY = "inplacemodify";
    private static final String KIND_TRUE_MODIFY = "truemodify";

    public static void derive(LlmScoringResponse response, LlmScoringRequest request) {
        if (Objects.isNull(request) || CollectionUtils.isEmpty(request.getFileChanges())) {
            return;
        }
        List<FileChange> eligible = request.getFileChanges().stream()
                .filter(FileChange::isLinesJustificationRequired)
                .filter(fc -> StringUtils.isNotBlank(fc.getDiff()))
                .toList();
        if (eligible.isEmpty()) {
            return;
        }

        DiffClassification classification = ensureClassification(response);
        Map<String, FileDiffClassification> llmByFile = Maps.newHashMap();
        for (FileDiffClassification entry : CollectionUtils.emptyIfNull(classification.getPerFile())) {
            llmByFile.put(entry.getFile(), entry);
        }

        List<FileDiffClassification> derived = Lists.newArrayListWithCapacity(eligible.size());
        int totalAdded = 0;
        int totalDeleted = 0;
        for (FileChange fc : eligible) {
            UnifiedDiffLines diffLines = UnifiedDiffLines.parse(fc.getDiff(), fc.isLinesJustificationRequired());
            derived.add(deriveFile(fc, diffLines, llmByFile.get(fc.getPath())));
            totalAdded += fc.getLinesAdded();
            totalDeleted += fc.getLinesDeleted();
        }
        classification.setPerFile(derived);
        classification.setTotalLinesAddedRaw(totalAdded);
        classification.setTotalLinesDeletedRaw(totalDeleted);
    }
    private static FileDiffClassification deriveFile(FileChange fc, UnifiedDiffLines diffLines, FileDiffClassification llm) {
        Set<Integer> cosmeticAdded = sanitizeCosmetic(
                Objects.nonNull(llm) ? llm.getCosmeticAdded() : null, diffLines.getCandidateAddedLines(), fc.getPath(), "cosmeticAdded");
        Set<Integer> cosmeticDeleted = sanitizeCosmetic(
                Objects.nonNull(llm) ? llm.getCosmeticDeleted() : null, diffLines.getCandidateDeletedLines(), fc.getPath(), "cosmeticDeleted");

        List<LinePair> inPlacePairs = Lists.newArrayList();
        List<LinePair> trueModifyPairs = Lists.newArrayList();
        List<Integer> pureAdd = Lists.newArrayList();
        List<Integer> pureDelete = Lists.newArrayList();
        for (ChangeBlock block : diffLines.getBlocks()) {
            List<Integer> deleted = block.getDeletedLines().stream().filter(n -> !cosmeticDeleted.contains(n)).toList();
            List<Integer> added = block.getAddedLines().stream().filter(n -> !cosmeticAdded.contains(n)).toList();

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
                .blockKinds(Objects.nonNull(llm) ? llm.getBlockKinds() : Maps.newHashMap())
                .cosmeticAdded(Lists.newArrayList(cosmeticAdded))
                .cosmeticDeleted(Lists.newArrayList(cosmeticDeleted))
                .inPlaceModifyPairs(inPlacePairs)
                .trueModifyPairs(trueModifyPairs)
                .pureAdd(pureAdd)
                .pureDelete(pureDelete)
                .build();
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
    /**
     * Validation rejects unknown cosmetic citations and triggers a retry, but the retry budget is
     * finite — whatever still points outside the candidate set after the last attempt is dropped
     * here so persisted coordinates stay correct.
     */
    private static Set<Integer> sanitizeCosmetic(List<Integer> cited, Set<Integer> candidates, String file, String bucket) {
        Set<Integer> toReturn = Sets.newTreeSet();
        for (Integer line : CollectionUtils.emptyIfNull(cited)) {
            if (Objects.nonNull(line) && candidates.contains(line)) {
                toReturn.add(line);
            } else {
                log.warn("diffClassification.droppedCosmetic file='{}' bucket={} line={} — not an effective changed line", file, bucket, line);
            }
        }
        return toReturn;
    }
    private static boolean isInPlace(FileDiffClassification llm, String blockId, String file) {
        if (Objects.isNull(llm) || MapUtils.isEmpty(llm.getBlockKinds())) {
            return false;
        }
        String kind = llm.getBlockKinds().get(blockId);
        if (Objects.isNull(kind)) {
            return false;
        }
        String normalized = kind.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (KIND_IN_PLACE.equals(normalized) || KIND_IN_PLACE_MODIFY.equals(normalized)) {
            return true;
        }
        if (!KIND_TRUE_MODIFY.equals(normalized)) {
            log.warn("diffClassification.unknownBlockKind file='{}' block={} kind='{}' — defaulting to trueModify", file, blockId, kind);
        }
        return false;
    }
}
