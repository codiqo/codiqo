package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

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
 * as zeros.
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

        analysis = new StubAnalysis();
        ctx = SubmissionContext.create(args, index(), analysis, workTree, StubAnalysis.LOGS,
                "group:artifact", "test", new ClientInfoModel());
        ctx.getProjectModel().setModules(Lists.newArrayList(List.of(module("a"))));

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
    void fullPopulatesTheWholeSubmissionIncludingTheConfig() throws Exception {
        SubmissionAssembly.full(ctx);

        assertNotNull(ctx.getSubmissionModel().getCommit(), "commit metadata");
        assertNotNull(ctx.getSubmissionModel().getIndex(), "index summary");
        assertNotNull(ctx.getSubmissionModel().getProjectQuality(), "quality aggregates");
        assertNotNull(ctx.getSubmissionModel().getFullProjectCoverage(), "coverage aggregates");
        assertNotNull(ctx.getSubmissionModel().getProjectMetrics(), "driver metrics");
        assertNotNull(ctx.getSubmissionModel().getScoringConfig(), "the effective scoring config");
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
    /** the hook runs after the populators, so a diagnostic that needs the driver metrics sees them */
    @Test
    void theHookSeesTheDriverMetrics() throws Exception {
        AtomicReference<Object> seen = new AtomicReference<>();

        SubmissionAssembly.full(ctx, c -> seen.set(c.getSubmissionModel().getProjectMetrics()));

        assertNotNull(seen.get(), "the hook must run, and must run after the metrics exist");
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
    private static ModuleModel module(String id) {
        ModuleModel toReturn = new ModuleModel();
        toReturn.setId(id);
        toReturn.setName(id);
        return toReturn;
    }
    private static IndexingSummary index() {
        Multimap<File, CodeBlockInfo> blocks = HashMultimap.create();
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
