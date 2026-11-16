package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

class CallShapeTest {
    @Test
    void noneCountsNoCall() {
        assertEquals(0, CallShape.NONE.countInLine("foo(bar(x));"));
        assertEquals(Set.of(), CallShape.NONE.getNotCalls());
    }
    @Test
    void aParenthesisedShapeSkipsOnlyItsOwnWordsThatAreNoCalls() {
        CallShape shape = CallShape.parenthesised(Set.of("when"));

        assertEquals(0, shape.countInLine("when (x) {"));
        assertEquals(2, shape.countInLine("if (a) $b(c);"), "if is no call only where the kind says so");
    }
}
