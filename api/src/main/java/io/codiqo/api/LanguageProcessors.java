package io.codiqo.api;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;

import io.codiqo.api.diff.CommitAnalysis;

public interface LanguageProcessors extends LanguageServerProjectImporter, Iterable<LanguageSpec>, Closeable {
    Collection<String> extensions();
    IndexingSummary index(CommitAnalysis analysis) throws IOException;
    void identifyAffectedSymbols(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void collectAndCapture(IndexingSummary summary, CommitAnalysis analysis) throws IOException;

    void captureCopyPaste(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureComplexity(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
}
