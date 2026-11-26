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
    }
    @Test
    void whitespaceInsideALiteralIsKept() {
        assertNotEquals(Spacing.withoutSpacing("log.info(\"a  b\");"), Spacing.withoutSpacing("log.info(\"a b\");"));
        assertNotEquals(Spacing.withoutSpacing("char c = ' ';"), Spacing.withoutSpacing("char c = '';"));
        assertNotEquals(Spacing.withoutSpacing("const s = `x ${y}`;"), Spacing.withoutSpacing("const s = `x${y}`;"));
    }
    @Test
    void anEscapedQuoteDoesNotCloseItsLiteral() {
        assertNotEquals(Spacing.withoutSpacing("s = \"say \\\" a  b\";"), Spacing.withoutSpacing("s = \"say \\\" a b\";"));
        assertEquals(Spacing.withoutSpacing("s = \"a\\\\\" +  b;"), Spacing.withoutSpacing("s = \"a\\\\\" + b;"),
                "an escaped backslash ends the escape, so the quote after it closes the literal");
    }
    @Test
    void aLiteralLeftOpenKeepsTheRestOfItsLine() {
        assertNotEquals(Spacing.withoutSpacing("fn f(x: &'a  str)"), Spacing.withoutSpacing("fn f(x: &'a str)"),
                "an apostrophe that opens no literal over-reads towards a real change, never away from one");
    }
}
