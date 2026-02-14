package io.codiqo.maven.populator;

import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;

import com.google.common.collect.Lists;

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
        int totalLines = 0;
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
        int fullTotalLinesInProject = 0;
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
                qualityModel.setFilesChanged(tracker.getAffectedFilesChanged().intValue());
                qualityModel.setCodeUnitsAffected(tracker.getAffectedCodeUnits().intValue());
                qualityModel.setTotalLines(tracker.getAffectedTotalLines().intValue());
                qualityModel.setTotalMethods(tracker.getAffectedCodeUnits().intValue());
                qualityModel.setPmdViolations(tracker.getAffectedPmdViolations().intValue());
                qualityModel.setSpotbugsIssues(tracker.getAffectedSpotbugsIssues().intValue());

                // module-level violations (all code, not just changed)
                qualityModel.setTotalPmdViolationsInModule(tracker.getModuleTotalPmdViolations().intValue());
                qualityModel.setTotalSpotbugsIssuesInModule(tracker.getModuleTotalSpotbugsIssues().intValue());
                qualityModel.setCriticalViolations(tracker.getCriticalViolations());

                /**
                 * module-level totals
                 */
                qualityModel.setTotalMethodsInModule(tracker.getModuleTotalMethods().intValue());
                qualityModel.setTotalLinesInModule(tracker.getModuleTotalLines().intValue());

                if (tracker.getAffectedCoverageCount().intValue() > 0) {
                    qualityModel.setAverageCoverage(tracker.getAffectedAverageCoverage());
                }
                if (tracker.getAffectedComplexityCount().intValue() > 0) {
                    qualityModel.setAverageComplexity(tracker.getAffectedAverageComplexity());
                }

                /**
                 * full module coverage
                 */
                ModuleFullCoverageModel moduleFullCoverage = new ModuleFullCoverageModel();
                moduleFullCoverage.setModuleId(moduleModel.getId());
                moduleFullCoverage.setTotalMethods(tracker.getModuleTotalMethods().intValue());
                moduleFullCoverage.setCoveredMethods(tracker.getModuleCoveredMethods().intValue());
                moduleFullCoverage.setUncoveredMethods(tracker.getModuleUncoveredMethods());
                moduleFullCoverage.setTotalExecutableLines(tracker.getModuleTotalExecutableLines().intValue());
                moduleFullCoverage.setCoveredLines(tracker.getModuleCoveredLines().intValue());
                moduleFullCoverage.setMissedLines(tracker.getModuleMissedLines().intValue());
                moduleFullCoverage.setTotalBranches(tracker.getModuleTotalBranches().intValue());
                moduleFullCoverage.setCoveredBranches(tracker.getModuleCoveredBranches().intValue());
                moduleFullCoverage.setMissedBranches(tracker.getModuleMissedBranches().intValue());
                moduleFullCoverage.setLinePercentage(tracker.getModuleLineCoveragePercent());
                moduleFullCoverage.setBranchPercentage(tracker.getModuleBranchCoveragePercent());

                qualityModel.setFullCoverage(moduleFullCoverage);
                ctx.getModuleFullCoverages().put(moduleModel.getId(), moduleFullCoverage);

                /**
                 * aggregate module metrics to project totals
                 */
                fullTotalMethods += tracker.getModuleTotalMethods().intValue();
                fullCoveredMethods += tracker.getModuleCoveredMethods().intValue();
                fullTotalExecutableLines += tracker.getModuleTotalExecutableLines().intValue();
                fullCoveredLines += tracker.getModuleCoveredLines().intValue();
                fullMissedLines += tracker.getModuleMissedLines().intValue();
                fullTotalBranches += tracker.getModuleTotalBranches().intValue();
                fullCoveredBranches += tracker.getModuleCoveredBranches().intValue();
                fullMissedBranches += tracker.getModuleMissedBranches().intValue();
                fullTotalLinesInProject += tracker.getModuleTotalLines().intValue();
                fullTotalClasses += tracker.getModuleTotalClasses();
                if (tracker.getModuleComplexityCount().intValue() > 0) {
                    fullTotalComplexity += tracker.getModuleTotalComplexity().doubleValue();
                    fullComplexityCount += tracker.getModuleComplexityCount().intValue();
                }

                moduleModel.setQuality(qualityModel);

                /**
                 * aggregate affected metrics to project totals
                 */
                totalFilesChanged += tracker.getAffectedFilesChanged().intValue();
                totalCodeUnitsAffected += tracker.getAffectedCodeUnits().intValue();
                totalPmdViolations += tracker.getAffectedPmdViolations().intValue();
                totalSpotbugsIssues += tracker.getAffectedSpotbugsIssues().intValue();
                totalLines += tracker.getAffectedTotalLines().intValue();
                totalCoverage += tracker.getAffectedTotalCoverage().doubleValue();
                totalComplexity += tracker.getAffectedTotalComplexity().doubleValue();
                coverageCount += tracker.getAffectedCoverageCount().intValue();
                complexityCount += tracker.getAffectedComplexityCount().intValue();
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
        if (totalLines > 0 && totalDuplicatedLines > 0) {
            double cpdPercent = totalDuplicatedLines * 100.0 / totalLines;
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
        projectMetricsModel.setTotalLines(fullTotalLinesInProject);
        projectMetricsModel.setTotalClasses(fullTotalClasses);
        if (fullComplexityCount > 0) {
            projectMetricsModel.setAverageComplexity(fullTotalComplexity / fullComplexityCount);
        }
        if (fullTotalExecutableLines > 0) {
            projectMetricsModel.setAverageCoverage(fullCoveredLines * 100.0 / fullTotalExecutableLines);
        }

        ctx.getProjectModel().setTotalFiles(ctx.getIndex().getTotalFiles().size());
        ctx.getProjectModel().setTotalMethods(ctx.getIndex().getBlocks().size());
        ctx.getProjectModel().setTotalLines(fullTotalLinesInProject);
        ctx.getSubmissionModel().setProjectMetrics(projectMetricsModel);

        int linesQuantile = computeLinesQuantile(ctx);
        ctx.setLinesPerMethodQuantile(linesQuantile);
        projectMetricsModel.setLinesPerMethodQuantile(linesQuantile);
    }
    private static int computeLinesQuantile(SubmissionContext ctx) {
        List<Integer> allLines = Lists.newArrayList();
        for (ModuleModel moduleModel : ctx.getProjectModel().getModules()) {
            ModuleQualityTracker tracker = ctx.getQualityTrackers().getIfPresent(moduleModel.getId());
            if (Objects.nonNull(tracker)) {
                allLines.addAll(tracker.getModuleLinesPerMethod());
            }
        }
        if (CollectionUtils.isEmpty(allLines)) {
            return 0;
        }

        double quantileLevel = ctx.getArgs().getNcssQuantile() * 100.0;
        double[] values = allLines.stream().mapToDouble(Integer::doubleValue).toArray();
        return (int) new Percentile().evaluate(values, quantileLevel);
    }
}
