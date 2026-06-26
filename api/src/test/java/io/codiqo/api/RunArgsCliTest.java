package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void jdtlsBaseUrlDefaultsToFlatSnapshots() {
        RunArgs args = new RunArgs();

        assertEquals("https://download.eclipse.org/jdtls/snapshots", args.jdtlsBaseUrl().build().toString());
    }
    @Test
    void jdtlsBaseUrlUsesVersionedMilestonesWhenSnapshotsDisabled() {
        RunArgs args = new RunArgs();
        args.setJdtlsUseSnapshot(false);

        assertEquals("https://download.eclipse.org/jdtls/milestones/1.58.0", args.jdtlsBaseUrl().build().toString());
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

        assertEquals("1.58.0", args.effectiveJdtlsVersion());
    }
}
