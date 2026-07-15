package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.reporting.RuleViolation;

class CodiqoPmdRulesetTest {
    private static final String RULESET = "codiqo/pmd/java-codestyle.xml";
    private static final String METHOD_NAMING_RULE = "MethodNamingConventions";

    @TempDir
    Path tempDir;

    @Test
    void junitJupiterTestMethodWithUnderscoresIsNotFlagged() throws Exception {
        String source = """
                import org.junit.jupiter.api.Test;
                public class SampleTest {
                    @Test
                    void testPurchaseCreditPackRequestHandler_PersistsPackRevenue() { }
                    @Test
                    void should_return_null_when_absent_from_response() { }
                }
                """;
        assertEquals(0, methodNamingViolations(runPmd("SampleTest.java", source)));
    }
    @Test
    void junitJupiterTestMethodStartingUppercaseIsStillFlagged() throws Exception {
        String source = """
                import org.junit.jupiter.api.Test;
                public class SampleTest {
                    @Test
                    void TestPurchase() { }
                }
                """;
        assertEquals(1, methodNamingViolations(runPmd("SampleTest.java", source)));
    }
    @Test
    void productionMethodWithUnderscoresIsStillFlagged() throws Exception {
        String source = """
                public class Sample {
                    void persist_pack_revenue() { }
                }
                """;
        assertEquals(1, methodNamingViolations(runPmd("Sample.java", source)));
    }
    @Test
    void codestyleCategoryRulesAreRetained() {
        PMDConfiguration cfg = newConfiguration();
        try (PmdAnalysis pmd = PmdAnalysis.create(cfg)) {
            List<RuleSet> ruleSets = pmd.newRuleSetLoader().warnDeprecated(false).loadFromResources(List.of(RULESET));
            assertEquals(1, ruleSets.size());

            RuleSet ruleSet = ruleSets.iterator().next();
            assertTrue(ruleSet.getRules().stream().anyMatch(r -> "ClassNamingConventions".equals(r.getName())));
            assertTrue(ruleSet.getRules().stream().anyMatch(r -> METHOD_NAMING_RULE.equals(r.getName())));
        }
    }
    private List<RuleViolation> runPmd(String fileName, String source) throws Exception {
        Path sourceFile = tempDir.resolve(fileName);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

        PMDConfiguration cfg = newConfiguration();
        try (PmdAnalysis pmd = PmdAnalysis.create(cfg)) {
            pmd.addRuleSets(pmd.newRuleSetLoader().warnDeprecated(false).loadFromResources(List.of(RULESET)));
            pmd.files().addFile(sourceFile);
            return pmd.performAnalysisAndCollectReport().getViolations();
        }
    }
    private static long methodNamingViolations(List<RuleViolation> violations) {
        return violations.stream().filter(v -> METHOD_NAMING_RULE.equals(v.getRule().getName())).count();
    }
    private static PMDConfiguration newConfiguration() {
        JavaLanguageModule language = new JavaLanguageModule();

        PMDConfiguration toReturn = new PMDConfiguration(LanguageRegistry.singleton(language));
        toReturn.setDefaultLanguageVersion(language.getDefaultVersion());
        toReturn.setIgnoreIncrementalAnalysis(true);
        toReturn.setFailOnViolation(false);
        toReturn.setFailOnError(true);
        toReturn.setSourceEncoding(StandardCharsets.UTF_8);
        toReturn.setMinimumPriority(RulePriority.HIGH);
        toReturn.prependAuxClasspath(System.getProperty("java.class.path"));
        return toReturn;
    }
}
