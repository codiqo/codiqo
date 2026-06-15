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
}
