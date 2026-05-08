package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

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

class JavaLineCountAnalyzerTest {
    private static final JavaLanguageModule LANG = new JavaLanguageModule();

    @Test
    void bareMethodCountsSignatureAndBraces() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m() {",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(0, counts.getCommentLines());
    }
    @Test
    void trailingLineCommentCountsAsBothCodeAndComment() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m() {",
                "        int x = 1; // trailing",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(1, counts.getCommentLines());
    }
    @Test
    void lineCommentOnlyLineIsCommentNotCode() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m() {",
                "        // only",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(1, counts.getCommentLines());
    }
    @Test
    void multiLineBlockCommentWithTrailingCodeCountsMixedLine() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m() {",
                "        /* start",
                "         * middle",
                "         */ int x = 1;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(3, counts.getCommentLines());
    }
    @Test
    void blockCommentBeforeCodeOnSameLineCountsAsBoth() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m() {",
                "        /* doc */ int x = 1;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(1, counts.getCommentLines());
    }
    @Test
    void inlineBlockCommentInSignatureCountsAsBoth() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    int add(int /* x */ a, int b /* y */) {",
                "        return a + b;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(1, counts.getCommentLines());
    }
    @Test
    void commentMarkersInsideStringsAndCharLiteralsAreNotComments() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    String m() {",
                "        String url = \"https://codiqo.io\";",
                "        char slash = '/';",
                "        String marker = \"/* not comment */\";",
                "        /* actual */",
                "        return marker + url + slash;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(6, counts.getCodeLines());
        assertEquals(1, counts.getCommentLines());
    }
    @Test
    void javadocAboveMethodIsNotCounted() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    /**",
                "     * javadoc line",
                "     */",
                "    void m() {",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(0, counts.getCommentLines());
    }
    @Test
    void trailingCommentAfterClosingBraceStillCountsOnBraceLine() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m() {",
                "        return;",
                "    } // end",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(0, counts.getCommentLines());
    }
    @Test
    void singleLineMethodWithInlineCommentAndTrailingCode() throws Exception {
        String source = "class C { void m() { int x = 1; /* c */ int y = 2; } }";

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(1, counts.getCodeLines());
        assertEquals(1, counts.getCommentLines());
    }
    @Test
    void abstractMethodCountsSignatureLineOnly() throws Exception {
        String source = String.join("\n",
                "abstract class C {",
                "    abstract void m();",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(1, counts.getCodeLines());
        assertEquals(0, counts.getCommentLines());
        assertEquals(0, counts.getBodyStartLine());
        assertEquals(0, counts.getBodyEndLine());
        assertEquals(0, counts.getBodyCodeLines());
        assertEquals(1, counts.getDeclarationCodeLines());
    }
    @Test
    void bodyCountsExcludeSingleLineSignature() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m() {",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(3, counts.getCodeLines());
        assertEquals(2, counts.getBodyStartLine());
        assertEquals(4, counts.getBodyEndLine());
        assertEquals(3, counts.getBodyCodeLines());
        assertEquals(1, counts.getDeclarationCodeLines());
    }
    @Test
    void bodyCountsExcludeMultiLineSignature() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m(",
                "            String a,",
                "            String b,",
                "            String c) {",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(6, counts.getCodeLines());
        assertEquals(5, counts.getBodyStartLine());
        assertEquals(7, counts.getBodyEndLine());
        assertEquals(3, counts.getBodyCodeLines());
        assertEquals(4, counts.getDeclarationCodeLines());
    }
    @Test
    void bodyCountsExcludeStackedAnnotationsInSignature() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    @Deprecated",
                "    @SuppressWarnings(\"unused\")",
                "    void m() {",
                "        int x = 1;",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(6, counts.getCodeLines());
        assertEquals(4, counts.getBodyStartLine());
        assertEquals(7, counts.getBodyEndLine());
        assertEquals(4, counts.getBodyCodeLines());
    }
    @Test
    void bodyCountsExcludeMultiLineThrowsClause() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m()",
                "            throws java.io.IOException,",
                "            IllegalStateException {",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(5, counts.getCodeLines());
        assertEquals(4, counts.getBodyStartLine());
        assertEquals(6, counts.getBodyEndLine());
        assertEquals(3, counts.getBodyCodeLines());
    }
    @Test
    void interfaceMethodHasNoBody() throws Exception {
        String source = String.join("\n",
                "interface I {",
                "    void m();",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(1, counts.getCodeLines());
        assertEquals(0, counts.getBodyStartLine());
        assertEquals(0, counts.getBodyEndLine());
        assertEquals(0, counts.getBodyCodeLines());
        assertEquals(1, counts.getDeclarationCodeLines());
    }
    @Test
    void declarationLineCountsBraceOnOwnLineAsBodyOnly() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    void m()",
                "    {",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(4, counts.getCodeLines());
        assertEquals(1, counts.getDeclarationCodeLines());
        assertEquals(3, counts.getBodyCodeLines());
        assertEquals(3, counts.getBodyStartLine());
    }
    @Test
    void declarationLineCountsStackedAnnotations() throws Exception {
        String source = String.join("\n",
                "class C {",
                "    @Deprecated",
                "    @SuppressWarnings(\"unused\")",
                "    void m() {",
                "        int x = 1;",
                "        return;",
                "    }",
                "}");

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(6, counts.getCodeLines());
        assertEquals(3, counts.getDeclarationCodeLines());
        assertEquals(4, counts.getBodyCodeLines());
    }
    @Test
    void declarationLineCountsSingleLineMethod() throws Exception {
        String source = "class C { void m() { int x = 1; } }";

        JavaLineCountAnalyzer.LineCounts counts = analyzeFirstMethod(source);

        assertEquals(1, counts.getCodeLines());
        assertEquals(1, counts.getDeclarationCodeLines());
        assertEquals(1, counts.getBodyCodeLines());
    }
    private static JavaLineCountAnalyzer.LineCounts analyzeFirstMethod(String source) throws Exception {
        ASTExecutableDeclaration node = parse(source).descendants(ASTMethodDeclaration.class).first();
        return JavaLineCountAnalyzer.analyze(node);
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
