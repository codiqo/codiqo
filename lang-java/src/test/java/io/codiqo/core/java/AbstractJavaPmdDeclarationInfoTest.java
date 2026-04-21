package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
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
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.util.log.PmdReporter;

class AbstractJavaPmdDeclarationInfoTest {
    private static final JavaLanguageModule LANG = new JavaLanguageModule();

    @Test
    void getterIsTrivial() throws Exception {
        assertTrue(parseMethod("class C { int x; int getX() { return x; } }").isTrivial());
    }
    @Test
    void setterIsTrivial() throws Exception {
        assertTrue(parseMethod("class C { int x; void setX(int x) { this.x = x; } }").isTrivial());
    }
    @Test
    void constantReturnIsTrivial() throws Exception {
        assertTrue(parseMethod("class C { int answer() { return 42; } }").isTrivial());
    }
    @Test
    void arithmeticExpressionIsTrivial() throws Exception {
        assertTrue(parseMethod("class C { int add(int a, int b) { return a + b; } }").isTrivial());
    }
    @Test
    void fieldAssigningConstructorIsTrivial() throws Exception {
        assertTrue(parseConstructor("class C { int x; C(int x) { this.x = x; } }").isTrivial());
    }
    @Test
    void pureDelegateIsNotTrivial() throws Exception {
        assertFalse(parseMethod("class C { int h() { return hashCode(); } }").isTrivial());
    }
    @Test
    void wrapperConstructorIsNotTrivial() throws Exception {
        assertFalse(parseConstructor("class C { C() { this(0); } C(int x) {} }").isTrivial());
    }
    @Test
    void ternaryBranchingIsNotTrivial() throws Exception {
        assertFalse(parseMethod("class C { int sign(int x) { return x > 0 ? 1 : -1; } }").isTrivial());
    }
    @Test
    void ifElseBranchingIsNotTrivial() throws Exception {
        assertFalse(parseMethod("class C { int sign(int x) { if (x > 0) return 1; else return -1; } }").isTrivial());
    }
    @Test
    void forLoopIsNotTrivial() throws Exception {
        assertFalse(parseMethod("class C { int sum(int n) { int s = 0; for (int i = 0; i < n; i++) s += i; return s; } }").isTrivial());
    }
    @Test
    void switchOnlyIsNotTrivial() throws Exception {
        String source = "class C { int pick(int x) { switch (x) { case 1: return 10; case 2: return 20; default: return 0; } } }";
        assertFalse(parseMethod(source).isTrivial());
    }
    @Test
    void multiStatementConstructorIsNotTrivial() throws Exception {
        assertFalse(parseConstructor("class C { int a; int b; C(int a, int b) { this.a = a; this.b = b; } }").isTrivial());
    }
    private static CodeBlockInfo parseMethod(String source) throws Exception {
        return wrap(find(source, ASTMethodDeclaration.class));
    }
    private static CodeBlockInfo parseConstructor(String source) throws Exception {
        return wrap(find(source, ASTConstructorDeclaration.class));
    }
    private static CodeBlockInfo wrap(ASTExecutableDeclaration node) {
        SourceLocation location = SourceLocation.builder().startLine(1).endLine(1).startColumn(1).endColumn(1).build();
        if (node instanceof ASTMethodDeclaration) {
            return JavaPmdMethodInfo.builder()
                    .file(new File("Test.java"))
                    .location(location)
                    .node(node)
                    .invocations(Lists.newArrayList())
                    .body(node.getText().toString())
                    .build();
        }
        return JavaPmdConstructorInfo.builder()
                .file(new File("Test.java"))
                .location(location)
                .node(node)
                .invocations(Lists.newArrayList())
                .body(node.getText().toString())
                .build();
    }
    private static <T extends ASTExecutableDeclaration> T find(String source, Class<T> type) throws Exception {
        ASTCompilationUnit tree = parse(source);
        return tree.descendants(type).first();
    }
    private static ASTCompilationUnit parse(String source) throws Exception {
        LanguagePropertyBundle bundle = LANG.newPropertyBundle();
        bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);
        FileId fileId = FileId.fromPathLikeString("Test.java");
        try (TextFile file = TextFile.forCharSeq(source, fileId, LANG.getDefaultVersion())) {
            try (TextDocument doc = TextDocument.create(file)) {
                LanguageRegistry registry = LanguageRegistry.singleton(LANG);
                Map<Language, LanguagePropertyBundle> props = ImmutableMap.of(LANG, bundle);
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
