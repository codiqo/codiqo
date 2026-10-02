package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunArgsOwnerTest {
    /**
     * kryo's layout: main/ declares ../src and ../test, so no module directory contains a single source and every one
     * of them came back an orphan — no code units, no callers, a score of zero for a commit that changed real code
     */
    @Test
    void fileInADeclaredSourceRootOutsideTheModuleDirectoryIsOwned(@TempDir Path root) {
        JvmProjectSpec main = module("kryo", root.resolve("main"), List.of(root.resolve("src")), List.of(root.resolve("test")));
        RunArgs args = args(main);

        assertEquals(Optional.of(main), args.owner(root.resolve("src/com/esotericsoftware/kryo/Kryo.java").toFile()));
        assertEquals(Optional.of(main), args.owner(root.resolve("test/com/esotericsoftware/kryo/KryoTest.java").toFile()));
    }
    /**
     * a module that borrows another's sources through build-helper leaves them with the module that both holds and
     * declares them
     */
    @Test
    void moduleDirectoryWinsOverAnotherModulesDeclaredRoot(@TempDir Path root) {
        JvmProjectSpec borrower = module("borrower", root.resolve("borrower"), List.of(root.resolve("shared/src/main/java")), List.of());
        JvmProjectSpec shared = module("shared", root.resolve("shared"), List.of(root.resolve("shared/src/main/java")), List.of());
        RunArgs args = args(borrower, shared);

        assertEquals(Optional.of(shared), args.owner(root.resolve("shared/src/main/java/Util.java").toFile()));
    }
    /**
     * two modules compiling the same tree, as kryo and kryo5 do when kryo5 is not excluded: the first in reactor order
     * owns it, every time, rather than whichever one the stream happens to reach
     */
    @Test
    void sharedDeclaredRootGoesToTheFirstModuleInReactorOrder(@TempDir Path root) {
        JvmProjectSpec first = module("kryo", root.resolve("main"), List.of(root.resolve("src")), List.of());
        JvmProjectSpec second = module("kryo5", root.resolve("main-versioned"), List.of(root.resolve("src")), List.of());
        RunArgs args = args(first, second);

        assertEquals(Optional.of(first), args.owner(root.resolve("src/Kryo.java").toFile()));
    }
    /**
     * b compiles ../a/src/it/java as tests and a does not compile it at all: the directory alone would credit a with
     * them and classify them as production code
     */
    @Test
    void declaredRootWinsOverADirectoryThatDoesNotCompileTheFile(@TempDir Path root) {
        JvmProjectSpec a = module("a", root.resolve("a"), List.of(root.resolve("a/src/main/java")), List.of());
        JvmProjectSpec b = module("b", root.resolve("b"), List.of(root.resolve("b/src/main/java")), List.of(root.resolve("a/src/it/java")));
        RunArgs args = args(a, b);

        assertEquals(Optional.of(b), args.owner(root.resolve("a/src/it/java/ItTest.java").toFile()));
    }
    @Test
    void fileNoRootHoldsBelongsToTheModuleDirectory(@TempDir Path root) {
        JvmProjectSpec a = module("a", root.resolve("a"), List.of(root.resolve("a/src/main/java")), List.of());
        RunArgs args = args(a);

        assertEquals(Optional.of(a), args.owner(root.resolve("a/pom.xml").toFile()));
        assertEquals(Optional.of(a), args.owner(root.resolve("a/src/main/resources/app.properties").toFile()));
    }
    @Test
    void declaredRootNeverReachesIntoAnExcludedModule(@TempDir Path root) {
        JvmProjectSpec included = module("included", root.resolve("included"), List.of(root.resolve("excluded/src/main/java")), List.of());
        RunArgs args = args(included);
        args.getExcludedProjectDirs().add(root.resolve("excluded").toFile());

        assertTrue(args.owner(root.resolve("excluded/src/main/java/Gone.java").toFile()).isEmpty());
    }
    /**
     * the build tool spells the work tree through a symlink (/var on macOS) and the language server through its real
     * path (/private/var); the exclusion must hold under both spellings, as the ownership checks it guards already do
     */
    @Test
    void declaredRootNeverReachesIntoAnExcludedModuleSpelledThroughASymlink(@TempDir Path root) throws Exception {
        Path real = Files.createDirectories(root.resolve("real"));
        Files.createDirectories(real.resolve("excluded/src/main/java"));
        Path link = Files.createSymbolicLink(root.resolve("link"), real);
        JvmProjectSpec included = module("included", link.resolve("included"), List.of(link.resolve("excluded/src/main/java")), List.of());
        RunArgs args = args(included);
        args.getExcludedProjectDirs().add(link.resolve("excluded").toFile());

        assertTrue(args.owner(real.resolve("excluded/src/main/java/Gone.java").toFile()).isEmpty());
    }
    /**
     * a commit that deletes kryo's last test leaves ../test absent from the work tree, so the compile lists drop it;
     * the deletions must still belong to the module that declares it
     */
    @Test
    void deletionFromARootTheCommitEmptiedKeepsItsOwner(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        JvmProjectSpec main = module("kryo", root.resolve("main"), List.of(root.resolve("src")), List.of(root.resolve("test")));
        RunArgs args = args(main);

        assertTrue(main.getTestCompileSourceRoots().isEmpty());
        assertEquals(Optional.of(main), args.owner(root.resolve("test/com/esotericsoftware/kryo/KryoTest.java").toFile()));
    }
    @Test
    void fileNeitherUnderAModuleNorInADeclaredRootStaysAnOrphan(@TempDir Path root) {
        RunArgs args = args(module("kryo", root.resolve("main"), List.of(root.resolve("src")), List.of(root.resolve("test"))));

        assertTrue(args.owner(root.resolve("benchmarks/src/Bench.java").toFile()).isEmpty());
        assertTrue(args.owner(root.resolve("README.md").toFile()).isEmpty());
    }
    private static RunArgs args(ProjectSpec... modules) {
        RunArgs args = new RunArgs();
        args.getProjects().addAll(List.of(modules));
        return args;
    }
    /**
     * only what ownership reads is answered; the interface's own default methods run as written, so contains and
     * declaresSource are the production code under test. The compile lists drop absent roots, as both wrappers do
     */
    private static JvmProjectSpec module(String id, Path baseDirectory, List<Path> sourceRoots, List<Path> testSourceRoots) {
        InvocationHandler handler = (proxy, method, methodArgs) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, methodArgs);
            }
            return switch (method.getName()) {
                case "getId", "toString" -> id;
                case "getBaseDirectory" -> baseDirectory.toFile();
                case "getCompileSourceRoots" -> sourceRoots.stream().filter(Files::exists).map(Path::toFile).toList();
                case "getTestCompileSourceRoots" -> testSourceRoots.stream().filter(Files::exists).map(Path::toFile).toList();
                case "getDeclaredSourceRoots" -> sourceRoots.stream().map(Path::toFile).toList();
                case "getDeclaredTestSourceRoots" -> testSourceRoots.stream().map(Path::toFile).toList();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == methodArgs[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        };
        return (JvmProjectSpec) Proxy.newProxyInstance(RunArgsOwnerTest.class.getClassLoader(), new Class<?>[] { JvmProjectSpec.class }, handler);
    }
}
