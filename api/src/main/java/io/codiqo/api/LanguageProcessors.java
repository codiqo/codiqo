package io.codiqo.api;

import java.io.Closeable;

import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.pmd.CopyPasteDetectionSummary;

public interface LanguageProcessors extends LanguageServerProjectImporter, Iterable<LanguageSpec>, Closeable {
    IndexingSummary index(CommitAnalysis analysis);
    CopyPasteDetectionSummary detectCopyPaste(IndexingSummary summary, CommitAnalysis analyze);
    void captureComplexity(IndexingSummary summary, CommitAnalysis analyze);
    void captureViolations(IndexingSummary summary, CommitAnalysis analyze);
}
