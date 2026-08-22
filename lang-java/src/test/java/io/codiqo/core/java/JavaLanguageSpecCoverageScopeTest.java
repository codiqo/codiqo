package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.JvmProjectSpec;
import io.codiqo.api.RunArgs;

/**
 * Which classes a coverage figure is allowed to speak for. Both halves used to be decided by the mere existence of a
 * compiled output directory, which says nothing about whether a test ever loaded the classes or whether the operator
 * asked for that tree to be left out.
 */
class JavaLanguageSpecCoverageScopeTest {
    @TempDir
    Path workTree;

    @Test
    void aModuleWhoseClassesAppearInTheExecutionDataIsExercised() throws Exception {
        File classes = compiled("guava/target/classes", "com/google/common/base/Strings.class");

        assertTrue(exercisedAt(classes, exec("guava-tests", 3_000, "com/google/common/base/Strings")).isPresent());
    }
    /** guava keeps its production code in one module and every test in another, so its own exec file is never written */
    @Test
    void aModuleExercisedOnlyByAnotherModulesTestsIsStillAdmitted() throws Exception {
        File classes = compiled("guava/target/classes", "com/google/common/base/Strings.class");

        assertTrue(exercisedAt(classes,
                exec("guava-tests", 3_000, "com/google/common/base/StringsTest", "com/google/common/base/Strings")).isPresent());
    }
    @Test
    void aModuleNoTestEverLoadedIsNotAdmitted() throws Exception {
        File classes = compiled("codegen/target/classes", "com/example/codegen/Generator.class");

        assertTrue(exercisedAt(classes, exec("other", 3_000, "com/google/common/base/Strings")).isEmpty());
    }
    @Test
    void anEmptyOutputDirectoryIsNotAdmitted() throws Exception {
        File classes = compiled("empty/target/classes");

        assertTrue(exercisedAt(classes, exec("other", 3_000, "com/example/Anything")).isEmpty());
    }
    /** a versioned copy carries the META-INF prefix, so only the base copy can match a recorded class name */
    @Test
    void aMultiReleaseCopyAloneDoesNotAdmitTheModule() throws Exception {
        File classes = compiled("mr/target/classes", "META-INF/versions/21/com/example/Only.class");

        assertTrue(exercisedAt(classes, exec("other", 3_000, "com/example/Only")).isEmpty());
    }
    /**
     * The regression the newest-exec-overall comparison caused: module A is only ever exercised by an OLD exec, while
     * an unrelated module B has just been re-run. Judging A against the newest exec in the build let B's fresh file
     * vouch for A's stale probes, so A's changed classes were scored against coverage recorded before they existed.
     * The answer for A has to be A's own exec time, whatever else the build wrote afterwards.
     */
    @Test
    void aFreshExecForAnotherModuleDoesNotVouchForThisOnesStaleCoverage() throws Exception {
        File classes = compiled("a/target/classes", "com/example/a/Service.class");

        Optional<Date> exercisedAt = exercisedAt(classes,
                exec("a-old", 1_000, "com/example/a/Service"),
                exec("b-fresh", 9_000, "com/example/b/Other"));

        assertEquals(Optional.of(new Date(1_000)), exercisedAt);
    }
    /** among execs that do cover the module, the newest one wins — that is the coverage the analysis will use */
    @Test
    void theNewestExecCoveringTheModuleIsTheOneThatCounts() throws Exception {
        File classes = compiled("a/target/classes", "com/example/a/Service.class");

        Optional<Date> exercisedAt = exercisedAt(classes,
                exec("a-old", 1_000, "com/example/a/Service"),
                exec("a-new", 5_000, "com/example/a/Service"),
                exec("b-fresh", 9_000, "com/example/b/Other"));

        assertEquals(Optional.of(new Date(5_000)), exercisedAt);
    }
    @Test
    void aSourceFileOutsideEveryExcludePatternIsKept() throws Exception {
        Path sourceRoot = sourceRoot("core/src/main/java", "com/example/Kept.java");
        Map<File, ISourceFileCoverage> coverages = new HashMap<>();

        Set<CoverageSourceFile> excluded = JavaLanguageSpec.collectSourceCoverage(
                args("android/**"), workTree.toFile(), project(sourceRoot), bundle("com/example", "Kept.java"), coverages);

        assertTrue(excluded.isEmpty(), excluded.toString());
        assertEquals(1, coverages.size());
        assertTrue(coverages.containsKey(sourceRoot.resolve("com/example/Kept.java").toFile()));
    }
    /**
     * guava's android/ tree is a file-for-file mirror of guava/, so its classes carry the same names and the same
     * coverage twice. the pattern that keeps it out of CPD and the symbol index has to keep it out of here too.
     */
    @Test
    void aSourceFileUnderAnExcludedTreeIsDroppedFromCoverage() throws Exception {
        Path sourceRoot = sourceRoot("android/guava/src/main/java", "com/example/Mirrored.java");
        Map<File, ISourceFileCoverage> coverages = new HashMap<>();

        Set<CoverageSourceFile> excluded = JavaLanguageSpec.collectSourceCoverage(
                args("android/**"), workTree.toFile(), project(sourceRoot), bundle("com/example", "Mirrored.java"), coverages);

        assertEquals(Set.of(new CoverageSourceFile("com/example", "Mirrored.java")), excluded);
        assertTrue(coverages.isEmpty(), coverages.toString());
    }
    @Test
    void withoutAnyPatternEverySourceFileIsKept() throws Exception {
        Path sourceRoot = sourceRoot("android/guava/src/main/java", "com/example/Mirrored.java");
        Map<File, ISourceFileCoverage> coverages = new HashMap<>();

        Set<CoverageSourceFile> excluded = JavaLanguageSpec.collectSourceCoverage(
                args(null), workTree.toFile(), project(sourceRoot), bundle("com/example", "Mirrored.java"), coverages);

        assertTrue(excluded.isEmpty(), excluded.toString());
        assertEquals(1, coverages.size());
    }
    private Optional<Date> exercisedAt(File outputDirectory, JavaLanguageSpec.LoadedExec... execs) throws Exception {
        return JavaLanguageSpec.newestExecContaining(List.of(execs), JavaLanguageSpec.compiledClassNames(outputDirectory));
    }
    private JavaLanguageSpec.LoadedExec exec(String name, long lastModified, String... classNames) throws Exception {
        Path file = Files.createFile(workTree.resolve(name + ".exec"));
        assertTrue(file.toFile().setLastModified(lastModified), "could not stamp " + file);
        return new JavaLanguageSpec.LoadedExec(file.toFile(), Set.of(classNames));
    }
    private File compiled(String outputDir, String... classFiles) throws Exception {
        Path root = workTree.resolve(outputDir);
        Files.createDirectories(root);
        for (String classFile : classFiles) {
            Path compiled = root.resolve(classFile);
            Files.createDirectories(compiled.getParent());
            Files.createFile(compiled);
        }
        return root.toFile();
    }
    private Path sourceRoot(String sourceRoot, String sourceFile) throws Exception {
        Path root = workTree.resolve(sourceRoot);
        Path source = root.resolve(sourceFile);
        Files.createDirectories(source.getParent());
        Files.createFile(source);
        return root;
    }
    private static RunArgs args(String excludePaths) {
        RunArgs toReturn = new RunArgs();
        toReturn.setExcludePaths(excludePaths);
        return toReturn;
    }
    private static JvmProjectSpec project(Path sourceRoot) {
        JvmProjectSpec toReturn = mock(JvmProjectSpec.class);
        when(toReturn.getCompileSourceRoots()).thenReturn(List.of(sourceRoot.toFile()));
        return toReturn;
    }
    private static IBundleCoverage bundle(String packageName, String sourceFileName) {
        ISourceFileCoverage source = mock(ISourceFileCoverage.class);
        when(source.getPackageName()).thenReturn(packageName);
        when(source.getName()).thenReturn(sourceFileName);

        IPackageCoverage pkg = mock(IPackageCoverage.class);
        when(pkg.getSourceFiles()).thenReturn(List.of(source));

        IBundleCoverage toReturn = mock(IBundleCoverage.class);
        when(toReturn.getPackages()).thenReturn(List.of(pkg));
        return toReturn;
    }
}
