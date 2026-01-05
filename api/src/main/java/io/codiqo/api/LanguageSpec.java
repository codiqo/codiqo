package io.codiqo.api;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Collection;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.FileAnalysis;
import net.sourceforge.pmd.lang.Language;

public interface LanguageSpec extends LanguageServerProjectImporter, Closeable {
    Language lang();
    Collection<String> pmdRules();
    boolean supportsCpd();
    void identifyAffectedSymbols(FileAnalysis analysis, Object symbol, Path path, Collection<Integer> modifiedLines);
    Collection<CodeBlockInfo> parse(Path file, String source);
}
