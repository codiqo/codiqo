package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

class EffectiveLineParserTest {
    private static final String DIFF = String.join("\n",
            "diff --git a/A.java b/A.java",
            "--- a/A.java",
            "+++ b/A.java",
            "@@ -1,5 +1,8 @@",
            " package p;",
            "-import x.Old;",
            "+import x.New;",
            "+// added comment",
            "+",
            "+int added = 1;",
            "-int removed = 2;",
            " int kept = 3;",
            "") + "\n";

    @Test
    void parseAddedLinesReturnsAllAddedLineNumbers() {
        assertEquals(ImmutableSet.of(2, 3, 4, 5), EffectiveLineParser.parseAddedLines(DIFF));
    }
    @Test
    void parseEffectiveAddedLinesFiltersBlanksAndComments() {
        Set<Integer> effective = EffectiveLineParser.parseEffectiveAddedLines(DIFF, JavaLineFilters.COMMENT);
        assertEquals(ImmutableSet.of(2, 5), effective);
    }
    @Test
    void parseEffectiveAddedLinesWithNoneKeepsNonBlankLines() {
        Set<Integer> effective = EffectiveLineParser.parseEffectiveAddedLines(DIFF, JavaLineFilters.NONE);
        assertEquals(ImmutableSet.of(2, 3, 5), effective);
    }
    @Test
    void parseEffectiveDeletedLineContentsKeepsTrimmedNonImportNonCommentLines() {
        Map<Integer, List<String>> deleted = EffectiveLineParser.parseEffectiveDeletedLineContents(DIFF, JavaLineFilters.COMMENT_OR_IMPORT);
        assertEquals(ImmutableMap.of(6, List.of("int removed = 2;")), deleted);
    }
    @Test
    void parseEffectiveDeletionAnchorsCountsByNewSideAnchor() {
        Map<Integer, Integer> anchors = EffectiveLineParser.parseEffectiveDeletionAnchors(DIFF, JavaLineFilters.COMMENT_OR_IMPORT);
        assertEquals(ImmutableMap.of(6, 1), anchors);
    }
    @Test
    void walkVisitsAddedDeletedAndContextInOrder() {
        StringBuilder log = new StringBuilder();
        EffectiveLineParser.walk(DIFF, (kind, newLine, content) ->
                log.append(kind).append('@').append(newLine).append(':').append(content).append('\n'));
        String out = log.toString();
        assertTrue(out.contains("CONTEXT@1:package p;"));
        assertTrue(out.contains("DELETED@2:import x.Old;"));
        assertTrue(out.contains("ADDED@2:import x.New;"));
        assertTrue(out.contains("ADDED@3:// added comment"));
        assertTrue(out.contains("ADDED@4:"));
        assertTrue(out.contains("ADDED@5:int added = 1;"));
        assertTrue(out.contains("DELETED@6:int removed = 2;"));
        assertTrue(out.contains("CONTEXT@6:int kept = 3;"));
    }
}
