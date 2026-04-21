package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaInvocationCounterTest {
    @Test
    void countsPlainCallsOnASingleLine() {
        assertEquals(1, JavaInvocationCounter.countInLine("foo();"));
        assertEquals(2, JavaInvocationCounter.countInLine("a.b(); c.d();"));
    }
    @Test
    void countsNestedCallsIndependently() {
        assertEquals(2, JavaInvocationCounter.countInLine("outer(inner());"));
    }
    @Test
    void countsConstructorCalls() {
        assertEquals(1, JavaInvocationCounter.countInLine("new Foo(1, 2)"));
    }
    @Test
    void countsQualifiedCallsOnceEach() {
        assertEquals(2, JavaInvocationCounter.countInLine("a.b.c(x).d(y)"));
    }
    @Test
    void discardsControlFlowKeywordsThatShareCallShape() {
        assertEquals(0, JavaInvocationCounter.countInLine("if (x > 0) {"));
        assertEquals(0, JavaInvocationCounter.countInLine("while (cond) {"));
        assertEquals(0, JavaInvocationCounter.countInLine("for (int i = 0; i < n; i++) {"));
        assertEquals(0, JavaInvocationCounter.countInLine("switch (v) {"));
        assertEquals(0, JavaInvocationCounter.countInLine("} catch (IOException e) {"));
        assertEquals(0, JavaInvocationCounter.countInLine("synchronized (lock) {"));
        assertEquals(1, JavaInvocationCounter.countInLine("try (Resource r = open()) {"));
        assertEquals(1, JavaInvocationCounter.countInLine("return foo();"));
        assertEquals(0, JavaInvocationCounter.countInLine("return (x + y);"));
        assertEquals(1, JavaInvocationCounter.countInLine("throw (new RuntimeException());"));
    }
    @Test
    void returnsZeroForLinesWithoutCalls() {
        assertEquals(0, JavaInvocationCounter.countInLine(""));
        assertEquals(0, JavaInvocationCounter.countInLine("int x = 42;"));
        assertEquals(0, JavaInvocationCounter.countInLine("// no call here"));
    }
    @Test
    void countsCallsEvenInsideStringsAndCommentsAsKnownLimitation() {
        assertEquals(1, JavaInvocationCounter.countInLine("String s = \"foo()\";"));
        assertEquals(1, JavaInvocationCounter.countInLine("// bar();"));
    }
}
