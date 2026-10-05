package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenProjectWrapperTestResourceTest {
    @Test
    void fileUnderADeclaredTestRootTheCommitEmptiedIsATest(@TempDir Path root) throws IOException {
        try (MavenProjectWrapper module = new MavenProjectWrapper()) {
            module.getDeclaredTestSourceRoots().add(root.resolve("src/test/java").toFile());

            assertTrue(module.isTestResource(root.resolve("src/test/java/FooTest.java").toFile()));
            assertFalse(module.isTestResource(root.resolve("src/main/java/Foo.java").toFile()));
        }
    }
}
