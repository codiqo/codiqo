package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommentSyntaxTest {
    @Test
    void cStyleMatchesCommentLines() {
        assertTrue(CommentSyntax.C_STYLE.isCommentLine("// comment"));
        assertTrue(CommentSyntax.C_STYLE.isCommentLine("/* block */"));
        assertTrue(CommentSyntax.C_STYLE.isCommentLine("* javadoc"));
        assertTrue(CommentSyntax.C_STYLE.isCommentLine("*/"));
        assertTrue(CommentSyntax.C_STYLE.isCommentLine("/* unterminated block"));
    }
    @Test
    void cStyleRejectsLinesWithTrailingCode() {
        assertFalse(CommentSyntax.C_STYLE.isCommentLine("int x = 1; // comment"));
        assertFalse(CommentSyntax.C_STYLE.isCommentLine("/* block */ int x = 1;"));
        assertFalse(CommentSyntax.C_STYLE.isCommentLine("*/ int x = 1;"));
        assertFalse(CommentSyntax.C_STYLE.isCommentLine("String marker = \"// not comment\";"));
        assertFalse(CommentSyntax.C_STYLE.isCommentLine(""));
    }
    @Test
    void xmlMatchesMarkupComments() {
        assertTrue(CommentSyntax.XML.isCommentLine("<!-- a comment -->"));
        assertTrue(CommentSyntax.XML.isCommentLine("<!-- open block"));
        assertTrue(CommentSyntax.XML.isCommentLine("-->"));
        assertFalse(CommentSyntax.XML.isCommentLine("<version>1.0</version>"));
        assertFalse(CommentSyntax.XML.isCommentLine("--> <dependency>"));
    }
    @Test
    void noneMatchesNothing() {
        assertFalse(CommentSyntax.NONE.isCommentLine("// comment"));
        assertFalse(CommentSyntax.NONE.isCommentLine("<!-- c -->"));
        assertFalse(CommentSyntax.NONE.isCommentLine("code"));
    }
}
