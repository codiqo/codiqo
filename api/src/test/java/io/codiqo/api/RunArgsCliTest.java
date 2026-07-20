package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.junit.jupiter.api.Test;

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

        assertEquals(Duration.ofMinutes(60), args.getBuildTimeout());
        assertEquals(Duration.ofMinutes(30), args.getTestTimeout());
        assertEquals(Duration.ofMinutes(15), args.getPerTestTimeout());
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

        assertTrue(args.isExcludedAuthor("jenkins@jenkins-slave-medium-abc.europe-west2-a.c.dev-lotto2.internal"));
        assertTrue(args.isExcludedAuthor("jenkins@b2spin.com"));
        assertFalse(args.isExcludedAuthor("alice@patrianna.com"));
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
        args.setExcludeAuthorEmails("jenkins@*, ci@jenkins.quizbeat.net");

        assertTrue(args.isExcludedAuthor("jenkins@patrianna-dev-release-xq0xgz.internal"));
        assertTrue(args.isExcludedAuthor("ci@jenkins.quizbeat.net"));
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
