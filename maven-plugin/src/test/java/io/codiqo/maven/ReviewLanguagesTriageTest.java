package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.llm.StaticAnalysisLists;

class ReviewLanguagesTriageTest {
    /** a tool a language puts to the triage without lists in the static analysis review would never be asked about */
    @Test
    void everyTriagedToolHasListsInTheStaticAnalysisReview() {
        for (ReviewLanguage language : DefaultLanguageProcessors.reviewLanguages()) {
            for (String tool : language.triagedTools()) {
                assertTrue(StaticAnalysisLists.of(tool).isPresent(), language.getClass().getSimpleName() + " triages " + tool);
            }
        }
    }
}
