package io.codiqo.api;

import java.io.IOException;

import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.util.Fetch;

/**
 * Everything codiqo registers for one language, in one place: what a commit is matched against before anything starts,
 * what the local review needs, what this machine must provide for the language's full analysis, and the analysis itself.
 * The registry lists every language once, and each place that needs a language reads it from there.
 */
public interface LanguageSupport {
    /** the language's wire value and file extensions, known without starting anything */
    SourceLanguage language();
    /** how the local review names the language's units and which of its tools' findings it triages */
    ReviewLanguage review();
    /**
     * Fails when this machine cannot run what the language's full analysis starts, such as a language server and the
     * runtime it needs. It runs before the commit is built and before a failed run can halt the project, so a machine
     * that cannot run the analysis fails the run and halts nothing.
     */
    void requireRuntime(RunArgs args) throws IOException;
    /** the language's analysis for one run: built cheaply, it starts nothing until the registry loads it */
    LanguageSpec analysis(LogFactory logFactory, RunArgs args, Fetch fetch);
}
