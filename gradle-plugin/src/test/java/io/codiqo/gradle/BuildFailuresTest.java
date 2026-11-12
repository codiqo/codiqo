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

        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(":app:compileJava", task(unresolved))).orElseThrow();

        assertTrue(Strings.CS.contains(failure.getDetail(), "missing-a"), failure.getDetail());
        assertTrue(Strings.CS.contains(failure.getDetail(), "missing-b"), "getCause() names only the first: " + failure.getDetail());
        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, failure.getCategory());
        assertEquals(Optional.empty(), failure.getCompilerCrash());
    }
    @Test
    void anAuthorsErrorBesideADownloadThatFailedStaysTheAuthors() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(ImmutableMap.of(
                ":app:compileJava", task(new IllegalStateException(COMPILE_ERROR)),
                ":docs:fetchTheme", task(new IllegalStateException("Could not GET 'https://repo.example/theme-1.0.zip'.", new IllegalStateException("Received status code 503 from server")))))
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory(), "under --continue an unrelated download failure must not hide the author's error");
    }
    @Test
    void downloadsThatFailedAloneAreTheEnvironments() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(ImmutableMap.of(
                ":app:compileJava", task(new IllegalStateException("Could not GET 'https://repo.example/lib-1.0.pom'.", new IllegalStateException("Received status code 503 from server"))),
                ":docs:fetchTheme", task(new IllegalStateException("Could not GET 'https://repo.example/theme-1.0.zip'.", new IllegalStateException("Received status code 503 from server")))))
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, failure.getCategory());
    }
    @Test
    void aHostThatCouldNotBeFoundIsReadFromItsExceptionsClass() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(":app:spotlessApply",
                task(new IllegalStateException("Failed to load the formatter from https://download.example/updates/p2.index", new UnknownHostException("download.example")))))
                .orElseThrow();

        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, failure.getCategory(), "the message is the host alone, its class says what failed");
        assertTrue(Strings.CS.contains(failure.getDetail(), "download.example"), failure.getDetail());
        assertFalse(Strings.CS.contains(failure.getDetail(), "java.net.UnknownHostException"), "the detail stays the messages: " + failure.getDetail());
    }
    @Test
    void aCrashBeyondTheDetailsLimitIsStillFound() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(ImmutableMap.of(
                ":app:compileJava", task(new IllegalStateException(COMPILE_ERROR + StringUtils.repeat('x', 10_000))),
                ":lib:compileJava", task(new IllegalStateException("Compilation failed; see the compiler output below.\n" + ERROR_PRONE_CRASH))))
                .orElseThrow();

        assertEquals(Optional.of(ERROR_PRONE_CRASH), failure.getCompilerCrash());
        assertTrue(failure.getDetail().length() <= 4096, "the detail is still cut: " + failure.getDetail().length());
    }
    @Test
    void aCompileErrorIsTheAuthors() {
        BuildFailures.BuildFailure failure = BuildFailures.classify(Map.of(":app:compileJava", task(new IllegalStateException(COMPILE_ERROR)))).orElseThrow();

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, failure.getCategory());
        assertEquals(Optional.empty(), failure.getCompilerCrash());
        assertTrue(failure.getDetail().startsWith(":app:compileJava: Execution failed for task ':app:compileJava'."), failure.getDetail());
    }
    @Test
    void noTaskFailedNoFailure() {
        assertEquals(Optional.empty(), BuildFailures.classify(Map.of()));
    }
    /** the task's own failure, whose cause is what went wrong */
    private static Throwable task(Throwable cause) {
        return new IllegalStateException("Execution failed for task ':app:compileJava'.", cause);
    }
}
