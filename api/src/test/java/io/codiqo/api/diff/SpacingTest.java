package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SpacingTest {
    @Test
    void spacingBetweenTokensIsSetAside() {
        assertEquals(Spacing.withoutSpacing("return processAnnotation(topics, clazz, annotation, bean);"),
                Spacing.withoutSpacing("return  processAnnotation(topics,clazz,  annotation, bean) ;"));
        assertEquals(Spacing.withoutSpacing("\tif (a == b) {"), Spacing.withoutSpacing("if(a==b){"));
        assertEquals(Spacing.withoutSpacing("x = a + b;"), Spacing.withoutSpacing("x=a+b;"), "a word beside an operator does not join it");
    }
    @Test
    void spacingThatKeepsTwoTokensApartCounts() {
        assertNotEquals(Spacing.withoutSpacing("return new Builder();"), Spacing.withoutSpacing("return newBuilder();"));
        assertNotEquals(Spacing.withoutSpacing("i = j + ++k;"), Spacing.withoutSpacing("i = j++ + k;"));
        assertNotEquals(Spacing.withoutSpacing("a - > b"), Spacing.withoutSpacing("a -> b"));
        assertEquals(Spacing.withoutSpacing("final  int x;"), Spacing.withoutSpacing("final int x;"), "two spaces keep the words apart as one does");
    }
    @Test
    void whitespaceInsideALiteralIsKept() {
        assertNotEquals(Spacing.withoutSpacing("log.info(\"a  b\");"), Spacing.withoutSpacing("log.info(\"a b\");"));
        assertNotEquals(Spacing.withoutSpacing("char c = ' ';"), Spacing.withoutSpacing("char c = '';"));
        assertNotEquals(Spacing.withoutSpacing("const s = `x ${y}`;"), Spacing.withoutSpacing("const s = `x${y}`;"));
    }
    @Test
    void aLiteralEndsAtItsNextQuoteAsHiddenSourceModeReadsIt() {
        assertEquals(Spacing.withoutSpacing("s = \"say \\\" a  b\";"), Spacing.withoutSpacing("s = \"say \\\" a b\";"),
                "an escaped quote closes the literal, so the rest of the string reads as code: a known, documented miss");
        assertNotEquals(Spacing.withoutSpacing("return \"\\\"\" + name + \"\\\"\";"), Spacing.withoutSpacing("return \"\\\"\"+name+\"\\\"\";"),
                "read without escapes, \" + name + \" is a literal, so this spacing reads as a change: the miss that errs towards work");
    }
    @Test
    void aQuoteLeftOpenKeepsTheRestOfItsLine() {
        assertNotEquals(Spacing.withoutSpacing("fn f(x: &'a  str)"), Spacing.withoutSpacing("fn f(x: &'a str)"),
                "a lone apostrophe keeps the spacing after it, which only ever reads a line as changed");
    }
}
