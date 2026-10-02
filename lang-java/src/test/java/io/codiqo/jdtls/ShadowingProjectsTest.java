package io.codiqo.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.JvmProjectSpec;
import io.codiqo.api.ProjectSpec;

class ShadowingProjectsTest {
    /**
     * kryo: main/ declares ../src and ../test, and kryo-parent's directory holds both, so the aggregator is the one
     * project to remove
     */
    @Test
    void aggregatorHoldingARootDeclaredOutsideItsModuleIsSelected(@TempDir Path root) {
        List<File> external = ShadowingProjects.externalSourceRoots(List.of(
                module(root.resolve("main"), List.of(root.resolve("src")), List.of(root.resolve("test")))));

        assertEquals(List.of(root.resolve("src").toFile(), root.resolve("test").toFile()), external);
        assertEquals(List.of(root.toUri()), ShadowingProjects.select(List.of(root.toUri()), external));
    }
    /**
     * every conventional reactor: roots inside their own module, so nothing is external and the workspace is left
     * exactly as the import built it
     */
    @Test
    void conventionalLayoutDeclaresNothingExternal(@TempDir Path root) {
        List<File> external = ShadowingProjects.externalSourceRoots(List.of(
                module(root.resolve("core"), List.of(root.resolve("core/src/main/java")), List.of(root.resolve("core/src/test/java")))));

        assertTrue(external.isEmpty());
    }
    @Test
    void nonJavaProjectThatHoldsNoExternalRootIsKept(@TempDir Path root) {
        List<File> external = List.of(root.resolve("src").toFile());

        assertTrue(ShadowingProjects.select(List.of(root.resolve("docs").toUri()), external).isEmpty());
    }
    /**
     * two modules declaring the same tree, as kryo and kryo5 do: the root is reported once
     */
    @Test
    void sharedExternalRootIsReportedOnce(@TempDir Path root) {
        List<File> external = ShadowingProjects.externalSourceRoots(List.of(
                module(root.resolve("main"), List.of(root.resolve("src")), List.of()),
                module(root.resolve("main-versioned"), List.of(root.resolve("src")), List.of())));

        assertEquals(List.of(root.resolve("src").toFile()), external);
    }
    /**
     * ../shared/java/src is three segments deep in the aggregator but one in the module's flattened link, so the link
     * already wins the lookup; the same holds for generated sources under a target/ redirected out of the module.
     * Removing the aggregator there would change a workspace that already worked.
     */
    @Test
    void aggregatorHoldingTheRootDeeperThanTheLinkIsKept(@TempDir Path root) {
        List<File> external = List.of(root.resolve("shared/java/src").toFile(), root.resolve("target/generated-sources/annotations").toFile());

        assertTrue(ShadowingProjects.select(List.of(root.toUri()), external).isEmpty());
    }
    @Test
    void projectUrisAreMatchedAgainstRootsBelowThem(@TempDir Path root) {
        URI parent = root.toUri();
        URI sibling = root.resolveSibling(root.getFileName() + "-other").toUri();

        assertEquals(List.of(parent), ShadowingProjects.select(List.of(parent, sibling), List.of(root.resolve("src").toFile())));
    }
    /**
     * only what the selection reads is answered; the interfaces' own default methods run as written
     */
    private static ProjectSpec module(Path baseDirectory, List<Path> sourceRoots, List<Path> testSourceRoots) {
        InvocationHandler handler = (proxy, method, methodArgs) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, methodArgs);
            }
            return switch (method.getName()) {
                case "getBaseDirectory" -> baseDirectory.toFile();
                case "getCompileSourceRoots" -> sourceRoots.stream().map(Path::toFile).toList();
                case "getTestCompileSourceRoots" -> testSourceRoots.stream().map(Path::toFile).toList();
                case "toString" -> baseDirectory.toString();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == methodArgs[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        };
        return (ProjectSpec) Proxy.newProxyInstance(ShadowingProjectsTest.class.getClassLoader(), new Class<?>[] { JvmProjectSpec.class }, handler);
    }
}
