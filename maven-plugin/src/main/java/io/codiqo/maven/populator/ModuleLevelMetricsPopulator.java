package io.codiqo.maven.populator;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.BooleanUtils;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.lang.spec.JavaCodeBlockInfo;

public class ModuleLevelMetricsPopulator implements SubmissionPopulator {
    @Override
    public void accept(SubmissionContext ctx) {
        for (Entry<File, Collection<CodeBlockInfo>> entry : ctx.getIndex().getBlocks().asMap().entrySet()) {
            File sourceFile = entry.getKey();
            ctx.getArgs().owner(sourceFile).ifPresent(spec -> {
                ModuleQualityTracker tracker = ctx.getQualityTrackers().getUnchecked(spec.getId());
                for (CodeBlockInfo block : entry.getValue()) {
                    if (block instanceof JavaCodeBlockInfo) {
                        JavaCodeBlockInfo javaBlock = (JavaCodeBlockInfo) block;

                        Map<Integer, ILine> lineCoverage = javaBlock.getLineCoverage();
                        int coveredLines = 0;
                        int missedLines = 0;
                        int coveredBranches = 0;
                        int missedBranches = 0;
                        boolean hasCoverage = false;

                        for (ILine line : lineCoverage.values()) {
                            int status = line.getStatus();
                            if (BooleanUtils.or(new boolean[] { status == ICounter.FULLY_COVERED, status == ICounter.PARTLY_COVERED })) {
                                coveredLines++;
                                hasCoverage = true;
                            } else if (status == ICounter.NOT_COVERED) {
                                missedLines++;
                            }
                            coveredBranches += line.getBranchCounter().getCoveredCount();
                            missedBranches += line.getBranchCounter().getMissedCount();
                        }

                        tracker.addModuleMethod(hasCoverage);
                        tracker.addModuleCoverageLines(coveredLines, missedLines);
                        tracker.addModuleCoverageBranches(coveredBranches, missedBranches);

                        javaBlock.metrics().subscribe(metrics -> {
                            tracker.addModuleLines(metrics.ncss());
                            tracker.addModuleComplexity(metrics.cyclo());
                        });

                        tracker.addModulePmdViolations(javaBlock.getPmdViolations().size());
                        tracker.addModuleSpotbugsIssues(javaBlock.getSpotbugs().size());
                        tracker.addModuleUniqueClass(javaBlock.getEnclosingType().getBinaryName());
                    }
                }
            });
        }
    }
}
