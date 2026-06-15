package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.DiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.FileDiffClassification;
import io.codiqo.llm.schema.LlmScoringResponse.LinePair;

class DiffClassificationDeriverTest {
    // B1: deleted {11} / added {11}; B2: deleted {13, 14} / added {13} (delete-heavy run)
    private static final String DIFF = String.join("\n",
            "--- a/Foo.java",
            "+++ b/Foo.java",
            "@@ -10,6 +10,5 @@",
            " context",
            "-old line A",
            "+new line A",
            " context2",
            "-old line B",
            "-old line C",
            "+new line B",
            " context3");

    @Test
    void derivesPairsAndPureLinesWithoutAnyLlmEntry() {
        LlmScoringResponse response = new LlmScoringResponse();
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        DiffClassificationDeriver.derive(response, request);

        FileDiffClassification entry = soleEntry(response);
        assertEquals("Foo.java", entry.getFile());
        assertTrue(entry.getInPlaceModifyPairs().isEmpty(), "no LLM labels → every pair defaults to trueModify");
        assertEquals(List.of(pair(11, 11), pair(13, 13)), entry.getTrueModifyPairs(),
                "B1 pairs 11→11; B2 pairs 13→13 (min(2,1) = 1)");
        assertEquals(List.of(14), entry.getPureDelete(), "B2's unpaired deletion is pure");
        assertTrue(entry.getPureAdd().isEmpty());
    }
    @Test
    void blockKindRoutesPairsToInPlace() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder()
                .file("Foo.java")
                .blockKinds(kinds("B1", "inPlace"))
                .build());
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        DiffClassificationDeriver.derive(response, request);

        FileDiffClassification entry = soleEntry(response);
        assertEquals(List.of(pair(11, 11)), entry.getInPlaceModifyPairs(), "B1 labeled inPlace");
        assertEquals(List.of(pair(13, 13)), entry.getTrueModifyPairs(), "B2 unlabeled → trueModify");
        assertEquals(List.of(14), entry.getPureDelete());
    }
    @Test
    void cosmeticLinesAreRemovedBeforePairing() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticAdded(Lists.newArrayList(13))
                .build());
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        DiffClassificationDeriver.derive(response, request);

        FileDiffClassification entry = soleEntry(response);
        assertEquals(List.of(13), entry.getCosmeticAdded());
        assertEquals(List.of(pair(11, 11)), entry.getTrueModifyPairs(),
                "B2's only added line went cosmetic → no pair left in B2");
        assertEquals(List.of(13, 14), entry.getPureDelete(), "B2's deletions both become pure after cosmetic removal");
    }
    @Test
    void invalidCosmeticCitationsAreDropped() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticAdded(Lists.newArrayList(10, 999)) // context line and nonexistent line
                .build());
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        DiffClassificationDeriver.derive(response, request);

        FileDiffClassification entry = soleEntry(response);
        assertTrue(entry.getCosmeticAdded().isEmpty(), "citations outside the candidate set are dropped");
        assertEquals(2, entry.getTrueModifyPairs().size(), "pairing unaffected by dropped citations");
    }
    @Test
    void ineligibleAndUnknownLlmEntriesArePruned() {
        LlmScoringResponse response = responseWith(
                FileDiffClassification.builder().file("pom.xml").build(),
                FileDiffClassification.builder().file("Ghost.java").build());
        FileChange eligible = fileChange("Foo.java", DIFF, true);
        FileChange config = fileChange("pom.xml", DIFF, false);
        LlmScoringRequest request = LlmScoringRequest.builder()
                .fileChanges(Lists.newArrayList(eligible, config))
                .build();

        DiffClassificationDeriver.derive(response, request);

        List<FileDiffClassification> perFile = response.getEffortBreakdown().getDiffClassification().getPerFile();
        assertEquals(1, perFile.size(), "only eligible files survive derivation");
        assertEquals("Foo.java", perFile.get(0).getFile());
    }
    @Test
    void totalsReflectEffectiveTargets() {
        LlmScoringResponse response = new LlmScoringResponse();
        FileChange fc = fileChange("Foo.java", DIFF, true);
        fc.setLinesAdded(2);
        fc.setLinesDeleted(3);
        LlmScoringRequest request = LlmScoringRequest.builder().fileChanges(Lists.newArrayList(fc)).build();

        DiffClassificationDeriver.derive(response, request);

        DiffClassification classification = response.getEffortBreakdown().getDiffClassification();
        assertEquals(2, classification.getTotalLinesAddedRaw());
        assertEquals(3, classification.getTotalLinesDeletedRaw());
    }

    private static FileDiffClassification soleEntry(LlmScoringResponse response) {
        List<FileDiffClassification> perFile = response.getEffortBreakdown().getDiffClassification().getPerFile();
        assertEquals(1, perFile.size());
        return perFile.get(0);
    }
    private static LlmScoringRequest requestWithDiff(String file, String diff) {
        return LlmScoringRequest.builder()
                .fileChanges(Lists.newArrayList(fileChange(file, diff, true)))
                .build();
    }
    private static FileChange fileChange(String file, String diff, boolean linesJustificationRequired) {
        return FileChange.builder()
                .path(file)
                .diff(diff)
                .linesJustificationRequired(linesJustificationRequired)
                .build();
    }
    private static LlmScoringResponse responseWith(FileDiffClassification... perFile) {
        LlmScoringResponse response = new LlmScoringResponse();
        response.setEffortBreakdown(EffortBreakdown.builder()
                .diffClassification(DiffClassification.builder()
                        .perFile(Lists.newArrayList(perFile))
                        .build())
                .build());
        return response;
    }
    private static Map<String, String> kinds(String blockId, String kind) {
        Map<String, String> toReturn = Maps.newHashMap();
        toReturn.put(blockId, kind);
        return toReturn;
    }
    private static LinePair pair(int deleted, int added) {
        return LinePair.builder().deleted(deleted).added(added).build();
    }
}
