package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.coverage.CoverageExclusionReason;
import io.codiqo.api.coverage.ExcludedCoverageClass;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.ModuleModel;

/**
 * The two indexed assembly paths. {@code full} is what a scored commit runs on either build tool; {@code degraded}
 * runs after a failed build, and must leave the quality and coverage aggregates absent rather than fabricate them
 * as zeros while surviving a convention-file failure that {@code full} is required to surface.
 */
class SubmissionAssemblyFullDegradedTest {
    @TempDir
    Path workTree;

    private Repository repository;
    private Git git;
    private RunArgs args;
    private StubAnalysis analysis;
    private SubmissionContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(workTree.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(workTree.toFile(), ".git")).build();

        args = new RunArgs();
        args.setGit(repository);
        args.setAutoDiscoveryAgentInstructions(true);
        Files.writeString(workTree.resolve("CLAUDE.md"), "Fail fast. No defensive programming.\n", StandardCharsets.UTF_8);

        analysis = new StubAnalysis();
        ctx = SubmissionContext.create(args, index(), analysis, workTree, StubAnalysis.LOGS,
                "group:artifact", "test", new ClientInfoModel());
        ctx.getProjectModel().setModules(new ArrayList<>(List.of(module("a"))));

        ModuleQualityTracker tracker = ctx.trackerFor("a");
        tracker.addModuleStatements(120);
        tracker.addModuleMethod(true);
        tracker.addModuleCoverageLines(60, 40);
        tracker.addModuleUniqueClass("io.codiqo.A");
        tracker.addModuleMethodSample("A.java", "run", new DriverScaler.Sample(12, 6, 3), false);
    }
    @AfterEach
    void tearDown() {
        if (git != null) {
            git.close();
        }
        if (repository != null) {
            repository.close();
        }
    }
    @Test
    void fullPopulatesTheWholeSubmissionIncludingTheConfigAndTheConventions() throws Exception {
        SubmissionAssembly.full(ctx);

        assertNotNull(ctx.getSubmissionModel().getCommit(), "commit metadata");
        assertNotNull(ctx.getSubmissionModel().getIndex(), "index summary");
        assertNotNull(ctx.getSubmissionModel().getProjectQuality(), "quality aggregates");
        assertNotNull(ctx.getSubmissionModel().getFullProjectCoverage(), "coverage aggregates");
        assertNotNull(ctx.getSubmissionModel().getProjectMetrics(), "driver metrics");
        assertNotNull(ctx.getSubmissionModel().getScoringConfig(), "the effective scoring config");
        assertTrue(ctx.getSubmissionModel().getAgentInstructions().contains("Fail fast"), "the repository's conventions");
    }
    /** the parity gap: this block used to reach a Maven submission and never a Gradle one */
    @Test
    void fullCarriesTheExcludedCoverageClassesThatExplainMissingCoverage() throws Exception {
        analysis.withExcludedCoverageClass(
                new ExcludedCoverageClass("io.codiqo.Duplicated", CoverageExclusionReason.DUPLICATE_FULLY_QUALIFIED_NAME));

        SubmissionAssembly.full(ctx);

        assertEquals(1, ctx.getSubmissionModel().getExcludedCoverageClasses().size());
        assertEquals("io.codiqo.Duplicated",
                ctx.getSubmissionModel().getExcludedCoverageClasses().get(0).getClassName());
    }
    /**
     * the hook runs between the populators and the instruction read, so a diagnostic that needs the driver metrics
     * still reaches the operator when reading the conventions then fails
     */
    @Test
    void theHookSeesTheDriverMetricsAndRunsEvenWhenTheInstructionReadThenFails() throws Exception {
        args.setLlmConventionFilesMaxChars(1);
        AtomicReference<Object> seen = new AtomicReference<>();

        assertThrows(IllegalStateException.class,
                () -> SubmissionAssembly.full(ctx, c -> seen.set(c.getSubmissionModel().getProjectMetrics())));

        assertNotNull(seen.get(), "the hook must run, and must run after the metrics exist");
    }
    /** a scored submission has to surface a misconfigured instruction budget: the prompt would not be the configured one */
    @Test
    void fullSurfacesAnOverBudgetInstructionSet() throws Exception {
        args.setLlmConventionFilesMaxChars(1);

        assertThrows(IllegalStateException.class, () -> SubmissionAssembly.full(ctx));
    }
    /** the language server did run here, so the version it ran at is already known and travels with the submission */
    @Test
    void fullCarriesTheVersionOfTheLanguageServerThatActuallyRan() throws Exception {
        args.setJdtlsArchiveName("jdt-language-server-1.61.0-202609081200.tar.gz");

        SubmissionAssembly.full(ctx);

        assertEquals("1.61.0", ctx.getSubmissionModel().getScoringConfig().getJdtlsVersion());
    }
    /**
     * a failed build never starts a language server, so there is no version to report — and resolving one would
     * fetch latest.txt over the network, which is a lost commit rather than a degraded one whenever Eclipse is down
     */
    @Test
    void degradedReportsNoLanguageServerVersionAndNeverResolvesOne() throws Exception {
        SubmissionAssembly.degraded(ctx);

        assertNull(ctx.getSubmissionModel().getScoringConfig().getJdtlsVersion(),
                "an unresolved language server must be reported as absent, not fetched to fill the field");
    }
    @Test
    void degradedLeavesTheUnmeasuredAggregatesAbsentRatherThanZero() throws Exception {
        SubmissionAssembly.degraded(ctx);

        assertNotNull(ctx.getSubmissionModel().getProjectMetrics(), "driver statistics are still derived");
        assertEquals(120, ctx.getSubmissionModel().getProjectMetrics().getTotalStatements());
        assertNull(ctx.getSubmissionModel().getProjectQuality(),
                "no PMD, SpotBugs or coverage ran — a zeroed quality block would read as a clean commit");
        assertNull(ctx.getSubmissionModel().getFullProjectCoverage(),
                "and no coverage block either");
        assertNotNull(ctx.getSubmissionModel().getScoringConfig());
    }
    /** losing the hint is acceptable on a path nothing scores; losing the commit is not */
    @Test
    void degradedSurvivesAnOverBudgetInstructionSet() throws Exception {
        args.setLlmConventionFilesMaxChars(1);

        assertDoesNotThrow(() -> SubmissionAssembly.degraded(ctx));

        assertNull(ctx.getSubmissionModel().getAgentInstructions(), "the instructions are dropped, not truncated");
        assertNotNull(ctx.getSubmissionModel().getProjectMetrics(), "everything else still stands");
    }
    private static ModuleModel module(String id) {
        ModuleModel toReturn = new ModuleModel();
        toReturn.setId(id);
        toReturn.setName(id);
        return toReturn;
    }
    private static IndexingSummary index() {
        MultiValuedMap<File, CodeBlockInfo> blocks = new HashSetValuedHashMap<>();
        return IndexingSummary.builder()
                .projectRoot(new File("."))
                .projects(List.of())
                .blocks(blocks)
                .totalFiles(List.of(Path.of("A.java")))
                .skippedFiles(List.of())
                .ignoredFiles(List.of())
                .build();
    }
}
