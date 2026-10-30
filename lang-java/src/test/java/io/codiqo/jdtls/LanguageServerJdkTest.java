package io.codiqo.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;

/**
 * Which Java a language server release starts on, measured by launching each archive on JDK 21: 1.61.0 started, while
 * 1.62.0 and 1.63.0 exited with code 13 because org.eclipse.core.resources requires JavaSE 25.
 */
class LanguageServerJdkTest {
    @TempDir
    Path tempDir;

    @Test
    void releasesBefore162StartOnJava21() {
        assertEquals(21, LanguageServerJdk.minimum("1.61.0"));
        assertEquals(21, LanguageServerJdk.minimum("1.57.0"));
        assertEquals(21, LanguageServerJdk.minimum("0.99.0"));
    }
    @Test
    void releasesFrom162OnNeedJava25() {
        assertEquals(25, LanguageServerJdk.minimum("1.62.0"));
        assertEquals(25, LanguageServerJdk.minimum("1.63.0"));
        assertEquals(25, LanguageServerJdk.minimum("1.100.0"));
        assertEquals(25, LanguageServerJdk.minimum("2.0.0"));
    }
    @Test
    void theDefaultLanguageServerNeedsJava25() {
        assertEquals(25, LanguageServerJdk.minimum(RunArgs.JDTLS_MIRRORED_VERSION));
    }
    @Test
    void aJava21HomeIsRefusedForTheDefaultServerWithWhatToChange() throws Exception {
        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home("\"21.0.5\""))));

        assertTrue(Strings.CS.contains(err.getMessage(), "needs Java 25"), err.getMessage());
        assertTrue(Strings.CS.contains(err.getMessage(), "codiqo.javaHome"), err.getMessage());
    }
    @Test
    void aJava25HomeIsAccepted() throws Exception {
        assertEquals(25, LanguageServerJdk.require(args(home("\"25.0.1\""))).feature());
    }
    @Test
    void aJava21HomeIsAcceptedForAServerBefore162() throws Exception {
        RunArgs args = args(home("\"21.0.5\""));
        args.setJdtlsVersion("1.61.0");

        assertEquals(21, LanguageServerJdk.require(args).feature());
    }
    /** written before Java 9 as "1.8.0_392", which Runtime.Version cannot parse: it must still be refused with the reason */
    @Test
    void aJava8HomeIsRefusedWithTheVersionNotAParseError() throws Exception {
        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home("\"1.8.0_392\""))));

        assertTrue(Strings.CS.contains(err.getMessage(), "would run on Java 8"), err.getMessage());
    }
    /** a mistyped path, or a home that is no JDK, is not replaced by the JVM running codiqo, which may pass the check */
    @Test
    void aHomeWithoutAReleaseFileIsRefused() throws Exception {
        Path notAJdk = Files.createDirectories(tempDir.resolve("not-a-jdk"));

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(notAJdk)));

        assertTrue(Strings.CS.contains(err.getMessage(), "is not a JDK"), err.getMessage());
    }
    @Test
    void aReleaseFileWithoutAJavaVersionIsRefused() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("no-version"));
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Example\"\n", StandardCharsets.UTF_8);

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home)));

        assertTrue(Strings.CS.contains(err.getMessage(), "names no JAVA_VERSION"), err.getMessage());
    }
    private Path home(String javaVersion) throws IOException {
        Path toReturn = Files.createDirectories(tempDir.resolve("jdk-" + javaVersion.replaceAll("[^0-9.]", "")));
        Files.writeString(toReturn.resolve("release"), "JAVA_VERSION=" + javaVersion + "\n", StandardCharsets.UTF_8);
        return toReturn;
    }
    private static RunArgs args(Path javaHome) {
        RunArgs toReturn = new RunArgs();
        toReturn.setJavaHome(javaHome.toFile());
        return toReturn;
    }
}
