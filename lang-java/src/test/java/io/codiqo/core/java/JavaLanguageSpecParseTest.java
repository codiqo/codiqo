package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.util.Fetch;

/**
 * Which method and constructor bodies the index walk reaches. Every type declaration, anonymous class and lambda is a
 * "find boundary" in PMD's Java AST, so a traversal that does not cross them reaches nothing at all — a failure that is
 * silent (zero code units, no error) and would let a whole commit score as if it changed no code.
 */
class JavaLanguageSpecParseTest {
    private static final String SOURCE = """
            package com.example;
            import java.util.concurrent.Callable;
            public class Sample {
                public String top() {
                    return "top";
                }
                Sample() {
                    System.out.println("ctor");
                }
                void empty() {
                }
                static class Nested {
                    void inNested() {
                        System.out.println("nested");
                    }
                }
                Callable<String> anonymous() {
                    return new Callable<String>() {
                        @Override
                        public String call() {
                            return "anon";
                        }
                    };
                }
                Runnable lambda() {
                    return () -> System.out.println("lambda");
                }
                interface Api {
                    void noBody();
                }
            }
            """;

    @TempDir
    Path workTree;

    @Test
    void everyBodyBehindAFindBoundaryIsIndexed() throws Exception {
        File source = write("Sample.java");

        try (JavaLanguageSpec spec = spec()) {
            List<CodeBlockInfo> blocks = spec.parse(mock(ProjectSpec.class), List.of(source));

            /**
             * the $Nested and $1 owners are the point: inNested lives inside a nested type and call() inside an
             * anonymous class, both find boundaries. empty() has an empty body and noBody() has none at all, so
             * neither is a unit of work.
             */
            assertEquals(
                    Set.of(
                            "com/example/Sample.top()Ljava/lang/String;",
                            "com/example/Sample.<init>()V",
                            "com/example/Sample$Nested.inNested()V",
                            "com/example/Sample.anonymous()Ljava/util/concurrent/Callable;",
                            "com/example/Sample$1.call()Ljava/lang/String;",
                            "com/example/Sample.lambda()Ljava/lang/Runnable;"),
                    blocks.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toSet()));
        }
    }
    @Test
    void aFileOfAnotherLanguageIsSkipped() throws Exception {
        File source = write("Sample.kt");

        try (JavaLanguageSpec spec = spec()) {
            assertEquals(List.of(), spec.parse(mock(ProjectSpec.class), List.of(source)));
        }
    }
    private File write(String name) throws Exception {
        Path toReturn = workTree.resolve(name);
        Files.writeString(toReturn, SOURCE);
        return toReturn.toFile();
    }
    private static JavaLanguageSpec spec() {
        RunArgs args = new RunArgs();
        return new JavaLanguageSpec(NoopLog.FACTORY, args, new Fetch(args));
    }

    private static class NoopLog implements Log {
        private static final LogFactory FACTORY = clazz -> new NoopLog();

        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
