package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MovedPairTest {
    @Test
    void parsesCanonicalFormAndRoundTrips() {
        MovedPair pair = MovedPair.parse("a/Foo.java:11->b/Bar.java:29").orElseThrow();

        assertEquals("a/Foo.java", pair.getFromFile());
        assertEquals(11, pair.getFromLine());
        assertEquals("b/Bar.java", pair.getToFile());
        assertEquals(29, pair.getToLine());
        assertEquals("a/Foo.java:11->b/Bar.java:29", pair.format());
    }
    @Test
    void toleratesSurroundingWhitespace() {
        MovedPair pair = MovedPair.parse("  Foo.java:11  ->  Bar.java:29  ").orElseThrow();

        assertEquals("Foo.java:11->Bar.java:29", pair.format(), "format() is canonical regardless of input spacing");
    }
    @Test
    void rejectsMalformedCitations() {
        assertTrue(MovedPair.parse(null).isEmpty());
        assertTrue(MovedPair.parse("  ").isEmpty());
        assertTrue(MovedPair.parse("garbage").isEmpty());
        assertTrue(MovedPair.parse("Foo.java:11").isEmpty(), "missing arrow");
        assertTrue(MovedPair.parse("Foo.java:11->Bar.java:29->Baz.java:3").isEmpty(), "more than one arrow");
        assertTrue(MovedPair.parse("Foo.java->Bar.java:29").isEmpty(), "missing line number");
        assertTrue(MovedPair.parse("Foo.java:eleven->Bar.java:29").isEmpty(), "non-numeric line");
        assertTrue(MovedPair.parse("Foo.java:0->Bar.java:29").isEmpty(), "line numbers are 1-based");
        assertTrue(MovedPair.parse(":11->Bar.java:29").isEmpty(), "blank file");
    }
}
