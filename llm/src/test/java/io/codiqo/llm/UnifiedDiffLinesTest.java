package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Sets;

import io.codiqo.api.diff.IneffectiveLineProfile;
import io.codiqo.llm.UnifiedDiffLines.ChangeBlock;

class UnifiedDiffLinesTest {
    // shape of the real a95206ac bootstrapper regression: two hunks, old/new numbering drifting
    // apart because the new file is shorter than the old one
    private static final String TWO_HUNK_DIFF = String.join("\n",
            "diff --git a/src/Foo.java b/src/Foo.java",
            "index 87bbd79..da3327e 100644",
            "--- a/src/Foo.java",
            "+++ b/src/Foo.java",
            "@@ -5,7 +5,6 @@",
            " import java.io.IOException;",
            "-import java.util.Arrays;",
            " import java.util.Map;",
            "-import org.springframework.kafka.test.EmbeddedKafkaZKBroker;",
            "+import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;",
            " import java.util.Objects;",
            "@@ -280,8 +278,7 @@",
            "     if (context.containsBean(EmbeddedKafkaBroker.BEAN_NAME)) {",
            "-        Map<String, EmbeddedKafkaZKBroker> mzk = lookup();",
            "+        Map<String, EmbeddedKafkaKraftBroker> mzk = lookup();",
            "         if (MapUtils.isNotEmpty(mzk)) {",
            "-            if (Objects.nonNull(broker.getBrokerAddresses())) {",
            "-                KafkaServiceInfo ksi = old(broker);",
            "+            if (Objects.nonNull(broker.getBrokersAsString())) {",
            "             addUps(ksi);",
            "\\ No newline at end of file");

    @Test
    void parseTracksOldAndNewCountersAcrossHunks() {
        UnifiedDiffLines lines = UnifiedDiffLines.parse(TWO_HUNK_DIFF, IneffectiveLineProfile.C_STYLE);

        assertEquals(Sets.newTreeSet(Set.of(6, 8, 281, 283, 284)), lines.getDeletedLines(),
                "deleted lines carry old-file numbers");
        assertEquals(Sets.newTreeSet(Set.of(7, 279, 281)), lines.getAddedLines(),
                "added lines carry new-file numbers");
    }
    @Test
    void candidatesExcludeImportsBlanksAndComments() {
        UnifiedDiffLines lines = UnifiedDiffLines.parse(TWO_HUNK_DIFF, IneffectiveLineProfile.C_STYLE);

        assertEquals(Sets.newTreeSet(Set.of(281, 283, 284)), lines.getCandidateDeletedLines(),
                "all hunk-1 deletions are imports → filtered out of the candidate set");
        assertEquals(Sets.newTreeSet(Set.of(279, 281)), lines.getCandidateAddedLines(),
                "the hunk-1 addition is an import → filtered out of the candidate set");
    }
    @Test
    void blocksGroupContiguousCandidateRuns() {
        List<ChangeBlock> blocks = UnifiedDiffLines.parse(TWO_HUNK_DIFF, IneffectiveLineProfile.C_STYLE).getBlocks();

        assertEquals(2, blocks.size(), "import-only run produces no block; two code runs remain");
        assertEquals("B1", blocks.get(0).getId());
        assertEquals(List.of(281), blocks.get(0).getDeletedLines());
        assertEquals(List.of(279), blocks.get(0).getAddedLines());
        assertEquals("B2", blocks.get(1).getId());
        assertEquals(List.of(283, 284), blocks.get(1).getDeletedLines());
        assertEquals(List.of(281), blocks.get(1).getAddedLines());
    }
    @Test
    void annotateTagsOnlyCandidateLines() {
        String annotated = UnifiedDiffLines.parse(TWO_HUNK_DIFF, IneffectiveLineProfile.C_STYLE).getAnnotated();

        assertTrue(annotated.contains("\n-6|import java.util.Arrays;"),
                "filtered deletion keeps its number but gets no block tag");
        assertTrue(annotated.contains("\n+7|import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;"),
                "filtered addition keeps its number but gets no block tag");
        assertTrue(annotated.contains("\n-281|B1|        Map<String, EmbeddedKafkaZKBroker> mzk = lookup();"),
                "candidate deletion carries old-file number and block id");
        assertTrue(annotated.contains("\n+279|B1|        Map<String, EmbeddedKafkaKraftBroker> mzk = lookup();"),
                "candidate addition carries new-file number and block id");
        assertTrue(annotated.contains("\n-283|B2|            if (Objects.nonNull(broker.getBrokerAddresses())) {"),
                "second run gets the next block id");
        assertTrue(annotated.contains("\n-284|B2|                KafkaServiceInfo ksi = old(broker);"),
                "back-to-back deletions share the block");
        assertTrue(annotated.contains("\n+281|B2|            if (Objects.nonNull(broker.getBrokersAsString())) {"),
                "addition after a deletion run stays in the same block");
        assertTrue(annotated.contains("\n     if (context.containsBean(EmbeddedKafkaBroker.BEAN_NAME)) {"),
                "context lines stay unannotated");
        assertTrue(annotated.contains("\n--- a/src/Foo.java"), "pre-hunk file headers stay untouched");
        assertTrue(annotated.contains("\n\\ No newline at end of file"), "no-newline marker stays untouched");
    }
    @Test
    void noneProfileKeepsImportsAndComments() {
        UnifiedDiffLines lines = UnifiedDiffLines.parse(TWO_HUNK_DIFF, IneffectiveLineProfile.NONE);

        assertEquals(Sets.newTreeSet(Set.of(6, 8, 281, 283, 284)), lines.getCandidateDeletedLines(),
                "NONE filters only blanks; import/comment filtering lives in the C_STYLE/XML profiles");
        assertEquals(Sets.newTreeSet(Set.of(7, 279, 281)), lines.getCandidateAddedLines());
    }
    @Test
    void metadataAndMissingHunksProduceNoLines() {
        UnifiedDiffLines lines = UnifiedDiffLines.parse("just some text\n+not a real diff line", IneffectiveLineProfile.C_STYLE);

        assertTrue(lines.getAddedLines().isEmpty(), "lines before any hunk header are metadata");
        assertTrue(lines.getDeletedLines().isEmpty());
        assertTrue(lines.getBlocks().isEmpty());
        assertEquals("just some text\n+not a real diff line", lines.getAnnotated(), "metadata round-trips unchanged");
    }
}
