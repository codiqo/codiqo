package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;

/**
 * Which Java a language server release starts on, measured by launching each archive on JDK 21: 1.61.0 started, while
 * 1.62.0 and 1.63.0 exited with code 13 because org.eclipse.core.resources requires JavaSE 25.
 */
class LanguageServerJdkTest {
    @Test
    void releasesBefore162StartOnJava21() {
        assertEquals(21, LanguageServerJdk.minimum("1.61.0"));
        assertEquals(21, LanguageServerJdk.minimum("1.57.0"));
    }
    @Test
    void releasesFrom162OnNeedJava25() {
        assertEquals(25, LanguageServerJdk.minimum("1.62.0"));
        assertEquals(25, LanguageServerJdk.minimum("1.63.0"));
        assertEquals(25, LanguageServerJdk.minimum("1.70.0"));
    }
    /** the version codiqo downloads by default is the one the check and the README's requirement are about */
    @Test
    void theDefaultLanguageServerNeedsJava25() {
        assertEquals(25, LanguageServerJdk.minimum(RunArgs.JDTLS_MIRRORED_VERSION));
    }
}
