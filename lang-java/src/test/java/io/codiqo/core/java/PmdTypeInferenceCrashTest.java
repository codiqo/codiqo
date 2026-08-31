package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.util.log.PmdReporter;

/**
 * Pins pmd/pmd#4436, fixed upstream in PMD 7.27.0: PMD's type inference used to violate an
 * internal invariant on a diamond anonymous class whose type argument must be inferred from
 * the enclosing generic invocation. The snippet now parses, and this test fails if a PMD
 * upgrade ever reintroduces the crash. JavaLanguageSpec#parse keeps its per-file catch
 * regardless — it degrades to zero code units for the whole class of PMD crashes, not just
 * this one.
 */
class PmdTypeInferenceCrashTest {
    private static final JavaLanguageModule LANG = new JavaLanguageModule();
    private static final String DIAMOND_ANONYMOUS_SNIPPET = """
            package example;

            import java.util.List;

            public class ScopedServerProperties {
                abstract static class TypeToken<T> {
                    protected TypeToken() {}
                }
                interface PropertyFactory {
                    <T> T getScoped(String key, TypeToken<T> token, T def);
                }
                record Theme(String a, String b, String c) {}

                private final List<Theme> themes;

                public ScopedServerProperties(PropertyFactory pf) {
                    this.themes = pf.getScoped("example.autoconfiguration.themes",
                            new TypeToken<>() {}, List.of(new Theme("test", "Test", "Test")));
                }
            }
            """;

    @Test
    void diamondAnonymousClassParsesSincePmd727() {
        assertDoesNotThrow(() -> parse(DIAMOND_ANONYMOUS_SNIPPET));
    }
    private static void parse(String source) throws Exception {
        LanguagePropertyBundle bundle = LANG.newPropertyBundle();
        bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);

        FileId fileId = FileId.fromPathLikeString("ScopedServerProperties.java");
        try (TextFile file = TextFile.forCharSeq(source, fileId, LANG.getDefaultVersion())) {
            try (TextDocument doc = TextDocument.create(file)) {
                LanguageRegistry registry = LanguageRegistry.singleton(LANG);
                Map<Language, LanguagePropertyBundle> props = Map.of(LANG, bundle);
                try (LanguageProcessorRegistry procRegistry = LanguageProcessorRegistry.create(registry, props, PmdReporter.quiet())) {
                    Parser parser = procRegistry.getProcessor(LANG).services().getParser();
                    ParserTask task = new ParserTask(doc, SemanticErrorReporter.noop(), procRegistry);
                    parser.parse(task);
                }
            }
        }
    }
}
