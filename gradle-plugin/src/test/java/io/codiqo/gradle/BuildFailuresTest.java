package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.gradle.api.artifacts.ResolveException;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import io.codiqo.client.model.AnalysisExcludeCategory;

/** failures shaped as Gradle 9.9's failed tasks threw them: the task's own message, then the causes beneath it */
class BuildFailuresTest {
    private static final String COMPILE_ERROR = "Compilation failed; see the compiler output below.\n/work/src/main/java/app/App.java:5: error: cannot find symbol\n1 error";
    private static final String ERROR_PRONE_CRASH = "/work/src/main/java/app/App.java:4: error: An unhandled exception was thrown by the Error Prone static analysis plugin.";

    @Test
    void everyDependencyGradleCouldNotResolveReachesTheDetail() {
        ResolveException unresolved = new ResolveException("Could not resolve all files for configuration ':app:compileClasspath'.", List.of(
                new IllegalStateException("Could not find com.example:missing-a:1.0.\nSearched in the following locations:\n  - https://repo.example/com/example/missing-a/1.0/missing-a-1.0.pom"),
                new IllegalStateException("Could not find com.example:missing-b:1.0.\nSearched in the following locations:\n  - https://repo.example/com/example/missing-b/1.0/missing-b-1.0.pom")));

        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(":app:compileJava", task(unresolved)), Map.of()).orElseThrow();

