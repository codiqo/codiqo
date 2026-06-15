package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IneffectiveLineProfileTest {
    @Test
    void cStyleFiltersCommentsAndImports() {
        assertTrue(IneffectiveLineProfile.C_STYLE.isComment("// comment"));
        assertTrue(IneffectiveLineProfile.C_STYLE.isComment("/* block */"));
        assertTrue(IneffectiveLineProfile.C_STYLE.isComment("* javadoc"));
        assertFalse(IneffectiveLineProfile.C_STYLE.isComment("int x = 1;"));

        assertTrue(IneffectiveLineProfile.C_STYLE.isImport("import x.Y;"));
        assertTrue(IneffectiveLineProfile.C_STYLE.isImport("import \"common.proto\";"), "proto imports start with 'import '");
        assertFalse(IneffectiveLineProfile.C_STYLE.isImport("imports.resolve();"));
    }
    @Test
    void xmlFiltersMarkupCommentsButNotImports() {
        assertTrue(IneffectiveLineProfile.XML.isComment("<!-- a comment -->"));
        assertTrue(IneffectiveLineProfile.XML.isComment("<!-- open block"));
        assertTrue(IneffectiveLineProfile.XML.isComment("-->"));
        assertFalse(IneffectiveLineProfile.XML.isComment("<version>1.0</version>"));
        assertFalse(IneffectiveLineProfile.XML.isComment("--> <dependency>"), "real content after a comment close is not cosmetic");

        assertFalse(IneffectiveLineProfile.XML.isImport("import x.Y;"), "XML has no import-line concept");
    }
    @Test
    void noneFiltersNothingButBlanks() {
        assertFalse(IneffectiveLineProfile.NONE.isComment("// comment"));
        assertFalse(IneffectiveLineProfile.NONE.isComment("<!-- c -->"));
        assertFalse(IneffectiveLineProfile.NONE.isImport("import x.Y;"));
    }
    @Test
    void commentOrImportFilterCombinesBothForCStyle() {
        assertTrue(IneffectiveLineProfile.C_STYLE.commentOrImportFilter().test("// c"));
        assertTrue(IneffectiveLineProfile.C_STYLE.commentOrImportFilter().test("import x.Y;"));
        assertFalse(IneffectiveLineProfile.C_STYLE.commentOrImportFilter().test("int x = 1;"));
    }
}
