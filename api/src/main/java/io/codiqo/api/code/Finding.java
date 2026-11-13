package io.codiqo.api.code;

import io.codiqo.client.model.DiagnosticModel;

/**
 * A static-analysis tool's finding on a code unit, the same for every language and tool: each language adapts its
 * tools' own results to it (the Java language adapts PMD's violations and SpotBugs' bugs), and the submission, the
 * module's quality counts and the hotspot report read only this.
 *
 * <p>A finding a tool reports once is attached to every unit whose range holds it, so a nested unit shares it with
 * the units around it. Two findings are equal exactly when the tool results they adapt are, which is how the hotspot
 * report keeps one of them, on the innermost unit.
 */
public interface Finding {
    /** the tool's id, as the submission's diagnostics name the tool ({@code pmd}, {@code spotbugs}) */
    String getTool();
    String getRuleId();
    String getMessage();
    String getCategory();
    Severity getSeverity();
    /**
     * Where the tool reported it. Columns count from one, and both are {@link SourceLocation#NO_COLUMN} when the tool
     * reports lines only (SpotBugs reads bytecode, which keeps line numbers alone).
     */
    SourceLocation getLocation();
    /** the finding's place among its own tool's findings, most severe first: the hotspot report lists them in this order */
    int getRank();
    /**
     * Adds what only this tool reports to the finding's diagnostic in the submission (PMD's rule set and priority,
     * SpotBugs' rank, confidence and CWE), after the submission has written what every finding has.
     */
    void describe(DiagnosticModel diagnostic);

    /** how severe a finding is, which the submission writes as the diagnostic's severity */
    enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}
