package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JavaLineFiltersTest {
    @Test
    void noneRejectsEverythingAsEffective() {
        assertFalse(JavaLineFilters.NONE.test(""));
        assertFalse(JavaLineFilters.NONE.test("int x = 1;"));
        assertFalse(JavaLineFilters.NONE.test("// comment"));
        assertFalse(JavaLineFilters.NONE.test("import x.Y;"));
    }
    @Test
    void commentMatchesOnlyStandaloneComments() {
        assertTrue(JavaLineFilters.COMMENT.test("// comment"));
        assertTrue(JavaLineFilters.COMMENT.test("/* block */"));
        assertTrue(JavaLineFilters.COMMENT.test("* javadoc"));
        assertFalse(JavaLineFilters.COMMENT.test("int x = 1;"));
        assertFalse(JavaLineFilters.COMMENT.test("import x.Y;"));
    }
    @Test
    void commentOrImportMatchesBothCategories() {
        assertTrue(JavaLineFilters.COMMENT_OR_IMPORT.test("// comment"));
        assertTrue(JavaLineFilters.COMMENT_OR_IMPORT.test("import x.Y;"));
        assertTrue(JavaLineFilters.COMMENT_OR_IMPORT.test("import static a.B.c;"));
        assertFalse(JavaLineFilters.COMMENT_OR_IMPORT.test("int x = 1;"));
        assertFalse(JavaLineFilters.COMMENT_OR_IMPORT.test("imports.resolve();"));
    }
}
