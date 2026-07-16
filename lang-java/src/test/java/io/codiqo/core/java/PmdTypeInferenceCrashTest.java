package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Pins pmd/pmd#4436: PMD's type inference violates an internal invariant on a diamond
 * anonymous class whose type argument must be inferred from the enclosing generic
 * invocation. JavaLanguageSpec#parse guards against this whole class of PMD crashes
 * with a per-file catch that degrades to zero code units. If this test starts failing
 * after a PMD upgrade, the upstream bug is fixed — the guard stays either way.
 */
class PmdTypeInferenceCrashTest {
    private static final JavaLanguageModule LANG = new JavaLanguageModule();
    private static final String DIAMOND_ANONYMOUS_SNIPPET = """
            package offerchain;

            import java.util.List;

            public class OfferChainServerProperties {
                abstract static class TypeToken<T> {
                    protected TypeToken() {}
                }
                interface PropertyFactory {
                    <T> T getScoped(String key, TypeToken<T> token, T def);
                }
                record Theme(String a, String b, String c) {}

                private final List<Theme> themes;

                public OfferChainServerProperties(PropertyFactory pf) {
                    this.themes = pf.getScoped("offerchain.autoconfiguration.themes",
                            new TypeToken<>() {}, List.of(new Theme("test", "Test", "Test")));
                }
            }
            """;

    @Test
    void diamondAnonymousClassStillCrashesPmd() {
        RuntimeException err = assertThrows(RuntimeException.class, () -> parse(DIAMOND_ANONYMOUS_SNIPPET));
        assertTrue(err.getMessage().contains("Disambiguation pass should resolve everything except qualified ctor calls"));
    }
    private static void parse(String source) throws Exception {
        LanguagePropertyBundle bundle = LANG.newPropertyBundle();
        bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);

        FileId fileId = FileId.fromPathLikeString("OfferChainServerProperties.java");
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
