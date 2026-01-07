package io.codiqo.api;

import java.io.Closeable;
import java.io.File;
import java.util.Collection;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import net.sourceforge.pmd.lang.Language;

public interface LanguageSpec extends LanguageServerProjectImporter, Closeable {
    Language lang();
    boolean supportsCpd();
    void identifyAffectedSymbols(FileAnalysis analysis, Object symbol, File destination, Collection<Integer> modifiedLines);
    Collection<CodeBlockInfo> parse(File file, String source);
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis);
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis);
}
