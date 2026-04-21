package io.codiqo.api.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeLineCounterTest {
    @Test
    void shouldRecognizeStandaloneCommentLinesUsedInDiffFiltering() {
        assertTrue(CodeLineCounter.isCommentLine("// comment"));
        assertTrue(CodeLineCounter.isCommentLine("/* block */"));
        assertTrue(CodeLineCounter.isCommentLine("* javadoc"));
        assertTrue(CodeLineCounter.isCommentLine("*/"));
    }
    @Test
    void shouldRejectMixedOrNonCommentLinesInDiffFiltering() {
        assertFalse(CodeLineCounter.isCommentLine("int x = 1; // comment"));
        assertFalse(CodeLineCounter.isCommentLine("/* block */ int x = 1;"));
        assertFalse(CodeLineCounter.isCommentLine("*/ int x = 1;"));
        assertFalse(CodeLineCounter.isCommentLine("String marker = \"// not comment\";"));
        assertFalse(CodeLineCounter.isCommentLine(""));
    }
}