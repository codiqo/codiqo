package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;


import io.codiqo.llm.MovedLineDetector.MoveCandidate;
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

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request);

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

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request);

        FileDiffClassification entry = soleEntry(response);
        assertEquals(List.of(pair(11, 11)), entry.getInPlaceModifyPairs(), "B1 labeled inPlace");
        assertEquals(List.of(pair(13, 13)), entry.getTrueModifyPairs(), "B2 unlabeled → trueModify");
        assertEquals(List.of(14), entry.getPureDelete());
    }
    @Test
    void cosmeticLinesAreRemovedBeforePairing() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticAdded(new ArrayList<>(List.of(13)))
                .build());
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request);

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
                .cosmeticAdded(new ArrayList<>(List.of(10, 999))) // context line and nonexistent line
                .build());
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request);

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
                .fileChanges(new ArrayList<>(List.of(eligible, config)))
                .build();

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request);

        List<FileDiffClassification> perFile = response.getEffortBreakdown().getDiffClassification().getPerFile();
        assertEquals(1, perFile.size(), "only eligible files survive derivation");
        assertEquals("Foo.java", perFile.get(0).getFile());
    }
    @Test
    void confirmedMovedLinesAreExcludedFromPairing() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder().file("Foo.java").build());
        response.getEffortBreakdown().getDiffClassification().setConfirmedMoveIds(new ArrayList<>(List.of("M1")));
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request, List.of(candidate("M1", "Foo.java", 14, "Foo.java", 13)));

        FileDiffClassification entry = soleEntry(response);
        assertEquals(List.of(13), entry.getMovedAdded());
        assertEquals(List.of(14), entry.getMovedDeleted());
        assertEquals(List.of(pair(11, 11)), entry.getTrueModifyPairs(), "B1 pairing unaffected by the move");
        assertEquals(List.of(13), entry.getPureDelete(),
                "B2: added 13 and deleted 14 left as moved → remaining deleted 13 is pure");
        assertTrue(entry.getPureAdd().isEmpty());
        assertEquals(List.of("M1"), response.getEffortBreakdown().getDiffClassification().getConfirmedMoveIds());
    }
    @Test
    void movedBeatsCosmeticCitation() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder()
                .file("Foo.java")
                .cosmeticAdded(new ArrayList<>(List.of(13)))
                .build());
        response.getEffortBreakdown().getDiffClassification().setConfirmedMoveIds(new ArrayList<>(List.of("M1")));
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request, List.of(candidate("M1", "Foo.java", 14, "Foo.java", 13)));

        FileDiffClassification entry = soleEntry(response);
        assertTrue(entry.getCosmeticAdded().isEmpty(), "a confirmed moved line beats its cosmetic citation");
        assertEquals(List.of(13), entry.getMovedAdded());
    }
    @Test
    void unknownConfirmedMoveIdsAreDropped() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder().file("Foo.java").build());
        response.getEffortBreakdown().getDiffClassification().setConfirmedMoveIds(new ArrayList<>(List.of("M9")));
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request, List.of(candidate("M1", "Foo.java", 14, "Foo.java", 13)));

        FileDiffClassification entry = soleEntry(response);
        assertTrue(response.getEffortBreakdown().getDiffClassification().getConfirmedMoveIds().isEmpty(),
                "ids outside the candidate set are sanitized away");
        assertTrue(entry.getMovedAdded().isEmpty());
        assertTrue(entry.getMovedDeleted().isEmpty());
    }
    @Test
    void movedPairsMergeIntoMovedSets() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder().file("Foo.java").build());
        response.getEffortBreakdown().getDiffClassification().setMovedPairs(new ArrayList<>(List.of("Foo.java:14->Foo.java:13")));
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request);

        FileDiffClassification entry = soleEntry(response);
        assertEquals(List.of(13), entry.getMovedAdded());
        assertEquals(List.of(14), entry.getMovedDeleted());
        assertEquals(List.of(pair(11, 11)), entry.getTrueModifyPairs(), "B1 pairing unaffected by the pair");
        assertEquals(List.of(13), entry.getPureDelete());
        assertEquals(List.of("Foo.java:14->Foo.java:13"),
                response.getEffortBreakdown().getDiffClassification().getMovedPairs());
    }
    @Test
    void invalidMovedPairsAreDropped() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder().file("Foo.java").build());
        response.getEffortBreakdown().getDiffClassification().setMovedPairs(new ArrayList<>(List.of(
                "garbage",
                "Foo.java:999->Foo.java:13",
                "Foo.java:14->Foo.java:999")));
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request);

        FileDiffClassification entry = soleEntry(response);
        assertTrue(response.getEffortBreakdown().getDiffClassification().getMovedPairs().isEmpty(),
                "unparseable and non-candidate citations are sanitized away");
        assertTrue(entry.getMovedAdded().isEmpty());
        assertTrue(entry.getMovedDeleted().isEmpty());
        assertEquals(2, entry.getTrueModifyPairs().size(), "pairing unaffected by dropped pairs");
    }
    @Test
    void movedPairOverlappingConfirmedMoveIsDropped() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder().file("Foo.java").build());
        response.getEffortBreakdown().getDiffClassification().setConfirmedMoveIds(new ArrayList<>(List.of("M1")));
        response.getEffortBreakdown().getDiffClassification().setMovedPairs(new ArrayList<>(List.of("Foo.java:14->Foo.java:13")));
        LlmScoringRequest request = requestWithDiff("Foo.java", DIFF);

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request, List.of(candidate("M1", "Foo.java", 14, "Foo.java", 13)));

        FileDiffClassification entry = soleEntry(response);
        assertEquals(List.of(13), entry.getMovedAdded(), "the line is moved exactly once");
        assertEquals(List.of(14), entry.getMovedDeleted());
        assertEquals(List.of("Foo.java:14->Foo.java:13"),
                response.getEffortBreakdown().getDiffClassification().getMovedPairs(),
                "materialized list carries the confirmed candidate's pair; the duplicate citation is dropped");
    }
    @Test
    void crossFileMoveSplitsSidesAcrossFiles() {
        LlmScoringResponse response = responseWith(FileDiffClassification.builder().file("A.java").build());
        response.getEffortBreakdown().getDiffClassification().setConfirmedMoveIds(new ArrayList<>(List.of("M1")));
        LlmScoringRequest request = LlmScoringRequest.builder()
                .fileChanges(new ArrayList<>(List.of(fileChange("A.java", DIFF, true), fileChange("B.java", DIFF, true))))
                .build();

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request, List.of(candidate("M1", "A.java", 14, "B.java", 13)));

        List<FileDiffClassification> perFile = response.getEffortBreakdown().getDiffClassification().getPerFile();
        assertEquals(2, perFile.size());
        assertEquals(List.of(14), perFile.get(0).getMovedDeleted(), "A.java carries the deleted side");
        assertTrue(perFile.get(0).getMovedAdded().isEmpty());
        assertEquals(List.of(13), perFile.get(1).getMovedAdded(), "B.java carries the added side");
        assertTrue(perFile.get(1).getMovedDeleted().isEmpty());
    }
    @Test
    void totalsReflectEffectiveTargets() {
        LlmScoringResponse response = new LlmScoringResponse();
        FileChange fc = fileChange("Foo.java", DIFF, true);
        fc.setLinesAdded(2);
        fc.setLinesDeleted(3);
        LlmScoringRequest request = LlmScoringRequest.builder().fileChanges(new ArrayList<>(List.of(fc))).build();

        new DiffClassificationDeriver(NoopLog.INSTANCE).derive(response, request);

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
                .fileChanges(new ArrayList<>(List.of(fileChange(file, diff, true))))
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
                        .perFile(new ArrayList<>(List.of(perFile)))
                        .build())
                .build());
        return response;
    }
    private static Map<String, String> kinds(String blockId, String kind) {
        Map<String, String> toReturn = new HashMap<>();
        toReturn.put(blockId, kind);
        return toReturn;
    }
    private static LinePair pair(int deleted, int added) {
        return LinePair.builder().deleted(deleted).added(added).build();
    }
    private static MoveCandidate candidate(String id, String fromFile, int fromLine, String toFile, int toLine) {
        return new MoveCandidate(id, fromFile, fromLine, toFile, toLine, 1.0, "moved content");
    }
}
