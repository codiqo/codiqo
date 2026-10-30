package io.codiqo.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.util.RuntimeRequirementException;

/**
 * Which Java a language server release starts on, measured by launching each archive on JDK 21: 1.61.0 started, while
 * 1.62.0 and 1.63.0 exited with code 13 because org.eclipse.core.resources requires JavaSE 25. Every check here reads
 * the configured version only, so none of them reaches download.eclipse.org or the download cache. A configured home is
 * asked for its settings by running its bin/java, which these tests replace with a script printing what a JDK prints.
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
    /** a build's qualifier is no release number: "1.63.0-SNAPSHOT" is 1.63.0, and a name without one is a new release */
    @Test
    void qualifiedAndUnnumberedVersionsAreReadByTheirReleaseNumber() {
        assertEquals(25, LanguageServerJdk.minimum("1.63.0-SNAPSHOT"));
        assertEquals(25, LanguageServerJdk.minimum("1.62.0.202610300819"));
        assertEquals(21, LanguageServerJdk.minimum("1.61.0-SNAPSHOT"));
        assertEquals(25, LanguageServerJdk.minimum("latest"));
    }
    /**
     * an empty jdtlsVersion property is checked as RunArgs downloads it, as a milestone that names no release, and
     * never as the mirrored default the run would not start
     */
    @Test
    void aBlankVersionIsCheckedAsTheRunWouldResolveIt() throws Exception {
        RunArgs args = args(home("21"));
        args.setJdtlsVersion(StringUtils.SPACE);

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args));

        assertTrue(Strings.CS.contains(err.getMessage(), "version ' ' needs Java 25"), err.getMessage());
    }
    @Test
    void theDefaultLanguageServerNeedsJava25() {
        assertEquals(25, LanguageServerJdk.minimum(RunArgs.JDTLS_MIRRORED_VERSION));
    }
    @Test
    void aJava21HomeIsRefusedForTheDefaultServerWithWhatToChange() throws Exception {
        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home("21"))));

        assertTrue(Strings.CS.contains(err.getMessage(), "needs Java 25"), err.getMessage());
        assertTrue(Strings.CS.contains(err.getMessage(), "codiqo.javaHome"), err.getMessage());
    }
    @Test
    void aJava25HomeIsAccepted() throws Exception {
        assertEquals(25, LanguageServerJdk.require(args(home("25"))).getFeature());
    }
    /** a pinned build missing from the cache would be looked up on download.eclipse.org if the version were resolved */
    @Test
    void aJava21HomeIsAcceptedForAServerBefore162() throws Exception {
        RunArgs args = args(home("21"));
        args.setJdtlsVersion("1.61.0");

        assertEquals(21, LanguageServerJdk.require(args).getFeature());
    }
    /** the snapshots channel builds from the newest line, so its version is not looked up to know it needs Java 25 */
    @Test
    void theSnapshotsChannelNeedsJava25WithoutLookingItsVersionUp() throws Exception {
        RunArgs args = args(home("21"));
        args.setJdtlsUseSnapshot(true);

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args));

        assertTrue(Strings.CS.contains(err.getMessage(), "snapshots channel"), err.getMessage());
    }
    /** a JDK before 9 reports its specification version as "1.8": it must still be refused with the reason */
    @Test
    void aJava8HomeIsRefusedWithTheVersionNotAParseError() throws Exception {
        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home("1.8"))));

        assertTrue(Strings.CS.contains(err.getMessage(), "would run on Java 8"), err.getMessage());
    }
    /**
     * Homebrew's opt/openjdk@25 only links bin/java into the JDK, and the home the server runs on and registers is the
     * one that JDK reports, never the keg root
     */
    @Test
    void theHomeIsTheOneTheJdkReports() throws Exception {
        Path jdk = home("25");
        Path keg = Files.createDirectories(tempDir.resolve("opt/openjdk@25/bin"));
        Files.createSymbolicLink(keg.resolve("java"), jdk.resolve("bin/java"));

        LanguageServerJdk.ServerJdk serverJdk = LanguageServerJdk.require(args(keg.getParent()));

        assertEquals(25, serverJdk.getFeature());
        assertEquals(jdk.toFile(), serverJdk.getHome());
    }
    /** the JDK running these tests, asked exactly as a configured codiqo.javaHome is */
    @Test
    void aRealJdkReportsItsOwnHomeAndRelease() throws Exception {
        RunArgs args = args(SystemUtils.getJavaHome().toPath());
        args.setJdtlsVersion("1.61.0");

        LanguageServerJdk.ServerJdk serverJdk = LanguageServerJdk.require(args);

        assertEquals(Runtime.version().feature(), serverJdk.getFeature());
        assertEquals(SystemUtils.getJavaHome().toPath().toRealPath(), serverJdk.getHome().toPath().toRealPath());
    }
    /**
     * the server checks its JDK again as it starts, after the review was paid for, where a refusal must halt the
     * project like any other failure; only the check before the build (DefaultLanguageProcessors.requireRuntime) halts
     * nothing
     */
    @Test
    void theServersOwnRefusalIsAnOrdinaryFailure() throws Exception {
        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home("21"))));

        assertFalse(err instanceof RuntimeRequirementException, err.toString());
    }
    /** a mistyped path, or a home that is no JDK, is not replaced by the JVM running codiqo, which may pass the check */
    @Test
    void aHomeWithoutJavaIsRefused() throws Exception {
        Path notAJdk = Files.createDirectories(tempDir.resolve("not-a-jdk"));

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(notAJdk)));

        assertTrue(Strings.CS.contains(err.getMessage(), "is not a JDK"), err.getMessage());
    }
    @Test
    void aJavaThatCannotStartIsRefusedWithWhatItPrinted() throws Exception {
        Path home = java(tempDir.resolve("broken"), """
                #!/bin/sh
                echo 'Error: could not find libjvm' >&2
                exit 1
                """);

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home)));

        assertTrue(Strings.CS.contains(err.getMessage(), "could not find libjvm"), err.getMessage());
    }
    @Test
    void aJavaThatReportsNoVersionIsRefused() throws Exception {
        Path home = java(tempDir.resolve("silent"), """
                #!/bin/sh
                exit 0
                """);

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home)));

        assertTrue(Strings.CS.contains(err.getMessage(), "is not a JDK"), err.getMessage());
    }
    /** a JDK home whose bin/java prints its settings to the error stream, as -XshowSettings:properties does */
    private Path home(String specificationVersion) throws IOException {
        Path toReturn = tempDir.resolve("jdk-" + specificationVersion);
        return java(toReturn, """
                #!/bin/sh
                echo 'Property settings:' >&2
                echo '    java.home = %s' >&2
                echo '    java.specification.version = %s' >&2
                echo 'openjdk version "%s"' >&2
                """.formatted(toReturn, specificationVersion, specificationVersion));
    }
    private static Path java(Path home, String script) throws IOException {
        Path java = Files.createDirectories(home.resolve("bin")).resolve("java");
        Files.writeString(java, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        return home;
    }
    private static RunArgs args(Path javaHome) {
        RunArgs toReturn = new RunArgs();
        toReturn.setJavaHome(javaHome.toFile());
        return toReturn;
    }
}
