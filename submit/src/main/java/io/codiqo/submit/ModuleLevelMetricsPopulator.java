package io.codiqo.submit;

import java.io.File;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.collections.api.map.primitive.IntObjectMap;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.Finding;
import io.codiqo.api.code.UnitKind;
import io.codiqo.api.coverage.LineCoverage;
import io.codiqo.api.metrics.CodeBlockMetrics;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.client.model.DiagnosticModel;

public class ModuleLevelMetricsPopulator implements SubmissionPopulator {
    private static final EnumSet<LineCoverage.Status> EXECUTED = EnumSet.of(LineCoverage.Status.COVERED, LineCoverage.Status.PARTIAL);

    @Override
    public void accept(SubmissionContext ctx) {
        for (Entry<File, Collection<CodeBlockInfo>> entry : ctx.getIndex().getBlocks().asMap().entrySet()) {
            File sourceFile = entry.getKey();
            ctx.getArgs().owner(sourceFile).ifPresent(spec -> {
                ModuleQualityTracker tracker = ctx.trackerFor(spec.getId());
                for (CodeBlockInfo block : entry.getValue()) {
                    List<Finding> findings = block.getFindings();
                    IntObjectMap<LineCoverage> lineCoverage = block.getLineCoverage();
                    int coveredLines = 0;
                    int missedLines = 0;
                    int coveredBranches = 0;
                    int missedBranches = 0;
                    boolean hasCoverage = false;

                    for (LineCoverage line : lineCoverage.values()) {
                        if (EXECUTED.contains(line.getStatus())) {
                            coveredLines++;
                            hasCoverage = true;
                        } else if (LineCoverage.Status.MISSED == line.getStatus()) {
                            missedLines++;
                        }
                        coveredBranches += line.getCoveredBranches();
                        missedBranches += line.getMissedBranches();
                    }

                    tracker.addModuleMethod(hasCoverage);
                    tracker.addModuleCoverageLines(coveredLines, missedLines);
                    tracker.addModuleCoverageBranches(coveredBranches, missedBranches);

                    boolean isTest = spec.isTestResource(sourceFile);
                    boolean isConstructor = UnitKind.CONSTRUCTOR == block.getKind();
                    boolean isTrivial = block.isTrivial();
                    CodeBlockMetrics metrics = block.metrics();

                    tracker.addModuleStatements(metrics.ncss());

                    if (isTrivial) {
                        if (isConstructor) {
                            tracker.incrementTrivialConstructor(isTest);
                        } else {
                            tracker.incrementTrivialMethod(isTest);
                        }
                    } else {
                        int sampleLines = metrics.bodyCodeLines() > 0 ? metrics.bodyCodeLines() : metrics.nonCommentCodeLines();
                        DriverScaler.Sample sample = new DriverScaler.Sample(sampleLines, metrics.ncss(), metrics.directInvocationCount());

                        String fileDisplay = FilenameUtils.getBaseName(block.getFile().getName());
                        String blockDisplay = block.getDisplaySignature();

                        if (isConstructor) {
                            tracker.addModuleConstructorSample(fileDisplay, blockDisplay, sample, isTest);
                        } else {
                            tracker.addModuleMethodSample(fileDisplay, blockDisplay, sample, isTest);
                        }
                    }
                    tracker.addModuleComplexity(metrics.cyclo());

                    tracker.addModulePmdViolations(FindingDiagnostics.count(findings, DiagnosticModel.ToolEnum.PMD));
                    tracker.addModuleSpotbugsIssues(FindingDiagnostics.count(findings, DiagnosticModel.ToolEnum.SPOTBUGS));
                    tracker.addModuleUniqueClass(block.getDeclaringType());

                    if (!isTest) {
                        String relativePath = ctx.getWorkTree().relativize(sourceFile.toPath()).toString();
                        collectCriticalViolations(tracker, findings, relativePath);
                    }
                }
            });
        }
    }
    /**
     * A module's critical violations are the findings of error severity in its production code: the scariest of
     * SpotBugs' ranks and PMD's high priority. Each is listed without what only its tool reports.
     */
    private static void collectCriticalViolations(ModuleQualityTracker tracker, List<Finding> findings, String filePath) {
        for (Finding finding : findings) {
            if (Finding.Severity.ERROR == finding.getSeverity()) {
                tracker.addCriticalViolation(FindingDiagnostics.diagnostic(finding, filePath));
            }
        }
    }
}
