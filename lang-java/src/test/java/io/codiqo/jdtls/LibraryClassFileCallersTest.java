package io.codiqo.jdtls;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import io.codiqo.api.BuildTool;
import io.codiqo.api.JvmProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.util.Fetch;


/**
 * Starts a real jdt.ls on a Maven project with scala-library 2.13 on its test classpath, the way Kafka's test
 * utilities bring it in. The library's scala/math/Ordering$$anon$1 calls apply(..) and declares max/min with a type
 * variable bounded by itself, and JDT recursed on that bound until the stack overflowed, failing every one-argument
 * apply(..) caller lookup (eclipse-jdt/eclipse.jdt.core#5561). Caller searches now cover project sources only, so
 * the lookup answers with the project's own caller.
 */
class LibraryClassFileCallersTest {
    private static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1</version>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.scala-lang</groupId>
                        <artifactId>scala-library</artifactId>
                        <version>2.13.18</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """;
    private static final String HANDLER = """
            package com.example;

            public class Handler {
                public void apply(String request) {
                    System.out.println(request);
                }
            }
            """;
    private static final String CALLER = """
            package com.example;

            public class Caller {
                public void call(Handler handler) {
                    handler.apply("x");
                }
            }
            """;
    private static final int APPLY_LINE = 3;
    private static final int APPLY_NAME_START = 16;
    private static final int APPLY_NAME_END = 21;
    private static final int APPLY_LAST_LINE = 5;

    @TempDir
    Path root;

    @Test
    void oneArgumentApplyResolvesItsCallerWithScalaLibraryOnTheTestClasspath() throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM);
        Path pkg = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Handler.java"), HANDLER);
        Files.writeString(pkg.resolve("Caller.java"), CALLER);

        RunArgs args = new RunArgs();
        args.setBuildTool(BuildTool.MAVEN);
        args.setJavaHome(new File(System.getProperty("java.home")));
        /**
         * The default language server needs Java 25. A build that runs this test on an older JDK names one for the server
         * alone with -Dcodiqo.jdtlsJavaHome, as the release workflow does; without it the server runs on this JVM.
         */
        Optional.ofNullable(System.getProperty("codiqo.jdtlsJavaHome")).map(File::new).ifPresent(args::setJdtlsJavaHome);
        args.getProjects().add(module(root, root.resolve("src/main/java"), root.resolve("target/classes")));

        try (Git git = Git.init().setDirectory(root.toFile()).call();
                Fetch fetch = new Fetch(args);
                JdtLspProjectImporter importer = new JdtLspProjectImporter(StdoutLog.FACTORY, args, fetch)) {
            args.setGit(git.getRepository());
            importer.load();

            List<CallHierarchyIncomingCall> calls = importer.callHierarchyIncomingCalls(applyItem())
                    .get(args.getLspQueryTimeout().getSeconds(), TimeUnit.SECONDS);

            assertEquals(List.of("call(Handler) : void"), calls.stream().map(call -> call.getFrom().getName()).toList());
        }
    }
    private CallHierarchyItem applyItem() {
        CallHierarchyItem toReturn = new CallHierarchyItem();
        toReturn.setName("apply(String)");
        toReturn.setKind(SymbolKind.Method);
        toReturn.setUri(root.resolve("src/main/java/com/example/Handler.java").toUri().toString());
        toReturn.setDetail("com.example.Handler");
        toReturn.setRange(new Range(new Position(APPLY_LINE, 4), new Position(APPLY_LAST_LINE, 5)));
        toReturn.setSelectionRange(new Range(new Position(APPLY_LINE, APPLY_NAME_START), new Position(APPLY_LINE, APPLY_NAME_END)));
        return toReturn;
    }
    private static JvmProjectSpec module(Path baseDirectory, Path sourceRoot, Path classes) {
        InvocationHandler handler = (proxy, method, methodArgs) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, methodArgs);
            }
            return switch (method.getName()) {
                case "getId", "toString" -> "lib";
                case "getBaseDirectory" -> baseDirectory.toFile();
                case "getCompileSourceRoots", "getDeclaredSourceRoots" -> List.of(sourceRoot.toFile());
                case "getTestCompileSourceRoots", "getDeclaredTestSourceRoots" -> List.of();
                case "getOutputDirectory" -> classes.toFile();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == methodArgs[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        };
        return (JvmProjectSpec) Proxy.newProxyInstance(LibraryClassFileCallersTest.class.getClassLoader(), new Class<?>[] { JvmProjectSpec.class }, handler);
    }

    private static class StdoutLog implements Log {
        private static final LogFactory FACTORY = clazz -> new StdoutLog();

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
            System.out.println(level + " " + (Objects.isNull(formatArgs) ? message : String.format(message, formatArgs)) + (Objects.isNull(error) ? "" : " " + error));
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
