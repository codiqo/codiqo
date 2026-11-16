package io.codiqo.core.java;

import java.io.IOException;

import io.codiqo.api.LanguageSpec;
import io.codiqo.api.LanguageSupport;
import io.codiqo.api.RunArgs;
import io.codiqo.api.SourceLanguage;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.jdtls.LanguageServerJdk;
import io.codiqo.lang.config.FileKinds;
import io.codiqo.util.Fetch;

/** Java as codiqo registers it: PMD, SpotBugs, JaCoCo and the Eclipse JDT language server behind {@link JavaLanguageSpec}. */
public class JavaLanguageSupport implements LanguageSupport {
    @Override
    public SourceLanguage language() {
        return FileKinds.JAVA;
    }
    @Override
    public ReviewLanguage review() {
        return new JavaReviewLanguage();
    }
    /**
     * The JDK the full analysis forks the Eclipse JDT language server on (LanguageServerJdk). The language server checks
     * its JDK again as it starts, after the review, and there a refusal halts the project like any other failure, since
     * the next scheduled run would pay for the review again.
     */
    @Override
    public void requireRuntime(RunArgs args) throws IOException {
        LanguageServerJdk.require(args);
    }
    @Override
    public LanguageSpec analysis(LogFactory logFactory, RunArgs args, Fetch fetch) {
        return new JavaLanguageSpec(logFactory, args, fetch);
    }
}
