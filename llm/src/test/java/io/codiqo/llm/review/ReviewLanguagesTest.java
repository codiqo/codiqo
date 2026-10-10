package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.api.review.UnitName;

class ReviewLanguagesTest {
    private static final ReviewLanguage FIRST = language("first", "Rule of the first language.");
    private static final ReviewLanguage SECOND = language("second", "Rule of the second language.");
    private static final List<ReviewLanguage> LANGUAGES = List.of(FIRST, SECOND);

    /** the rules come in registration order, once each, whatever order the files are in */
    @Test
    void theRulesAreThoseOfTheLanguagesTheCommitChanges() {
        assertEquals(List.of(FIRST.namingRule(), SECOND.namingRule()), ReviewLanguages.namingRules(List.of("b.second", "a.first", "c.first"), LANGUAGES));
        assertEquals(List.of(SECOND.namingRule()), ReviewLanguages.namingRules(List.of("b.second"), LANGUAGES));
    }
    /** a file of no registered language matches by the exact name, which the prompt's own wording already asks for */
    @Test
    void aFileOfNoRegisteredLanguageAddsNoRule() {
        assertEquals(List.of(FIRST.namingRule()), ReviewLanguages.namingRules(List.of("a.first", "web/totals.ts"), LANGUAGES));
        assertEquals(List.of(), ReviewLanguages.namingRules(List.of("web/totals.ts"), LANGUAGES));
    }
    /** a commit whose files could not be listed (a treeless clone, a shallow boundary) is given every rule */
    @Test
    void unknownFilesGetEveryRule() {
        assertEquals(List.of(FIRST.namingRule(), SECOND.namingRule()), ReviewLanguages.namingRules(List.of(), LANGUAGES));
    }
    private static ReviewLanguage language(String extension, String rule) {
        return new ReviewLanguage() {
            @Override
            public Collection<String> extensions() {
                return List.of(extension);
            }
            @Override
            public String namingRule() {
                return rule;
            }
            @Override
            public UnitName labelled(String signature, String path) {
                return new UnitName(List.of(), signature);
            }
            @Override
            public UnitName unit(String name, String signature, String path) {
                return new UnitName(List.of(), name);
            }
            @Override
            public Set<String> triagedTools() {
                return Set.of();
            }
        };
    }
}
