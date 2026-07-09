package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.LanguagePropertyBundle;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.ast.Parser;
import net.sourceforge.pmd.lang.ast.Parser.ParserTask;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.util.log.PmdReporter;

class InvocationCounterTest {
    private static final JavaLanguageModule LANG = new JavaLanguageModule();

    @Test
    void sameTypeFluentRunCountsOnce() throws Exception {
        assertEquals(1, countIn("class C { C self() { return this; } void m() { self().self().self(); } }", "m"));
    }
    @Test
    void builderSettersCollapseButTerminalOfDifferentTypeCounts() throws Exception {
        String source = "class C {"
                + "  static class B { B a() { return this; } B b() { return this; } C build() { return new C(); } }"
                + "  static B make() { return new B(); }"
                + "  void m() { make().a().b().build(); }"
                + "}";
        // make() head (1) + a()/b() collapse into it (same type B) + build() returns C, different type (1) = 2
        assertEquals(2, countIn(source, "m"));
    }
    @Test
    void crossTypeNavigationCountsPerHop() throws Exception {
        String source = "class C {"
                + "  static class A { B getB() { return new B(); } }"
                + "  static class B { String getS() { return \"\"; } }"
                + "  void m(A a) { a.getB().getS(); }"
                + "}";
        // getB() returns B (1), getS() returns String — different type from B (1) = 2
        assertEquals(2, countIn(source, "m"));
    }
    @Test
    void constructorThenSameTypeCallCollapses() throws Exception {
        assertEquals(1, countIn("class C { C run() { return this; } void m() { new C().run(); } }", "m"));
    }
    @Test
    void constructorThenDifferentTypeCallCountsEach() throws Exception {
        assertEquals(2, countIn("class C { String run() { return \"\"; } void m() { new C().run(); } }", "m"));
    }
    @Test
    void separateStatementCallsCountEach() throws Exception {
        assertEquals(2, countIn("class C { void x() {} void y() {} void m() { x(); y(); } }", "m"));
    }
    @Test
    void nestedArgumentCallsCountEach() throws Exception {
        assertEquals(2, countIn("class C { int b() { return 1; } int a(int v) { return v; } void m() { a(b()); } }", "m"));
    }
    @Test
    void unresolvedTypesAreCountedNotCollapsed() throws Exception {
        // Foo is undefined -> types UNKNOWN -> conservative: count both hops rather than collapse
        assertEquals(2, countIn("class C { void m(Foo f) { f.a().b(); } }", "m"));
    }
    private static int countIn(String source, String methodName) throws Exception {
        for (ASTExecutableDeclaration exec : parse(source).descendants(ASTExecutableDeclaration.class)) {
            if (exec instanceof ASTMethodDeclaration m && methodName.equals(m.getName())) {
                return InvocationCounter.collectDirect(exec).size();
            }
        }
        throw new IllegalArgumentException("method not found: " + methodName);
    }
    private static ASTCompilationUnit parse(String source) throws Exception {
        LanguagePropertyBundle bundle = LANG.newPropertyBundle();
        bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);
        FileId fileId = FileId.fromPathLikeString("Test.java");
        try (TextFile file = TextFile.forCharSeq(source, fileId, LANG.getDefaultVersion())) {
            try (TextDocument doc = TextDocument.create(file)) {
                LanguageRegistry registry = LanguageRegistry.singleton(LANG);
                Map<Language, LanguagePropertyBundle> props = Map.of(LANG, bundle);
                try (LanguageProcessorRegistry procRegistry = LanguageProcessorRegistry.create(registry, props,
                        PmdReporter.quiet())) {
                    Parser parser = procRegistry.getProcessor(LANG).services().getParser();
                    SemanticErrorReporter errorReporter = SemanticErrorReporter.noop();
                    ParserTask task = new ParserTask(doc, errorReporter, procRegistry);
                    return (ASTCompilationUnit) parser.parse(task);
                }
            }
        }
    }
}
