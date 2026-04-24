package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class EnvValuesTest {
    @Test
    void nullInputReturnsEmpty() {
        assertFalse(Env.resolve(null).isPresent());
    }
    @Test
    void blankInputReturnsEmpty() {
        assertFalse(Env.resolve("").isPresent());
    }
    @Test
    void inlineValueIsReturnedAsIs() {
        Optional<String> resolved = Env.resolve("literal-secret");

        assertTrue(resolved.isPresent());
        assertEquals("literal-secret", resolved.get());
    }
    @Test
    void envPrefixResolvesKnownVariable() {
        String varName = uniqueEnvVar();
        String expected = System.getenv(varName);

        Optional<String> resolved = Env.resolve("env:" + varName);

        if (expected == null || expected.isEmpty()) {
            assertFalse(resolved.isPresent(),
                    "chosen env var " + varName + " must have a non-empty value for this assertion to apply");
        } else {
            assertTrue(resolved.isPresent());
            assertEquals(expected, resolved.get());
        }
    }
    @Test
    void envPrefixWithMissingVariableReturnsEmpty() {
        assertFalse(Env.resolve("env:CODIQO_DEFINITELY_UNSET_" + System.nanoTime()).isPresent());
    }
    @Test
    void valueStartingWithEnvColonButWithNoEnvVarReturnsEmpty() {
        assertFalse(Env.resolve("env:").isPresent(),
                "raw value of 'env:' asks for the empty-named env var, which cannot exist");
    }
    @Test
    void resolveRequiredReturnsInlineValue() {
        assertEquals("literal-secret", Env.resolveRequired("literal-secret", "codiqo.apiKey"));
    }
    @Test
    void resolveRequiredThrowsForEmptyWithMessageContainingParamName() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> Env.resolveRequired("", "codiqo.apiKey"));

        assertTrue(err.getMessage().contains("codiqo.apiKey"),
                "error message must name the offending parameter to aid operator debugging");
    }
    @Test
    void resolveRequiredThrowsForMissingEnvVar() {
        assertThrows(IllegalArgumentException.class,
                () -> Env.resolveRequired("env:CODIQO_DEFINITELY_UNSET_" + System.nanoTime(), "codiqo.apiKey"));
    }
    @Test
    void resolveIntoAppliesSetterWhenValuePresent() {
        AtomicReference<String> captured = new AtomicReference<>();

        Env.resolveInto("my-value", captured::set);

        assertEquals("my-value", captured.get());
    }
    @Test
    void resolveIntoSkipsSetterWhenInputEmpty() {
        AtomicReference<String> captured = new AtomicReference<>();

        Env.resolveInto(null, captured::set);

        assertNull(captured.get(), "setter must not fire when there is no value to propagate");
    }
    private static String uniqueEnvVar() {
        return System.getenv().keySet().stream()
                .filter(name -> !name.contains("$"))
                .filter(name -> !System.getenv(name).isEmpty())
                .findFirst()
                .orElse("PATH");
    }
}
