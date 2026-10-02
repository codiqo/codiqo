package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenProjectWrapperTestResourceTest {
    /**
     * the commit deleted the last test, so src/test/java is gone from the work tree; its deletions are still tests
     */
    @Test
    void fileUnderADeclaredTestRootTheCommitEmptiedIsATest(@TempDir Path root) {
        MavenProjectWrapper module = new MavenProjectWrapper();
        module.getDeclaredTestSourceRoots().add(root.resolve("src/test/java").toFile());

        assertTrue(module.isTestResource(root.resolve("src/test/java/FooTest.java").toFile()));
        assertFalse(module.isTestResource(root.resolve("src/main/java/Foo.java").toFile()));
    }
}
