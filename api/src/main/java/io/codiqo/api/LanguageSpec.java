package io.codiqo.api;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import net.sourceforge.pmd.lang.Language;

public interface LanguageSpec extends LanguageServerProjectImporter, Closeable {
    Language lang();
    boolean supportsCpd();
    void identifyAffectedSymbols(FileAnalysis analysis, Object symbol, File destination, Collection<Integer> modifiedLines) throws IOException;
    Collection<CodeBlockInfo> parse(File file, String source) throws IOException;
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
}
