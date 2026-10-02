package io.codiqo.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

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
 * Starts a real jdt.ls on kryo's layout: main/ declares ../src, so the aggregator above it holds the sources as well.
 * Callers must resolve, and the import must leave the forked build's class files alone — once a jdt.ls build replaced
 * them with ECJ's, the class ids stopped matching the JaCoCo execution data and coverage dropped to zero, which no
 * test noticed.
 */
class ExternalSourceRootImportTest {
    private static final String UTIL = """
            package com.example;

            public class Util {
                public static int helper() {
                    return 1;
                }
            }
            """;
    private static final String CALLER = """
            package com.example;

            public class Caller {
                public int call() {
                    return Util.helper();
                }
            }
            """;
    private static final int HELPER_LINE = 3;
    private static final int HELPER_NAME_START = 22;
    private static final int HELPER_NAME_END = 28;
    private static final int HELPER_LAST_LINE = 5;

    @TempDir
    Path root;

    @Test
    void mavenModuleDeclaringSourcesOutsideItsDirectoryResolvesCallersWithoutRebuildingTheClasses() throws Exception {
        writeMavenBuild();
        importAndVerify(BuildTool.MAVEN, root.resolve("main/target/classes"));
    }
    @Test
    void gradleProjectDeclaringSourcesOutsideItsDirectoryResolvesCallersWithoutRebuildingTheClasses() throws Exception {
        writeGradleBuild();
        importAndVerify(BuildTool.GRADLE, root.resolve("main/build/classes/java/main"));
    }
    private void importAndVerify(BuildTool buildTool, Path classes) throws Exception {
        writeSources();
        compile(classes);
        Map<String, String> before = digests(classes);

        RunArgs args = new RunArgs();
        args.setBuildTool(buildTool);
        args.setJavaHome(new File(System.getProperty("java.home")));
        args.getProjects().add(module(root.resolve("main"), root.resolve("src"), classes));

        try (Git git = Git.init().setDirectory(root.toFile()).call();
                Fetch fetch = new Fetch(args);
                JdtLspProjectImporter importer = new JdtLspProjectImporter(StdoutLog.FACTORY, args, fetch)) {
            args.setGit(git.getRepository());
            importer.load();

            List<CallHierarchyIncomingCall> calls = importer.callHierarchyIncomingCalls(helperItem())
                    .get(args.getLspQueryTimeout().getSeconds(), TimeUnit.SECONDS);

            assertNotNull(calls, "the aggregator's copy of Util.java won the lookup, so no compilation unit answered");
            assertEquals(List.of("call() : int"), calls.stream().map(call -> call.getFrom().getName()).toList());
        }

        assertEquals(before, digests(classes), "the language server rewrote the forked build's class files");
    }
    private void writeMavenBuild() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>main</module>
                    </modules>
                    <properties>
                        <maven.compiler.release>17</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                </project>
                """);
        Files.createDirectories(root.resolve("main"));
        Files.writeString(root.resolve("main/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>lib</artifactId>
                    <build>
                        <sourceDirectory>../src</sourceDirectory>
                    </build>
                </project>
                """);
    }
    /**
     * the wrapper names the distribution so Buildship uses it rather than one it picks itself; no wrapper jar is
     * needed, since the tooling API reads only the properties
     */
    private void writeGradleBuild() throws Exception {
        Files.writeString(root.resolve("settings.gradle"), """
                rootProject.name = 'parent'
                include 'main'
                """);
        Files.createDirectories(root.resolve("main"));
        Files.writeString(root.resolve("main/build.gradle"), """
                plugins {
                    id 'java'
                }
                sourceSets {
                    main {
                        java {
                            srcDirs = ['../src']
                        }
                    }
                }
                """);
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), """
                distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip
                """);
    }
    private void writeSources() throws Exception {
        Path pkg = Files.createDirectories(root.resolve("src/com/example"));
        Files.writeString(pkg.resolve("Util.java"), UTIL);
        Files.writeString(pkg.resolve("Caller.java"), CALLER);
    }
    private void compile(Path classes) throws Exception {
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int exit = javac.run(null, null, null,
                "--release", "17",
                "-d", classes.toString(),
                root.resolve("src/com/example/Util.java").toString(),
                root.resolve("src/com/example/Caller.java").toString());
        assertEquals(0, exit);
    }
    private CallHierarchyItem helperItem() {
        CallHierarchyItem toReturn = new CallHierarchyItem();
        toReturn.setName("helper()");
        toReturn.setKind(SymbolKind.Method);
        toReturn.setUri(root.resolve("src/com/example/Util.java").toUri().toString());
        toReturn.setDetail("com.example.Util");
        toReturn.setRange(new Range(new Position(HELPER_LINE, 4), new Position(HELPER_LAST_LINE, 5)));
        toReturn.setSelectionRange(new Range(new Position(HELPER_LINE, HELPER_NAME_START), new Position(HELPER_LINE, HELPER_NAME_END)));
        return toReturn;
    }
    private static Map<String, String> digests(Path dir) throws Exception {
        Map<String, String> toReturn = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                toReturn.put(dir.relativize(file).toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        }
        return toReturn;
    }
    /**
     * only what the import reads is answered; contains and the other default methods run as written
     */
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
        return (JvmProjectSpec) Proxy.newProxyInstance(ExternalSourceRootImportTest.class.getClassLoader(), new Class<?>[] { JvmProjectSpec.class }, handler);
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
