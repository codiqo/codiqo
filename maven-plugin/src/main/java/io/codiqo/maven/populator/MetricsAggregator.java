package io.codiqo.maven.populator;

import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;

import com.google.common.collect.Lists;

import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.api.metrics.DriverScaler.DimensionStats;
import io.codiqo.api.metrics.DriverScore;
import io.codiqo.client.model.DimensionStatsModel;
import io.codiqo.client.model.DriverScalerModel;
import io.codiqo.client.model.DriverScalersModel;
import io.codiqo.client.model.FullProjectCoverageModel;
import io.codiqo.client.model.ModuleFullCoverageModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.ModuleQualityModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.client.model.ProjectQualityModel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MetricsAggregator implements SubmissionPopulator {
    private final int totalDuplicatedLines;

    @Override
    public void accept(SubmissionContext ctx) {
        /**
         * affected metrics aggregation (commit-level)
         */
        int totalFilesChanged = 0;
        int totalCodeUnitsAffected = 0;
        int totalPmdViolations = 0;
        int totalSpotbugsIssues = 0;
        int totalStatements = 0;
        double totalCoverage = 0.0;
        double totalComplexity = 0.0;
        int coverageCount = 0;
        int complexityCount = 0;

        /**
         * full project coverage aggregation (all code units)
         */
        int fullTotalMethods = 0;
        int fullCoveredMethods = 0;
        int fullTotalExecutableLines = 0;
        int fullCoveredLines = 0;
        int fullMissedLines = 0;
        int fullTotalBranches = 0;
        int fullCoveredBranches = 0;
        int fullMissedBranches = 0;
        int fullTotalStatementsInProject = 0;
        int fullTotalClasses = 0;
        double fullTotalComplexity = 0.0;
        int fullComplexityCount = 0;

        for (ModuleModel moduleModel : ctx.getProjectModel().getModules()) {
            ModuleQualityTracker tracker = ctx.getQualityTrackers().getIfPresent(moduleModel.getId());
            if (Objects.nonNull(tracker)) {
                ModuleQualityModel qualityModel = new ModuleQualityModel();

                /**
                 * affected metrics (commit-level)
                 */
                qualityModel.setFilesChanged(tracker.affectedFilesChanged().intValue());
                qualityModel.setCodeUnitsAffected(tracker.affectedCodeUnits().intValue());
                qualityModel.setTotalStatements(tracker.affectedTotalStatements().intValue());
                qualityModel.setTotalMethods(tracker.affectedCodeUnits().intValue());
                qualityModel.setPmdViolations(tracker.affectedPmdViolations().intValue());
                qualityModel.setSpotbugsIssues(tracker.affectedSpotbugsIssues().intValue());

                // module-level violations (all code, not just changed)
                qualityModel.setTotalPmdViolationsInModule(tracker.moduleTotalPmdViolations().intValue());
                qualityModel.setTotalSpotbugsIssuesInModule(tracker.moduleTotalSpotbugsIssues().intValue());
                qualityModel.setCriticalViolations(tracker.criticalViolations());

                /**
                 * module-level totals
                 */
                qualityModel.setTotalMethodsInModule(tracker.moduleTotalMethods().intValue());
                qualityModel.setTotalStatementsInModule(tracker.moduleTotalStatements().intValue());

                if (tracker.affectedCoverageCount().intValue() > 0) {
                    qualityModel.setAverageCoverage(tracker.affectedAverageCoverage());
                }
                if (tracker.affectedComplexityCount().intValue() > 0) {
                    qualityModel.setAverageComplexity(tracker.affectedAverageComplexity());
                }

                /**
                 * full module coverage
                 */
                ModuleFullCoverageModel moduleFullCoverage = new ModuleFullCoverageModel();
                moduleFullCoverage.setModuleId(moduleModel.getId());
                moduleFullCoverage.setTotalMethods(tracker.moduleTotalMethods().intValue());
                moduleFullCoverage.setCoveredMethods(tracker.moduleCoveredMethods().intValue());
                moduleFullCoverage.setUncoveredMethods(tracker.moduleUncoveredMethods());
                moduleFullCoverage.setTotalExecutableLines(tracker.moduleTotalExecutableLines().intValue());
                moduleFullCoverage.setCoveredLines(tracker.moduleCoveredLines().intValue());
                moduleFullCoverage.setMissedLines(tracker.moduleMissedLines().intValue());
                moduleFullCoverage.setTotalBranches(tracker.moduleTotalBranches().intValue());
                moduleFullCoverage.setCoveredBranches(tracker.moduleCoveredBranches().intValue());
                moduleFullCoverage.setMissedBranches(tracker.moduleMissedBranches().intValue());
                moduleFullCoverage.setLinePercentage(tracker.moduleLineCoveragePercent());
                moduleFullCoverage.setBranchPercentage(tracker.moduleBranchCoveragePercent());

                qualityModel.setFullCoverage(moduleFullCoverage);
                ctx.getModuleFullCoverages().put(moduleModel.getId(), moduleFullCoverage);

                /**
                 * aggregate module metrics to project totals
                 */
                fullTotalMethods += tracker.moduleTotalMethods().intValue();
                fullCoveredMethods += tracker.moduleCoveredMethods().intValue();
                fullTotalExecutableLines += tracker.moduleTotalExecutableLines().intValue();
                fullCoveredLines += tracker.moduleCoveredLines().intValue();
                fullMissedLines += tracker.moduleMissedLines().intValue();
                fullTotalBranches += tracker.moduleTotalBranches().intValue();
                fullCoveredBranches += tracker.moduleCoveredBranches().intValue();
                fullMissedBranches += tracker.moduleMissedBranches().intValue();
                fullTotalStatementsInProject += tracker.moduleTotalStatements().intValue();
                fullTotalClasses += tracker.moduleTotalClasses();
                if (tracker.moduleComplexityCount().intValue() > 0) {
                    fullTotalComplexity += tracker.moduleTotalComplexity().doubleValue();
                    fullComplexityCount += tracker.moduleComplexityCount().intValue();
                }

                moduleModel.setQuality(qualityModel);

                /**
                 * aggregate affected metrics to project totals
                 */
                totalFilesChanged += tracker.affectedFilesChanged().intValue();
                totalCodeUnitsAffected += tracker.affectedCodeUnits().intValue();
                totalPmdViolations += tracker.affectedPmdViolations().intValue();
                totalSpotbugsIssues += tracker.affectedSpotbugsIssues().intValue();
                totalStatements += tracker.affectedTotalStatements().intValue();
                totalCoverage += tracker.affectedTotalCoverage().doubleValue();
                totalComplexity += tracker.affectedTotalComplexity().doubleValue();
                coverageCount += tracker.affectedCoverageCount().intValue();
                complexityCount += tracker.affectedComplexityCount().intValue();
            }
        }

        ProjectQualityModel projectQualityModel = new ProjectQualityModel();
        projectQualityModel.setFilesChanged(totalFilesChanged);
        projectQualityModel.setCodeUnitsAffected(totalCodeUnitsAffected);
        projectQualityModel.setTotalPmdViolations(totalPmdViolations);
        projectQualityModel.setTotalSpotbugsIssues(totalSpotbugsIssues);

        if (coverageCount > 0) {
            projectQualityModel.setAverageCoverage(totalCoverage / coverageCount);
        }
        if (complexityCount > 0) {
            projectQualityModel.setAverageComplexity(totalComplexity / complexityCount);
        }
        if (totalStatements > 0 && totalDuplicatedLines > 0) {
            double cpdPercent = totalDuplicatedLines * 100.0 / totalStatements;
            projectQualityModel.setCpdDuplicationPercent(Math.min(cpdPercent, 100.0));
            ctx.getSubmissionModel().getDuplication().setDuplicatedPercentage(Math.min(cpdPercent, 100.0));
        }

        ctx.getSubmissionModel().setProjectQuality(projectQualityModel);

        FullProjectCoverageModel fullProjectCoverageModel = new FullProjectCoverageModel();
        fullProjectCoverageModel.setTotalMethods(fullTotalMethods);
        fullProjectCoverageModel.setCoveredMethods(fullCoveredMethods);
        fullProjectCoverageModel.setUncoveredMethods(fullTotalMethods - fullCoveredMethods);
        fullProjectCoverageModel.setTotalExecutableLines(fullTotalExecutableLines);
        fullProjectCoverageModel.setCoveredLines(fullCoveredLines);
        fullProjectCoverageModel.setMissedLines(fullMissedLines);
        fullProjectCoverageModel.setTotalBranches(fullTotalBranches);
        fullProjectCoverageModel.setCoveredBranches(fullCoveredBranches);
        fullProjectCoverageModel.setMissedBranches(fullMissedBranches);

        if (fullTotalExecutableLines > 0) {
            fullProjectCoverageModel.setLinePercentage(fullCoveredLines * 100.0 / fullTotalExecutableLines);
        } else {
            fullProjectCoverageModel.setLinePercentage(0.0);
        }
        if (fullTotalBranches > 0) {
            fullProjectCoverageModel.setBranchPercentage(fullCoveredBranches * 100.0 / fullTotalBranches);
        } else {
            fullProjectCoverageModel.setBranchPercentage(100.0);
        }

        fullProjectCoverageModel.setByModule(ctx.getModuleFullCoverages());
        ctx.getSubmissionModel().setFullProjectCoverage(fullProjectCoverageModel);

        ProjectMetricsModel projectMetricsModel = new ProjectMetricsModel();
        projectMetricsModel.setTotalFiles(ctx.getIndex().getTotalFiles().size());
        projectMetricsModel.setTotalMethods(ctx.getIndex().getBlocks().size());
        projectMetricsModel.setTotalStatements(fullTotalStatementsInProject);
        projectMetricsModel.setTotalClasses(fullTotalClasses);
        if (fullComplexityCount > 0) {
            projectMetricsModel.setAverageComplexity(fullTotalComplexity / fullComplexityCount);
        }
        if (fullTotalExecutableLines > 0) {
            projectMetricsModel.setAverageCoverage(fullCoveredLines * 100.0 / fullTotalExecutableLines);
        }

        ctx.getProjectModel().setTotalFiles(ctx.getIndex().getTotalFiles().size());
        ctx.getProjectModel().setTotalMethods(ctx.getIndex().getBlocks().size());
        ctx.getProjectModel().setTotalStatements(fullTotalStatementsInProject);
        ctx.getSubmissionModel().setProjectMetrics(projectMetricsModel);

        List<DriverScaler.Sample> methodProdSamples = collectSamples(ctx, ModuleQualityTracker::methodSamplesProd);
        List<DriverScaler.Sample> methodTestSamples = collectSamples(ctx, ModuleQualityTracker::methodSamplesTest);
        List<DriverScaler.Sample> constructorProdSamples = collectSamples(ctx, ModuleQualityTracker::constructorSamplesProd);
        List<DriverScaler.Sample> constructorTestSamples = collectSamples(ctx, ModuleQualityTracker::constructorSamplesTest);

        DriverScaler methodScalerProd = DriverScaler.of(methodProdSamples);
        DriverScaler methodScalerTest = DriverScaler.of(methodTestSamples);
        DriverScaler constructorScalerProd = DriverScaler.of(constructorProdSamples);
        DriverScaler constructorScalerTest = DriverScaler.of(constructorTestSamples);

        ctx.setMethodScalerProd(methodScalerProd);
        ctx.setMethodScalerTest(methodScalerTest);
        ctx.setConstructorScalerProd(constructorScalerProd);
        ctx.setConstructorScalerTest(constructorScalerTest);

        ctx.setMethodMaxProd(mergeMaxTrackers(ctx, ModuleQualityTracker::methodMaxProd));
        ctx.setMethodMaxTest(mergeMaxTrackers(ctx, ModuleQualityTracker::methodMaxTest));
        ctx.setConstructorMaxProd(mergeMaxTrackers(ctx, ModuleQualityTracker::constructorMaxProd));
        ctx.setConstructorMaxTest(mergeMaxTrackers(ctx, ModuleQualityTracker::constructorMaxTest));

        double quantileLevel = ctx.getArgs().getStatsQuantile() * 100.0;
        int methodCapQuantileProd = computeDriverQuantile(methodProdSamples, methodScalerProd, quantileLevel);
        int methodCapQuantileTest = computeDriverQuantile(methodTestSamples, methodScalerTest, quantileLevel);
        int constructorCapQuantileProd = computeDriverQuantile(constructorProdSamples, constructorScalerProd, quantileLevel);
        int constructorCapQuantileTest = computeDriverQuantile(constructorTestSamples, constructorScalerTest, quantileLevel);

        ctx.setMethodCapQuantileProd(methodCapQuantileProd);
        ctx.setMethodCapQuantileTest(methodCapQuantileTest);
        ctx.setConstructorCapQuantileProd(constructorCapQuantileProd);
        ctx.setConstructorCapQuantileTest(constructorCapQuantileTest);
        projectMetricsModel.setMethodCapQuantileProd(methodCapQuantileProd);
        projectMetricsModel.setMethodCapQuantileTest(methodCapQuantileTest);
        projectMetricsModel.setConstructorCapQuantileProd(constructorCapQuantileProd);
        projectMetricsModel.setConstructorCapQuantileTest(constructorCapQuantileTest);

        DriverScalersModel scalersModel = new DriverScalersModel();
        scalersModel.setMethodScalerProd(toModel(methodScalerProd));
        scalersModel.setMethodScalerTest(toModel(methodScalerTest));
        scalersModel.setConstructorScalerProd(toModel(constructorScalerProd));
        scalersModel.setConstructorScalerTest(toModel(constructorScalerTest));
        populateTrivialCounts(ctx, scalersModel);
        projectMetricsModel.setDriverScalers(scalersModel);
    }
    private static DriverScalerModel toModel(DriverScaler scaler) {
        DriverScalerModel toReturn = new DriverScalerModel();
        toReturn.setPopulation(scaler.population());
        toReturn.setLines(toModel(scaler.lines()));
        toReturn.setNcss(toModel(scaler.ncss()));
        toReturn.setInvocations(toModel(scaler.invocations()));
        return toReturn;
    }
    private static DimensionStatsModel toModel(DimensionStats stats) {
        DimensionStatsModel toReturn = new DimensionStatsModel();
        toReturn.setMin(stats.min());
        toReturn.setP50(stats.p50());
        toReturn.setP75(stats.p75());
        toReturn.setP90(stats.p90());
        toReturn.setP95(stats.p95());
        toReturn.setMax(stats.max());
        return toReturn;
    }
    private static void populateTrivialCounts(SubmissionContext ctx, DriverScalersModel model) {
        int methodProd = 0;
        int methodTest = 0;
        int ctorProd = 0;
        int ctorTest = 0;
        for (ModuleModel moduleModel : ctx.getProjectModel().getModules()) {
            ModuleQualityTracker tracker = ctx.getQualityTrackers().getIfPresent(moduleModel.getId());
            if (Objects.nonNull(tracker)) {
                methodProd += tracker.trivialMethodProd().intValue();
                methodTest += tracker.trivialMethodTest().intValue();
                ctorProd += tracker.trivialConstructorProd().intValue();
                ctorTest += tracker.trivialConstructorTest().intValue();
            }
        }
        model.setTrivialMethodsProdExcluded(methodProd);
        model.setTrivialMethodsTestExcluded(methodTest);
        model.setTrivialConstructorsProdExcluded(ctorProd);
        model.setTrivialConstructorsTestExcluded(ctorTest);
    }
    private static List<DriverScaler.Sample> collectSamples(SubmissionContext ctx, java.util.function.Function<ModuleQualityTracker, List<DriverScaler.Sample>> extractor) {
        List<DriverScaler.Sample> toReturn = Lists.newArrayList();
        for (ModuleModel moduleModel : ctx.getProjectModel().getModules()) {
            ModuleQualityTracker tracker = ctx.getQualityTrackers().getIfPresent(moduleModel.getId());
            if (Objects.nonNull(tracker)) {
                toReturn.addAll(extractor.apply(tracker));
            }
        }
        return toReturn;
    }
    private static SampleMaxTracker mergeMaxTrackers(SubmissionContext ctx, java.util.function.Function<ModuleQualityTracker, SampleMaxTracker> extractor) {
        SampleMaxTracker toReturn = new SampleMaxTracker();
        for (ModuleModel moduleModel : ctx.getProjectModel().getModules()) {
            ModuleQualityTracker tracker = ctx.getQualityTrackers().getIfPresent(moduleModel.getId());
            if (Objects.nonNull(tracker)) {
                toReturn.mergeFrom(extractor.apply(tracker));
            }
        }
        return toReturn;
    }
    static int computeDriverQuantile(
            List<DriverScaler.Sample> samples,
            DriverScaler scaler,
            double quantileLevel) {

        if (CollectionUtils.isEmpty(samples)) {
            return 0;
        }

        double[] values = new double[samples.size()];
        int i = 0;
        for (DriverScaler.Sample sample : samples) {
            values[i++] = DriverScore.forNew(scaler, sample.lines(), sample.ncss(), sample.invocations());
        }
        return (int) new Percentile().evaluate(values, quantileLevel);
    }
}
