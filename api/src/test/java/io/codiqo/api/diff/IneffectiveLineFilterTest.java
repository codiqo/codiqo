package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IneffectiveLineFilterTest {
    private static final IneffectiveLineFilter C_STYLE = new IneffectiveLineFilter(CommentSyntax.C_STYLE, IneffectiveLineFilter.IMPORT_PREFIX);
    private static final IneffectiveLineFilter XML = new IneffectiveLineFilter(CommentSyntax.XML, null);

    @Test
    void commentFilterMatchesCommentsOnly() {
        assertTrue(C_STYLE.commentFilter().test("// comment"));
        assertTrue(C_STYLE.commentFilter().test("/* block */"));
        assertFalse(C_STYLE.commentFilter().test("import x.Y;"), "imports are not comments");
        assertFalse(C_STYLE.commentFilter().test("int x = 1;"));
    }
    @Test
    void commentOrImportFilterMatchesBothForCStyle() {
        assertTrue(C_STYLE.commentOrImportFilter().test("// c"));
        assertTrue(C_STYLE.commentOrImportFilter().test("import x.Y;"));
        assertTrue(C_STYLE.commentOrImportFilter().test("import \"common.proto\";"));
        assertFalse(C_STYLE.commentOrImportFilter().test("int x = 1;"));
    }
    @Test
    void xmlFiltersCommentsButHasNoImportConcept() {
        assertTrue(XML.commentFilter().test("<!-- a comment -->"));
        assertFalse(XML.commentOrImportFilter().test("import x.Y;"), "XML has no import prefix");
        assertFalse(XML.commentFilter().test("<version>1.0</version>"));
    }
    @Test
    void noneFiltersNothingButBlanks() {
        assertFalse(IneffectiveLineFilter.NONE.commentFilter().test("// comment"));
        assertFalse(IneffectiveLineFilter.NONE.commentOrImportFilter().test("import x.Y;"));
        assertTrue(IneffectiveLineFilter.NONE.isNone());
        assertFalse(C_STYLE.isNone());
    }
}
