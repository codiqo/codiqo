package io.codiqo.maven.populator;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugRankCategory;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.metrics.CodeBlockMetrics;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.lang.spec.JavaConstructorBlockInfo;
import net.sourceforge.pmd.lang.java.ast.internal.PrettyPrintingUtil;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.RuleViolation;

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

                        boolean isTest = spec.isTestResource(sourceFile);
                        boolean isConstructor = javaBlock instanceof JavaConstructorBlockInfo;
                        boolean isTrivial = javaBlock.isTrivial();
                        CodeBlockMetrics metrics = javaBlock.metrics();

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

                            String fileDisplay = FilenameUtils.getBaseName(javaBlock.getFile().getName());
                            String blockDisplay = PrettyPrintingUtil.displaySignature(javaBlock.getDeclaration());

                            if (isConstructor) {
                                tracker.addModuleConstructorSample(fileDisplay, blockDisplay, sample, isTest);
                            } else {
                                tracker.addModuleMethodSample(fileDisplay, blockDisplay, sample, isTest);
                            }
                        }
                        tracker.addModuleComplexity(metrics.cyclo());

                        tracker.addModulePmdViolations(javaBlock.getPmdViolations().size());
                        tracker.addModuleSpotbugsIssues(javaBlock.getSpotbugs().size());
                        tracker.addModuleUniqueClass(javaBlock.getEnclosingType().getBinaryName());

                        if (!isTest) {
                            String relativePath = ctx.getWorkTree().relativize(sourceFile.toPath()).toString();
                            collectCriticalViolations(tracker, javaBlock, relativePath);
                        }
                    }
                }
            });
        }
    }
    private static void collectCriticalViolations(ModuleQualityTracker tracker, JavaCodeBlockInfo javaBlock, String filePath) {
        for (BugInstance bug : javaBlock.getSpotbugs()) {
            if (BugRankCategory.getRank(bug.getBugRank()) == BugRankCategory.SCARIEST) {
                DiagnosticModel diag = new DiagnosticModel();
                diag.setTool(DiagnosticModel.ToolEnum.SPOTBUGS);
                diag.setRuleId(bug.getBugPattern().getType());
                diag.setMessage(bug.getMessage());
                diag.setCategory(bug.getBugPattern().getCategory());
                diag.setSeverity(DiagnosticModel.SeverityEnum.ERROR);
                diag.setFilePath(filePath);

                Optional.ofNullable(bug.getPrimarySourceLineAnnotation()).ifPresent(srcLine -> {
                    LocationModel loc = new LocationModel();
                    loc.setStartLine(srcLine.getStartLine());
                    loc.setEndLine(srcLine.getEndLine());
                    diag.setLocation(loc);
                });

                tracker.addCriticalViolation(diag);
            }
        }

        for (RuleViolation violation : javaBlock.getPmdViolations()) {
            if (violation.getRule().getPriority().getPriority() == RulePriority.HIGH.getPriority()) {
                DiagnosticModel diag = new DiagnosticModel();
                diag.setTool(DiagnosticModel.ToolEnum.PMD);
                diag.setRuleId(violation.getRule().getName());
                diag.setMessage(violation.getDescription());
                diag.setCategory(violation.getRule().getRuleSetName());
                diag.setSeverity(DiagnosticModel.SeverityEnum.ERROR);
                diag.setFilePath(filePath);

                LocationModel loc = new LocationModel();
                loc.setStartLine(violation.getBeginLine());
                loc.setStartColumn(violation.getBeginColumn());
                loc.setEndLine(violation.getEndLine());
                loc.setEndColumn(violation.getEndColumn());
                diag.setLocation(loc);

                tracker.addCriticalViolation(diag);
            }
        }
    }
}
