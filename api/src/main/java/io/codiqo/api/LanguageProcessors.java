package io.codiqo.api;

import java.io.Closeable;

import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.diff.CommitAnalysis;

public interface LanguageProcessors extends LanguageServerProjectImporter, Iterable<LanguageSpec>, Closeable {
    IndexingSummary index(CommitAnalysis analysis);
    CopyPasteDetectionSummary detectCopyPaste(IndexingSummary summary, CommitAnalysis analysis);
    void captureComplexity(IndexingSummary summary, CommitAnalysis analysis);
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis);
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis);
}
