package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.codiqo.api.code.DeclaredType;
import io.codiqo.api.code.TypeReference;
import io.codiqo.api.code.TypeReferenceKind;
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
import net.sourceforge.pmd.util.log.PmdReporter;

class JavaTypeGraphReaderTest {
    private static final JavaLanguageModule LANG = new JavaLanguageModule();

    @Test
    void typeArgumentsOfASupertypeAreNotSupertypes() throws Exception {
        String source = "interface Api {}"
                + " abstract class Model {}"
                + " class Handler implements java.util.function.Function<Api, Model> { public Model apply(Api api) { return null; } }"
                + " class Lister extends java.util.ArrayList<Model> {}"
                + " class Impl implements Api {}";

        Set<String> inherits = read(source).stream()
                .filter(reference -> reference.getKind() == TypeReferenceKind.INHERIT)
                .map(reference -> reference.getFrom() + " -> " + reference.getTo())
                .collect(Collectors.toSet());

        assertEquals(Set.of("Handler -> java.util.function.Function", "Lister -> java.util.ArrayList", "Impl -> Api"), inherits);
    }
    private static List<TypeReference> read(String source) throws Exception {
        LanguagePropertyBundle bundle = LANG.newPropertyBundle();
        try (TextFile file = TextFile.forCharSeq(source, FileId.fromPathLikeString("Test.java"), LANG.getDefaultVersion())) {
            try (TextDocument doc = TextDocument.create(file)) {
                Map<Language, LanguagePropertyBundle> props = Map.of(LANG, bundle);
                try (LanguageProcessorRegistry registry = LanguageProcessorRegistry.create(LanguageRegistry.singleton(LANG), props, PmdReporter.quiet())) {
                    Parser parser = registry.getProcessor(LANG).services().getParser();
                    ASTCompilationUnit tree = (ASTCompilationUnit) parser.parse(new ParserTask(doc, SemanticErrorReporter.noop(), registry));

                    List<DeclaredType> types = new ArrayList<>();
                    List<TypeReference> toReturn = new ArrayList<>();
                    JavaTypeGraphReader.read(tree, new File("Test.java"), false, types, toReturn);
                    return toReturn;
                }
            }
        }
    }
}
