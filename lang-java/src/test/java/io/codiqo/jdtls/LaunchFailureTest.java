package io.codiqo.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** what exit code 13 means, read from the log the OSGi framework writes when the Eclipse launcher cannot start */
class LaunchFailureTest {
    /** written by jdt.ls 1.62.0 started on Java 21, shortened */
    private static final String LOG = """
            !SESSION 2026-10-30 08:19:54.162 -----------------------------------------------
            eclipse.buildId=unknown
            java.version=21.0.13
            !ENTRY org.eclipse.osgi 4 0 2026-10-30 08:19:54.598
            !MESSAGE FrameworkEvent ERROR
            !STACK 0
            org.osgi.framework.BundleException: Could not resolve module: org.eclipse.core.resources [31]
              Unresolved requirement: Require-Capability: osgi.ee; filter:="(&(osgi.ee=JavaSE)(version=25))"
            \tat org.eclipse.osgi.container.Module.start(Module.java:463)
            !ENTRY org.eclipse.osgi 4 0 2026-10-30 08:19:54.601
            !MESSAGE FrameworkEvent ERROR
            org.osgi.framework.BundleException: Could not resolve module: org.eclipse.core.filesystem [28]
              Unresolved requirement: Require-Capability: osgi.ee; filter:="(&(osgi.ee=JavaSE)(version=25))"
            """;

    @TempDir
    Path tempDir;

    @Test
    void theFirstUnresolvedBundleAndWhatItNeededAreReported() throws Exception {
        Files.writeString(tempDir.resolve("1793347194162.log"), LOG, StandardCharsets.UTF_8);

        String cause = JdtLspProcess.launchFailure(tempDir).orElseThrow();

        assertTrue(Strings.CS.contains(cause, "Could not resolve module: org.eclipse.core.resources"), cause);
        assertTrue(Strings.CS.contains(cause, "version=25"), cause);
    }
    /** a bundle that requires an unresolved one is logged first, while the Java version is what the server lacks */
    @Test
    void theJavaVersionRequirementIsReportedBeforeABundleThatOnlyFollowsFromIt() throws Exception {
        Files.writeString(tempDir.resolve("1793347194162.log"), """
                !ENTRY org.eclipse.osgi 4 0 2026-10-30 08:19:54.598
                org.osgi.framework.BundleException: Could not resolve module: org.eclipse.jdt.ls.core [52]
                  Unresolved requirement: Require-Bundle: org.eclipse.core.resources
                !ENTRY org.eclipse.osgi 4 0 2026-10-30 08:19:54.601
                org.osgi.framework.BundleException: Could not resolve module: org.eclipse.core.resources [31]
                  Unresolved requirement: Require-Bundle: org.eclipse.core.expressions
                  Unresolved requirement: Require-Capability: osgi.ee; filter:="(&(osgi.ee=JavaSE)(version=25))"
                """, StandardCharsets.UTF_8);

        assertEquals(
                "org.osgi.framework.BundleException: Could not resolve module: org.eclipse.core.resources [31]; "
                        + "Unresolved requirement: Require-Capability: osgi.ee; filter:=\"(&(osgi.ee=JavaSE)(version=25))\"",
                JdtLspProcess.launchFailure(tempDir).orElseThrow());
    }
    @Test
    void noLogMeansNoReason() throws Exception {
        assertEquals(Optional.empty(), JdtLspProcess.launchFailure(tempDir));
    }
}
