package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Locale;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.junit.jupiter.api.Test;

import net.sourceforge.pmd.lang.rule.RulePriority;

class RunArgsCliTest {
    @Test
    void optionsParseWithNoArguments() throws Exception {
        // static constants must not surface as (required) CLI options
        CommandLine cmd = new DefaultParser().parse(RunArgs.options(), new String[0]);
        assertNotNull(cmd);
        assertFalse(cmd.hasOption("default-api-url"));
    }
    @Test
    void boxedNumericOptionsAreApplied() throws Exception {
        CommandLine cmd = new DefaultParser().parse(RunArgs.options(), new String[]{
                "--llm-validation-max-retries", "2",
                "--llm-max-retries", "5",
                "--llm-max-tokens", "1024",
                "--llm-temperature", "0.5"});

        RunArgs args = RunArgs.from(cmd);

        assertEquals((short) 2, args.getLlmValidationMaxRetries());
        assertEquals((short) 5, args.getLlmMaxRetries());
        assertEquals(1024, args.getLlmMaxTokens());
        assertEquals(0.5, args.getLlmTemperature(), 0.001);
    }
    @Test
    void perTestTimeoutDerivesHalfOfTestTimeoutWhenUnset() {
        RunArgs args = new RunArgs();
        args.validate();

        assertEquals(Duration.ofMinutes(45), args.getBuildTimeout());
        assertEquals(Duration.ofMinutes(30), args.getTestTimeout());
        assertEquals(Duration.ofMinutes(15), args.getPerTestTimeout());
    }
    /**
     * "medium-high" is the spelling every human writes and the one the CI action documents, but RulePriority's
     * constants are underscored — canonicalising in validate() keeps it from throwing deep inside PMD, after the
     * whole fork build has already been paid for.
     */
    @Test
    void pmdMinPriorityAcceptsHyphenatedAndSpacedSpellings() {
        RunArgs hyphenated = new RunArgs();
        hyphenated.setPmdMinPriority("medium-high");
        hyphenated.validate();

        RunArgs spaced = new RunArgs();
        spaced.setPmdMinPriority(" Medium Low ");
        spaced.validate();

        assertEquals("MEDIUM_HIGH", hyphenated.getPmdMinPriority());
        assertEquals("MEDIUM_LOW", spaced.getPmdMinPriority());
    }
    /**
     * PMD prints its own display name as "Medium High", so a value copied back out of a report has to round-trip.
     */
    @Test
    void pmdMinPriorityAcceptsPmdsOwnDisplayName() {
        RunArgs args = new RunArgs();
        args.setPmdMinPriority(RulePriority.MEDIUM_HIGH.getName());
        args.validate();

        assertEquals("MEDIUM_HIGH", args.getPmdMinPriority());
        assertEquals(RulePriority.MEDIUM_HIGH, args.pmdMinRulePriority());
    }
    /**
     * the previous implementation uppercased before matching, and under a Turkish locale "medium-high".toUpperCase()
     * yields "MEDİUM-HİGH" — no constant matches, so it threw on exactly the input it was written to accept.
     */
    @Test
    void pmdMinPriorityResolvesUnderALocaleWithNonAsciiUppercasing() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));

            RunArgs args = new RunArgs();
            args.setPmdMinPriority("medium-high");
            args.validate();

            assertEquals(RulePriority.MEDIUM_HIGH, args.pmdMinRulePriority());
        } finally {
            Locale.setDefault(original);
        }
    }
    @Test
    void pmdMinPriorityRejectsAnUnknownValue() {
        RunArgs args = new RunArgs();
        args.setPmdMinPriority("urgent");

        IllegalArgumentException err = assertThrows(IllegalArgumentException.class, args::validate);

        // the message has to name the offending value and the accepted set, or a CI misconfiguration is a guessing game
        assertTrue(err.getMessage().contains("urgent"));
        assertTrue(err.getMessage().contains("MEDIUM_HIGH"));
    }
    @Test
    void perTestTimeoutHonorsExplicitValue() throws Exception {
        CommandLine cmd = new DefaultParser().parse(RunArgs.options(), new String[]{"--per-test-timeout", "PT3M"});

        RunArgs args = RunArgs.from(cmd);

        assertEquals(Duration.ofMinutes(3), args.getPerTestTimeout());
    }
    @Test
    void perTestTimeoutZeroStaysDisabledAndIsNotDerived() throws Exception {
        CommandLine cmd = new DefaultParser().parse(RunArgs.options(), new String[]{"--per-test-timeout", "PT0S"});

        RunArgs args = RunArgs.from(cmd);

        assertEquals(Duration.ZERO, args.getPerTestTimeout());
    }
    @Test
    void perTestTimeoutDerivationIsHardCappedAt15Minutes() {
        RunArgs args = new RunArgs();
        args.setTestTimeout(Duration.ofHours(2));
        args.validate();

        assertEquals(RunArgs.PER_TEST_TIMEOUT_MAX, args.getPerTestTimeout());
        assertEquals(Duration.ofMinutes(15), args.getPerTestTimeout());
    }
    @Test
    void perTestTimeoutExplicitValueAboveCapIsClamped() throws Exception {
        CommandLine cmd = new DefaultParser().parse(RunArgs.options(), new String[]{"--per-test-timeout", "PT40M"});

        RunArgs args = RunArgs.from(cmd);

        assertEquals(Duration.ofMinutes(15), args.getPerTestTimeout());
    }
    @Test
    void jdtlsBaseUrlDefaultsToVersionedMilestones() {
        RunArgs args = new RunArgs();

        assertEquals("https://download.eclipse.org/jdtls/milestones/1.60.0", args.jdtlsBaseUrl().build().toString());
    }
    @Test
    void jdtlsBaseUrlUsesFlatSnapshotsWhenSnapshotsEnabled() {
        RunArgs args = new RunArgs();
        args.setJdtlsUseSnapshot(true);

        assertEquals("https://download.eclipse.org/jdtls/snapshots", args.jdtlsBaseUrl().build().toString());
    }
    @Test
    void effectiveJdtlsVersionParsesResolvedArchiveName() {
        RunArgs args = new RunArgs();
        args.setJdtlsArchiveName("jdt-language-server-1.59.0-202605111959.tar.gz");

        assertEquals("1.59.0", args.effectiveJdtlsVersion());
    }
    @Test
    void effectiveJdtlsVersionFallsBackToVersionArgWhenArchiveNameUnparsable() {
        RunArgs args = new RunArgs();
        args.setJdtlsArchiveName("unexpected-name.tar.gz");

        assertEquals("1.60.0", args.effectiveJdtlsVersion());
    }
    @Test
    void isExcludedAuthorIsFalseWhenListUnset() {
        assertFalse(new RunArgs().isExcludedAuthor("bob@example.com"));
    }
    @Test
    void isExcludedAuthorMatchesTrimmedCommaList() {
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails(" alice@example.com , bob@example.com ");

        assertTrue(args.isExcludedAuthor("bob@example.com"));
        assertFalse(args.isExcludedAuthor("carol@example.com"));
    }
    @Test
    void excludeWinsOverIncludeForSameAuthor() {
        RunArgs args = new RunArgs();
        args.setIncludeAuthorEmails("bob@example.com");
        args.setExcludeAuthorEmails("bob@example.com");

        assertTrue(args.matchesByAuthor("bob@example.com"));
        assertTrue(args.isExcludedAuthor("bob@example.com"));
    }
    @Test
    void isExcludedAuthorMatchesWildcardDomain() {
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails("jenkins@*");

        assertTrue(args.isExcludedAuthor("jenkins@ci-host-abc.internal"));
        assertTrue(args.isExcludedAuthor("jenkins@build.example.com"));
        assertFalse(args.isExcludedAuthor("alice@example.com"));
    }
    @Test
    void isExcludedAuthorIsCaseInsensitive() {
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails("Bob@Example.com");

        assertTrue(args.isExcludedAuthor("bob@example.com"));
    }
    @Test
    void isExcludedAuthorMixesWildcardAndExactEntries() {
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails("jenkins@*, ci@ci.example.org");

        assertTrue(args.isExcludedAuthor("jenkins@release-host.internal"));
        assertTrue(args.isExcludedAuthor("ci@ci.example.org"));
        assertFalse(args.isExcludedAuthor("bob@example.com"));
    }
    @Test
    void isAuthorAllowedTrueWhenNoFiltersSet() {
        assertTrue(new RunArgs().isAuthorAllowed("bob@example.com"));
    }
    @Test
    void isAuthorAllowedRespectsWhitelist() {
        RunArgs args = new RunArgs();
        args.setIncludeAuthorEmails("alice@example.com");

        assertTrue(args.isAuthorAllowed("alice@example.com"));
        assertFalse(args.isAuthorAllowed("bob@example.com"));
    }
    @Test
    void isAuthorAllowedBlacklistOverridesWhitelist() {
        RunArgs args = new RunArgs();
        args.setIncludeAuthorEmails("bob@example.com");
        args.setExcludeAuthorEmails("bob@example.com");

        assertFalse(args.isAuthorAllowed("bob@example.com"));
    }
    @Test
    void isAuthorAllowedRespectsWildcardBlacklist() {
        RunArgs args = new RunArgs();
        args.setExcludeAuthorEmails("jenkins@*");

        assertFalse(args.isAuthorAllowed("jenkins@some-agent.internal"));
        assertTrue(args.isAuthorAllowed("alice@example.com"));
    }
}
