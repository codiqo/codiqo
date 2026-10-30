package io.codiqo.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.util.RuntimeRequirementException;

/**
 * Which Java a language server release starts on, measured by launching each archive on JDK 21: 1.61.0 started, while
 * 1.62.0 and 1.63.0 exited with code 13 because org.eclipse.core.resources requires JavaSE 25. Every check here reads
 * the configured version only, so none of them reaches download.eclipse.org or the download cache.
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
        RunArgs args = args(home("21.0.5"));
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
        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home("21.0.5"))));

        assertTrue(Strings.CS.contains(err.getMessage(), "needs Java 25"), err.getMessage());
        assertTrue(Strings.CS.contains(err.getMessage(), "codiqo.javaHome"), err.getMessage());
    }
    @Test
    void aJava25HomeIsAccepted() throws Exception {
        assertEquals(25, LanguageServerJdk.require(args(home("25.0.1"))).getFeature());
    }
    /** a pinned build missing from the cache would be looked up on download.eclipse.org if the version were resolved */
    @Test
    void aJava21HomeIsAcceptedForAServerBefore162() throws Exception {
        RunArgs args = args(home("21.0.5"));
        args.setJdtlsVersion("1.61.0");

        assertEquals(21, LanguageServerJdk.require(args).getFeature());
    }
    /** the snapshots channel builds from the newest line, so its version is not looked up to know it needs Java 25 */
    @Test
    void theSnapshotsChannelNeedsJava25WithoutLookingItsVersionUp() throws Exception {
        RunArgs args = args(home("21.0.5"));
        args.setJdtlsUseSnapshot(true);

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args));

        assertTrue(Strings.CS.contains(err.getMessage(), "snapshots channel"), err.getMessage());
    }
    /** written before Java 9 as "1.8.0_392", which Runtime.Version cannot parse: it must still be refused with the reason */
    @Test
    void aJava8HomeIsRefusedWithTheVersionNotAParseError() throws Exception {
        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home("1.8.0_392"))));

        assertTrue(Strings.CS.contains(err.getMessage(), "would run on Java 8"), err.getMessage());
    }
    /** Homebrew's opt/openjdk@25 has no release file of its own; its bin/java links into the JDK that has one */
    @Test
    void aHomeWhoseJavaLinksIntoAJdkIsReadFromThatJdk() throws Exception {
        Path jdk = home("25.0.1");
        Path java = Files.createDirectories(jdk.resolve("bin")).resolve("java");
        Files.writeString(java, StringUtils.EMPTY, StandardCharsets.UTF_8);
        Path linked = Files.createDirectories(tempDir.resolve("opt/openjdk@25/bin"));
        Files.createSymbolicLink(linked.resolve("java"), java);

        LanguageServerJdk.ServerJdk serverJdk = LanguageServerJdk.require(args(linked.getParent()));

        assertEquals(25, serverJdk.getFeature());
        assertEquals(jdk.toRealPath(), serverJdk.getHome().toPath().toRealPath());
    }
    /**
     * the server checks its JDK again as it starts, after the review was paid for, where a refusal must halt the
     * project like any other failure; only the check before the build (DefaultLanguageProcessors.requireRuntime) halts
     * nothing
     */
    @Test
    void theServersOwnRefusalIsAnOrdinaryFailure() throws Exception {
        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home("21.0.5"))));

        assertFalse(err instanceof RuntimeRequirementException, err.toString());
    }
    /** Runtime.Version refuses trailing zero parts, so only the feature release is read */
    @Test
    void aReleaseFileWithTrailingZerosIsReadByItsFeatureRelease() throws Exception {
        assertEquals(25, LanguageServerJdk.require(args(home("25.0.0"))).getFeature());
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
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Example\"" + StringUtils.LF, StandardCharsets.UTF_8);

        IOException err = assertThrows(IOException.class, () -> LanguageServerJdk.require(args(home)));

        assertTrue(Strings.CS.contains(err.getMessage(), "names no JAVA_VERSION"), err.getMessage());
    }
    /** a JDK home whose release file names the version, quoted as JDKs write it */
    private Path home(String javaVersion) throws IOException {
        Path toReturn = Files.createDirectories(tempDir.resolve("jdk-" + javaVersion));
        Files.writeString(toReturn.resolve("release"), "JAVA_VERSION=\"" + javaVersion + "\"" + StringUtils.LF, StandardCharsets.UTF_8);
        return toReturn;
    }
    private static RunArgs args(Path javaHome) {
        RunArgs toReturn = new RunArgs();
        toReturn.setJavaHome(javaHome.toFile());
        return toReturn;
    }
}
