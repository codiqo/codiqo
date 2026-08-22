package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * The key has to come out identical from both directions: JaCoCo hands over a package name and a file name, while the
 * work-tree side relativizes a real file against its source root. A class in the default package is where the two
 * diverge — JaCoCo names that package with the empty string.
 */
class CoverageSourceFileTest {
    @Test
    void aPackagedSourceFileMatchesWhatItsSourceRootRelativizesTo() {
        CoverageSourceFile source = new CoverageSourceFile("com/example", "Foo.java");

        assertEquals(relativized("com/example/Foo.java"), source.unixPath());
        assertEquals(Paths.get("com/example", "Foo.java"), source.relativePath());
    }
    @Test
    void aSourceFileInTheDefaultPackageCarriesNoLeadingSeparator() {
        CoverageSourceFile source = new CoverageSourceFile("", "Foo.java");

        assertEquals("Foo.java", source.unixPath());
        assertEquals(relativized("Foo.java"), source.unixPath());
    }
    private static String relativized(String belowSourceRoot) {
        Path sourceRoot = Paths.get("/repo/src/main/java");
        return sourceRoot.relativize(sourceRoot.resolve(belowSourceRoot)).toString();
    }
}
