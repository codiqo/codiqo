package io.codiqo.api;

import java.io.Closeable;
import java.util.Collection;

import io.codiqo.api.diff.CommitAnalysis;

public interface LanguageProcessors extends LanguageServerProjectImporter, Iterable<LanguageSpec>, Closeable {
    Collection<String> extensions();
    IndexingSummary index(CommitAnalysis analysis);
    void captureCopyPaste(IndexingSummary summary, CommitAnalysis analysis);
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis);
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis);
    void captureComplexity(IndexingSummary summary, CommitAnalysis analysis);

    default void collectAndCapture(IndexingSummary summary, CommitAnalysis analysis) {
        captureCopyPaste(summary, analysis);
        captureCoverage(summary, analysis);
        captureViolations(summary, analysis);
        captureComplexity(summary, analysis);
    }
}
