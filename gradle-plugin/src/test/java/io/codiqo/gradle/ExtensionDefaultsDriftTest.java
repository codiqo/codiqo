package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;

/**
 * Guards against the Gradle extension defaults silently diverging from the RunArgs field defaults, and from the
 * equivalent mojo @Parameter defaults the Maven side declares. The extension value always overwrites RunArgs, so a
 * divergence makes the same commit behave differently depending on the build tool it was analyzed with. Mirrors
 * MojoDefaultsDriftTest on the Maven side.
 */
class ExtensionDefaultsDriftTest {
    @Test
    void extensionDefaultsMatchRunArgsDefaults() {
        CodiqoExtension ext = new CodiqoExtension();
        RunArgs reference = new RunArgs();

        assertEquals(reference.getTestTimeout().toMinutes(), ext.getTestTimeoutMinutes());
        assertEquals(reference.getImportTimeout().toMinutes(), ext.getImportTimeoutMinutes());
        assertEquals(reference.getLspQueryTimeout().getSeconds(), ext.getLspQueryTimeoutSeconds());

        assertEquals(reference.getJdtlsVersion(), ext.getJdtlsVersion());
        assertEquals(reference.isJdtlsUseSnapshot(), ext.isJdtlsUseSnapshot());
        assertEquals(reference.isJdtUseSharedIndex(), ext.isJdtUseSharedIndex());
        assertEquals(reference.isJdtIncludeDecompiledSources(), ext.isJdtIncludeDecompiledSources());
    }
}
