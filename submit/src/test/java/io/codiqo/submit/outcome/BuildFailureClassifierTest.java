package io.codiqo.submit.outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.codiqo.client.model.AnalysisExcludeCategory;

/**
 * Failure texts as Maven's fork printed them and as Gradle 9.9's failed tasks threw them (task message, then each cause),
 * and whose failure each is.
 */
class BuildFailureClassifierTest {
    private static final String SPOTLESS_OUTAGE = """
            [ERROR] Failed to execute goal com.diffplug.spotless:spotless-maven-plugin:2.44.4:apply (spotless-apply) on project app: \
            Execution spotless-apply of goal com.diffplug.spotless:spotless-maven-plugin:2.44.4:apply failed: java.io.IOException: \
            Failed to load eclipse jdt formatter: java.lang.UnsupportedOperationException: We could not parse the content at \
            https://download.eclipse.org/eclipse/updates/4.35/p2.index:
            <!DOCTYPE html>
            <html lang="en">
            <head><title>Temporary shutdown.</title></head>
            """;
    private static final String GRADLE_POM_NOT_FOUND = """
            :app:compileJava: Execution failed for task ':app:compileJava'.
            Could not resolve all files for configuration ':app:compileClasspath'.
            Could not find com.example:lib:1.0.
            Searched in the following locations:
              - https://repo.example/com/example/lib/1.0/lib-1.0.pom
            Required by:
                project ':app'
            """;
    private static final String GRADLE_SERVICE_UNAVAILABLE = """
            :app:compileJava: Execution failed for task ':app:compileJava'.
            Could not resolve all files for configuration ':app:compileClasspath'.
            Could not resolve com.example:lib:1.0.
            Could not get resource 'https://repo.example/com/example/lib/1.0/lib-1.0.pom'.
            Could not GET 'https://repo.example/com/example/lib/1.0/lib-1.0.pom'.
            Received status code 503 from server: Service Unavailable
            """;
    private static final String GRADLE_HTML_POM = """
            :app:compileJava: Execution failed for task ':app:compileJava'.
            Could not resolve all files for configuration ':app:compileClasspath'.
            Could not resolve com.example:lib:1.0.
            Could not parse POM https://repo.example/com/example/lib/1.0/lib-1.0.pom
            Already seen doctype.
            """;
    private static final String GRADLE_CONNECTION_REFUSED = """
            :app:compileJava: Execution failed for task ':app:compileJava'.
            Could not resolve all files for configuration ':app:compileClasspath'.
            Could not resolve com.example:lib:1.0.
            Could not GET 'http://127.0.0.1:8081/com/example/lib/1.0/lib-1.0.pom'.
            Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused (connect failed)
            """;
    private static final String GRADLE_LOCAL_VARIANT_MISMATCH = """
            :app:compileJava: Execution failed for task ':app:compileJava'.
            Could not resolve all files for configuration ':app:compileClasspath'.
            Could not resolve project :lib.
            No matching variant of project :lib was found.
            """;
    private static final String GRADLE_COMPILE_ERROR = """
            :app:compileJava: Execution failed for task ':app:compileJava'.
            Compilation failed; see the compiler output below.
            /work/src/main/java/app/App.java:5: error: incompatible types: int cannot be converted to String
                    String s = 42;
                               ^
            1 error
            """;
    private static final String GRADLE_ERROR_PRONE_CRASH = """
            :app:compileJava: Execution failed for task ':app:compileJava'.
            Compilation failed; see the compiler output below.
            /work/src/main/java/app/App.java:4: error: An unhandled exception was thrown by the Error Prone static analysis plugin.
                public int run() {
                           ^
                 Please report this at https://github.com/google/error-prone/issues/new and include the following:
                 error-prone version: 2.52.0
            """;

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void aFailureIsTheEnvironmentsOnlyWhenADownloadFailed(String failureText, AnalysisExcludeCategory expected) {
        assertEquals(expected, BuildFailureClassifier.category(failureText));
    }
    @Test
    void aCompilerCrashIsReadFromTheLineThatShowsIt() {
        assertEquals(
                Optional.of("/work/src/main/java/app/App.java:4: error: An unhandled exception was thrown by the Error Prone static analysis plugin."),
                BuildFailureClassifier.compilerCrash(GRADLE_ERROR_PRONE_CRASH));
        assertEquals(
                Optional.of("[ERROR] Failed to execute goal maven-compiler-plugin:3.15.0:compile: Fatal error compiling: java.lang.ExceptionInInitializerError"),
                BuildFailureClassifier.compilerCrash("[INFO] BUILD FAILURE\n[ERROR] Failed to execute goal maven-compiler-plugin:3.15.0:compile: Fatal error compiling: java.lang.ExceptionInInitializerError\n"));
        assertEquals(Optional.empty(), BuildFailureClassifier.compilerCrash(GRADLE_COMPILE_ERROR));
        assertEquals(Optional.empty(), BuildFailureClassifier.compilerCrash(StringUtils.EMPTY));
    }
    private static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(Named.of("an outage page instead of a plugin's download", SPOTLESS_OUTAGE), AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE),
                Arguments.of(Named.of("a plugin whose host could not be found",
                        "[ERROR] Could not download Node.js from https://nodejs.org/dist/v22.11.0/node-v22.11.0-linux-x64.tar.gz: java.net.UnknownHostException: nodejs.org"),
                        AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE),
                Arguments.of(Named.of("a dependency Gradle could not find", GRADLE_POM_NOT_FOUND), AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE),
                Arguments.of(Named.of("a repository answering 503 to Gradle", GRADLE_SERVICE_UNAVAILABLE), AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE),
                Arguments.of(Named.of("a web page where Gradle expected a POM", GRADLE_HTML_POM), AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE),
                Arguments.of(Named.of("a repository refusing Gradle's connection", GRADLE_CONNECTION_REFUSED), AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE),
                Arguments.of(Named.of("a local project Gradle could not match, which names no repository", GRADLE_LOCAL_VARIANT_MISMATCH), AnalysisExcludeCategory.BUILD_FAILURE),
                Arguments.of(Named.of("a Maven compile error", "[ERROR] /work/src/main/java/app/Ledger.java:[42,17] cannot find symbol"), AnalysisExcludeCategory.BUILD_FAILURE),
                Arguments.of(Named.of("a goal a POM misnames, beside Maven's help link",
                        "[ERROR] Could not find goal 'aply' in plugin com.diffplug.spotless:spotless-maven-plugin:2.44.4\n[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoNotFoundException"),
                        AnalysisExcludeCategory.BUILD_FAILURE),
                Arguments.of(Named.of("a Gradle compile error", GRADLE_COMPILE_ERROR), AnalysisExcludeCategory.BUILD_FAILURE),
                Arguments.of(Named.of("an annotation processor that threw, which may be the author's own",
                        ":app:compileJava: Execution failed for task ':app:compileJava'.\njava.lang.IllegalStateException: processor failed on App"),
                        AnalysisExcludeCategory.BUILD_FAILURE),
                Arguments.of(Named.of("a URL with no failed fetch",
                        "[ERROR] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies failed, see https://maven.apache.org/enforcer/"),
                        AnalysisExcludeCategory.BUILD_FAILURE),
                Arguments.of(Named.of("no text at all", StringUtils.EMPTY), AnalysisExcludeCategory.BUILD_FAILURE));
    }
}