        assertTrue(Strings.CS.contains(failure.getDetail(), "missing-a"), failure.getDetail());
        assertTrue(Strings.CS.contains(failure.getDetail(), "missing-b"), "getCause() names only the first: " + failure.getDetail());
        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, failure.getCategory());
        assertEquals(Optional.empty(), failure.getCompilerCrash());
    }
    @Test
    void anAuthorsErrorBesideADownloadThatFailedStaysTheAuthors() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(ImmutableMap.of(
                ":app:compileJava", task(new IllegalStateException(COMPILE_ERROR)),
                ":docs:fetchTheme", task(new IllegalStateException("Could not GET 'https://repo.example/theme-1.0.zip'.", new IllegalStateException("Received status code 503 from server")))), Map.of())
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory(), "under --continue an unrelated download failure must not hide the author's error");
    }
    @Test
    void downloadsThatFailedAloneAreTheEnvironments() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(ImmutableMap.of(
                ":app:compileJava", task(new IllegalStateException("Could not GET 'https://repo.example/lib-1.0.pom'.", new IllegalStateException("Received status code 503 from server"))),
                ":docs:fetchTheme", task(new IllegalStateException("Could not GET 'https://repo.example/theme-1.0.zip'.", new IllegalStateException("Received status code 503 from server")))), Map.of())
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, failure.getCategory());
    }
    @Test
    void aModuleGradleReportsFromItsCacheOfMissingOnesIsTheEnvironments() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(
                Map.of(":compileTestJava", task(new ResolveException("Could not resolve all files for configuration ':testCompileClasspath'.", List.of(
                        new ModuleVersionNotFoundException("Could not find com.example:missing-test:1.0.\nRequired by:\n    root project 'app'"))))),
                Map.of(":testRuntimeClasspath", new ResolveException("Could not resolve all files for configuration ':testRuntimeClasspath'.", List.of(
                        new ModuleVersionNotFoundException("Could not find com.example:missing-test:1.0.\nSearched in the following locations:\n  - https://repo.example/com/example/missing-test/1.0/missing-test-1.0.pom")))))
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, failure.getCategory(), "the task's report repeats a module the build searched remotely, naming no repository");
        assertTrue(failure.getDetail().startsWith(":compileTestJava:"), "the failed task comes before what the collector reported: " + failure.getDetail());
    }
    @Test
    void anAuthorsResolutionErrorBesideADownloadThatFailedStaysTheAuthors() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(ImmutableMap.of(
                ":app:compileJava", task(new ResolveException("Could not resolve all files for configuration ':app:compileClasspath'.", List.of(
                        new IllegalStateException("Dependency verification failed for configuration ':app:compileClasspath'")))),
                ":docs:fetchTheme", task(new IllegalStateException("Could not GET 'https://repo.example/theme-1.0.zip'.", new IllegalStateException("Received status code 503 from server")))), Map.of())
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory(), "a verification metadata the author did not update is the author's");
    }
    @Test
    void aLocalProjectGradleCouldNotMatchStaysTheAuthors() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(":app:compileJava",
                task(new ResolveException("Could not resolve all files for configuration ':app:compileClasspath'.", List.of(new IllegalStateException(
                        "Could not resolve project :lib.\nNo matching variant of project :lib was found."))))), Map.of())
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory());
    }
    @Test
    void aMissingModuleDoesNotHideAVariantTheAuthorGotWrongInTheSameConfiguration() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(), Map.of(":app:compileClasspath",
                new ResolveException("Could not resolve all files for configuration ':app:compileClasspath'.", List.of(
                        new ModuleVersionNotFoundException("Could not find com.example:missing:1.0.\nSearched in the following locations:\n  - https://repo.example/com/example/missing/1.0/missing-1.0.pom"),
                        new IllegalStateException("Could not resolve project :lib.\nNo matching variant of project :lib was found.")))))
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory(), "each cause of a resolve failure is classified on its own");
    }
    @Test
    void aJarMissingFromTheAuthorsOwnRepositoryIsTheAuthors() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(), Map.of(":compileClasspath",
                new ResolveException("Could not resolve all files for configuration ':compileClasspath'.", List.of(
                        new ModuleVersionNotFoundException("Could not find :vendored:1.0.\nRequired by:\n    root project 'app'")))))
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory(), "a flatDir repository names no URL: nothing was searched remotely");
    }
    @Test
    void aBundleInsideABundleIsSplitToo() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(), Map.of(":app:compileClasspath",
                new ResolveException("Could not resolve all files for configuration ':app:compileClasspath'.", List.of(
                        new ModuleVersionNotFoundException("Could not find com.example:missing:1.0.\nSearched in the following locations:\n  - https://repo.example/com/example/missing/1.0/missing-1.0.pom"),
                        new ResolveException("Could not resolve com.example:transformed:1.0.", List.of(
                                new IllegalStateException("Could not GET 'https://repo.example/x.pom'. Received status code 503 from server"),
                                new IllegalStateException("Could not resolve project :lib.\nNo matching variant of project :lib was found.")))))))
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory(), "a failed fetch in a nested bundle must not hide the author's variant beside it");
    }
    @Test
    void aProjectThatDefinesNoRepositoryIsTheAuthors() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(), Map.of(":app:compileClasspath",
                new ResolveException("Could not resolve all files for configuration ':app:compileClasspath'.", List.of(
                        new ModuleVersionNotFoundException("Cannot resolve external dependency com.example:lib:1.0 because no repositories are defined.")))))
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory(), "Gradle throws its not-found exception for a build that declares no repository too");
    }
    @Test
    void aClassOfTheAuthorsNamedLikeGradlesIsNoDownloadThatFailed() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(":app:compileJava", task(new IllegalStateException(
                "Compilation failed; see the compiler output below.\n/work/src/main/java/app/App.java:5: error: cannot find symbol\n  symbol:   class ArtifactNotFoundException"))), Map.of())
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory(), "the not-found rule reads the failure's own class, never its text");
    }
    @Test
    void aHostThatCouldNotBeFoundIsReadFromItsExceptionsClass() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(":app:spotlessApply",
                task(new IllegalStateException("Failed to load the formatter from https://download.example/updates/p2.index", new UnknownHostException("download.example")))), Map.of())
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, failure.getCategory(), "the message is the host alone, its class says what failed");
        assertTrue(Strings.CS.contains(failure.getDetail(), "download.example"), failure.getDetail());
        assertFalse(Strings.CS.contains(failure.getDetail(), "java.net.UnknownHostException"), "the detail stays the messages: " + failure.getDetail());
    }
    @Test
    void aCrashBeyondTheDetailsLimitIsStillFound() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(ImmutableMap.of(
                ":app:compileJava", task(new IllegalStateException(COMPILE_ERROR + StringUtils.repeat('x', 10_000))),
                ":lib:compileJava", task(new IllegalStateException("Compilation failed; see the compiler output below.\n" + ERROR_PRONE_CRASH))), Map.of())
                .orElseThrow();

        assertEquals(Optional.of(ERROR_PRONE_CRASH), failure.getCompilerCrash());
        assertTrue(failure.getDetail().length() <= 4096, "the detail is still cut: " + failure.getDetail().length());
    }
    @Test
    void aCompileErrorIsTheAuthors() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(":app:compileJava", task(new IllegalStateException(COMPILE_ERROR))), Map.of()).orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory());
        assertEquals(Optional.empty(), failure.getCompilerCrash());
        assertTrue(failure.getDetail().startsWith(":app:compileJava: Execution failed for task ':app:compileJava'."), failure.getDetail());
    }
    @Test
    void noTaskFailedNoFailure() {
        assertEquals(Optional.empty(), BuildFailures.classify(Map.of(), Map.of()));
    }
    /** the task's own failure, whose cause is what went wrong */
    private static Throwable task(Throwable cause) {
        return new IllegalStateException("Execution failed for task ':app:compileJava'.", cause);
    }

    /** stands for Gradle's internal exception of that name: the not-found rule reads a failure's class by its name */
    private static final class ModuleVersionNotFoundException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ModuleVersionNotFoundException(String message) {
            super(message);
        }
    }
}
