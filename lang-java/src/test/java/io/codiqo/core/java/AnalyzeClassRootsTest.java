package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code analyzeClassRoots} keeps a multi-release build's {@code META-INF/versions/<n>/} copies away from JaCoCo,
 * whose walk otherwise reads both copies as one fully-qualified name and aborts the whole coverage capture. An
 * empty array means "nothing to analyse"; only a null return — listFiles failing outright — is the fallback case.
 */
class AnalyzeClassRootsTest {
    @TempDir
    Path outputDir;

    @Test
    void anOutputDirectoryHoldingOnlyVersionedCopiesAnalysesNothing() throws Exception {
        writeClassTo(outputDir.resolve("META-INF/versions/9/io/codiqo/core/java/JavaBinaryFormat.class"));

        assertEquals(0, analyze(), "the versioned copy must not be analysed just because it is the only thing present");
    }
    @Test
    void theBaseCopyIsAnalysedAndTheVersionedCopyIsNot() throws Exception {
        writeClassTo(outputDir.resolve("io/codiqo/core/java/JavaBinaryFormat.class"));
        writeClassTo(outputDir.resolve("META-INF/versions/9/io/codiqo/core/java/JavaBinaryFormat.class"));

        assertEquals(1, analyze(), "exactly the base copy — analysing both is what aborts the whole capture");
    }
    @Test
    void aRegularClassTreeIsAnalysedNormally() throws Exception {
        writeClassTo(outputDir.resolve("io/codiqo/core/java/JavaBinaryFormat.class"));

        assertEquals(1, analyze());
    }
    /** listFiles returns null rather than an empty array when the path is not a readable directory */
    @Test
    void aPathThatCannotBeListedFallsBackToTheContentWalk() throws Exception {
        Path notADirectory = outputDir.resolve("classes.txt");
        Files.writeString(notADirectory, "not a class\n", StandardCharsets.UTF_8);

        CoverageBuilder builder = new CoverageBuilder();
        assertEquals(0, JavaLanguageSpec.analyzeClassRoots(
                new Analyzer(new ExecutionDataStore(), builder), notADirectory.toFile()));
    }
    private int analyze() throws Exception {
        CoverageBuilder builder = new CoverageBuilder();
        return JavaLanguageSpec.analyzeClassRoots(new Analyzer(new ExecutionDataStore(), builder), outputDir.toFile());
    }
    private void writeClassTo(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (InputStream compiled = getClass().getResourceAsStream("/io/codiqo/core/java/JavaBinaryFormat.class")) {
            assertNotNull(compiled, "the fixture needs a real class file to analyse");
            Files.copy(compiled, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
