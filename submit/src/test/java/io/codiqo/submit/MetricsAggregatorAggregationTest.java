package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.ModuleModel;

/**
 * The roll-up from per-module trackers to the project totals every quality figure and every driver scaler is
 * derived from. Two modules are always used, with different values, because a single-module fixture cannot tell
 * summing from overwriting.
 */
class MetricsAggregatorAggregationTest {
    private SubmissionContext ctx;

    @BeforeEach
    void buildContext() {
        RunArgs args = new RunArgs();
        ctx = SubmissionContext.create(
                args, index(), new StubAnalysis(), Path.of("."), StubAnalysis.LOGS,
                "group:artifact", "test", new ClientInfoModel());

        ctx.getProjectModel().setModules(new ArrayList<>(List.of(module("a"), module("b"))));

        ModuleQualityTracker a = ctx.trackerFor("a");
        a.incrementFilesChanged();
        a.incrementCodeUnits();
        a.addStatements(10);
        a.addPmdViolations(2);
        a.addSpotbugsIssues(1);
        a.addCoverage(80.0);
        a.addComplexity(4);
        a.addChangedLineCoverage(8, 10);
        a.addAddedLineCoverage(5, 6);
        a.addModifiedLineCoverage(3, 4);
        a.addModuleMethod(true);
        a.addModuleStatements(100);
        a.addModuleCoverageLines(70, 30);
        a.addModuleCoverageBranches(6, 4);
        a.addModuleComplexity(3);
        a.addModuleUniqueClass("io.codiqo.A");
        a.addModuleMethodSample("A.java", "run", new DriverScaler.Sample(10, 5, 3), false);
        a.incrementTrivialMethod(false);

        ModuleQualityTracker b = ctx.trackerFor("b");
        b.incrementFilesChanged();
        b.addStatements(20);
        b.addPmdViolations(3);
        b.addSpotbugsIssues(4);
        b.addChangedLineCoverage(2, 10);
        b.addAddedLineCoverage(1, 4);
        b.addModifiedLineCoverage(1, 6);
        b.addModuleMethod(false);
        b.addModuleStatements(300);
        b.addModuleCoverageLines(30, 70);
        b.addModuleCoverageBranches(2, 8);
        b.addModuleUniqueClass("io.codiqo.B");
        b.addModuleMethodSample("B.java", "run", new DriverScaler.Sample(20, 9, 7), false);
        b.incrementTrivialMethod(true);
    }
    @Test
    void projectQualitySumsEveryModuleRatherThanTakingTheLast() {
        new MetricsAggregator().accept(ctx);

        var quality = ctx.getSubmissionModel().getProjectQuality();
        assertEquals(2, quality.getFilesChanged());
        assertEquals(1, quality.getCodeUnitsAffected());
        assertEquals(5, quality.getTotalPmdViolations(), "2 + 3 across the two modules");
        assertEquals(5, quality.getTotalSpotbugsIssues(), "1 + 4 across the two modules");
    }
    @Test
    void changedLineCoverageIsPooledAcrossModulesNotAveragedPerModule() {
        new MetricsAggregator().accept(ctx);

        var quality = ctx.getSubmissionModel().getProjectQuality();
        assertEquals(50.0, quality.getChangedLineCoverage(), 0.001, "(8+2) of (10+10)");
        assertEquals(60.0, quality.getAddedLineCoverage(), 0.001, "(5+1) of (6+4)");
        assertEquals(40.0, quality.getModifiedLineCoverage(), 0.001, "(3+1) of (4+6)");
    }
    @Test
    void fullProjectCoverageTotalsAndPerModuleBreakdownAgree() {
        new MetricsAggregator().accept(ctx);

        var full = ctx.getSubmissionModel().getFullProjectCoverage();
        assertEquals(2, full.getTotalMethods());
        assertEquals(1, full.getCoveredMethods());
        assertEquals(1, full.getUncoveredMethods());
        assertEquals(100, full.getCoveredLines(), "70 + 30");
        assertEquals(100, full.getMissedLines(), "30 + 70");
        assertEquals(50.0, full.getLinePercentage(), 0.001);
        assertEquals(40.0, full.getBranchPercentage(), 0.001, "(6+2) of 20");
        assertEquals(2, full.getByModule().size(), "every tracked module appears in the breakdown");
    }
    /** a module in the project model with no tracker contributes nothing and must not break the walk */
    @Test
    void aModuleWithoutATrackerIsSkipped() {
        ctx.getProjectModel().getModules().add(module("untracked"));

        new MetricsAggregator().accept(ctx);

        assertEquals(2, ctx.getSubmissionModel().getProjectQuality().getFilesChanged(), "unchanged by the extra module");
        assertEquals(2, ctx.getSubmissionModel().getFullProjectCoverage().getByModule().size());
        assertNull(ctx.getProjectModel().getModules().get(2).getQuality(), "and it gains no fabricated quality block");
    }
    @Test
    void driverMetricsPoolStatementsAndSamplesFromEveryModule() {
        MetricsAggregator.populateDriverMetrics(ctx);

        var metrics = ctx.getSubmissionModel().getProjectMetrics();
        assertEquals(400, metrics.getTotalStatements(), "100 + 300");
        assertEquals(2, metrics.getTotalClasses(), "one unique class per module");
        assertEquals(2, ctx.getMethodScalerProd().population(), "both modules' samples reach the scaler");
        assertEquals(0, ctx.getMethodScalerTest().population(), "and the test scaler stays empty");
    }
    @Test
    void trivialExclusionsAreCountedPerScope() {
        MetricsAggregator.populateDriverMetrics(ctx);

        var scalers = ctx.getSubmissionModel().getProjectMetrics().getDriverScalers();
        assertEquals(1, scalers.getTrivialMethodsProdExcluded());
        assertEquals(1, scalers.getTrivialMethodsTestExcluded());
        assertEquals(0, scalers.getTrivialConstructorsProdExcluded());
    }
    /** the same figures reach the project model, which the submission carries independently of projectMetrics */
    @Test
    void theProjectModelCarriesTheSameTotals() {
        MetricsAggregator.populateDriverMetrics(ctx);

        assertEquals(400, ctx.getProjectModel().getTotalStatements());
        assertEquals(ctx.getSubmissionModel().getProjectMetrics().getTotalMethods(), ctx.getProjectModel().getTotalMethods());
        assertNotNull(ctx.getSubmissionModel().getProjectMetrics().getDriverScalers());
    }
    /** resolving the module/tracker pairing once must not change what any consumer sees */
    @Test
    void repeatedAggregationIsStable() {
        MetricsAggregator.populateDriverMetrics(ctx);
        DriverScaler first = ctx.getMethodScalerProd();
        int firstQuantile = ctx.getMethodCapQuantileProd();

        MetricsAggregator.populateDriverMetrics(ctx);

        assertEquals(first.population(), ctx.getMethodScalerProd().population());
        assertEquals(firstQuantile, ctx.getMethodCapQuantileProd());
        assertSame(ctx.getProjectModel().getModules().get(0), ctx.getProjectModel().getModules().get(0));
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
                .totalFiles(List.of(Path.of("A.java"), Path.of("B.java")))
                .skippedFiles(List.of())
                .ignoredFiles(List.of())
                .build();
    }
}
