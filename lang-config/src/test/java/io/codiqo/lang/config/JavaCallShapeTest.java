package io.codiqo.lang.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.codiqo.api.diff.CallShape;

class JavaCallShapeTest {
    private static final CallShape JAVA = FileKinds.ofLanguage(FileKinds.JAVA.getWireId()).orElseThrow().getCalls();

    @Test
    void countsPlainCallsOnASingleLine() {
        assertEquals(1, JAVA.countInLine("foo();"));
        assertEquals(2, JAVA.countInLine("a.b(); c.d();"));
    }
    @Test
    void countsNestedCallsIndependently() {
        assertEquals(2, JAVA.countInLine("outer(inner());"));
    }
    @Test
    void countsConstructorCalls() {
        assertEquals(1, JAVA.countInLine("new Foo(1, 2)"));
    }
    @Test
    void countsQualifiedCallsOnceEach() {
        assertEquals(2, JAVA.countInLine("a.b.c(x).d(y)"));
    }
    @Test
    void discardsControlFlowKeywordsThatShareCallShape() {
        assertEquals(0, JAVA.countInLine("if (x > 0) {"));
        assertEquals(0, JAVA.countInLine("while (cond) {"));
        assertEquals(0, JAVA.countInLine("for (int i = 0; i < n; i++) {"));
        assertEquals(0, JAVA.countInLine("switch (v) {"));
        assertEquals(0, JAVA.countInLine("} catch (IOException e) {"));
        assertEquals(0, JAVA.countInLine("synchronized (lock) {"));
        assertEquals(1, JAVA.countInLine("try (Resource r = open()) {"));
        assertEquals(1, JAVA.countInLine("return foo();"));
        assertEquals(0, JAVA.countInLine("return (x + y);"));
        assertEquals(1, JAVA.countInLine("throw (new RuntimeException());"));
    }
    @Test
    void returnsZeroForLinesWithoutCalls() {
        assertEquals(0, JAVA.countInLine(""));
        assertEquals(0, JAVA.countInLine("int x = 42;"));
        assertEquals(0, JAVA.countInLine("// no call here"));
    }
    @Test
    void countsCallsEvenInsideStringsAndCommentsAsKnownLimitation() {
        assertEquals(1, JAVA.countInLine("String s = \"foo()\";"));
        assertEquals(1, JAVA.countInLine("// bar();"));
    }
}
