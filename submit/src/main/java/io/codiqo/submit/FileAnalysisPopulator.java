package io.codiqo.submit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;

import io.codiqo.api.code.Caller;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.CompilerFinding;
import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.code.UnitKind;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.coverage.LineCoverage;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.EffectiveLineParser;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.metrics.CodeBlockMetrics;
import io.codiqo.client.model.CallerModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CoverageCounterModel;
import io.codiqo.client.model.CoverageModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LineCoverageModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.llm.lang.LanguageCapabilities;
import io.codiqo.util.JGit;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FileAnalysisPopulator implements SubmissionPopulator {
    @Override
    public void accept(SubmissionContext ctx) {
        Path workTreeRealPath = resolveRealPath(ctx.getWorkTree());
        ListMultimap<String, CompilerFinding> compilerFindings = Multimaps.index(ctx.getArgs().getCompilerFindings(), CompilerFinding::getPath);

        for (FileAnalysis fileAnalysis : ctx.getAnalysis()) {
            FileChangeModel fileChangeModel = new FileChangeModel();
            fileChangeModel.setDiff(fileAnalysis.getDiffText());
            fileChangeModel.setContentBefore(fileAnalysis.getContentBefore());
            fileChangeModel.setContentAfter(fileAnalysis.getContentAfter());
            fileChangeModel.setChangeType(FileChangeModel.ChangeTypeEnum.fromValue(fileAnalysis.getChangeType().name().toLowerCase()));
            fileChangeModel.setPreviousPath(fileAnalysis.getOldPath());
            fileChangeModel.setPath(JGit.effectivePath(fileAnalysis.getChangeType(), fileAnalysis.getOldPath(), fileAnalysis.getNewPath()));
            fileChangeModel.setIsTest(fileAnalysis.isTestFile());
            if (Objects.nonNull(fileAnalysis.getLanguage())) {
                fileChangeModel.setLanguage(FileChangeModel.LanguageEnum.fromValue(fileAnalysis.getLanguage().getWireId()));
            }
            fileAnalysis.project().ifPresent(spec -> {
                fileChangeModel.setModule(spec.getId());
                ctx.trackerFor(spec.getId()).incrementFilesChanged();
            });

            for (AffectedSymbolInfo affectedSymbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                affectedSymbol.block().ifPresent(block -> fileChangeModel.getCodeUnits().add(createCodeUnitModel(ctx, fileAnalysis, affectedSymbol, block, workTreeRealPath)));
            }
            for (AffectedSymbolInfo removed : fileAnalysis.getRemovedSymbols()) {
                removed.block().ifPresent(block -> fileChangeModel.getCodeUnits().add(removedCodeUnitModel(ctx, removed, block, workTreeRealPath)));
            }

            List<CompilerFinding> fileFindings = compilerFindings.get(fileChangeModel.getPath());
            if (BooleanUtils.and(new boolean[] { CollectionUtils.isNotEmpty(fileFindings), Objects.nonNull(fileChangeModel.getDiff()) })) {
                attachCompilerFindings(fileChangeModel, fileFindings);
            }

            if (fileAnalysis.getLineCoverage().notEmpty()) {
                fileChangeModel.setCoverage(buildCoverageModel(fileAnalysis.getLineCoverage()));
                populateChangedLineCoverage(ctx, fileChangeModel, fileAnalysis);
            }

            ctx.getSubmissionModel().getFiles().add(fileChangeModel);
        }

        /**
         * The change-set shape flags are derived once here because this is the only writer of the
         * submission's file list (the normal, degraded and exclusion paths all run this populator), so setting
         * them anywhere else could disagree with the files actually submitted. hasCodeChanges follows the
         * language field, which upstream sets only for files a LanguageSpec actually handled, so a diff-only
         * exclusion carries no language and reports false, matching "nothing was analysable".
         */
        List<FileChangeModel> files = ctx.getSubmissionModel().getFiles();
        ctx.getSubmissionModel().setConfigOnly(ConfigFiles.isConfigOnly(files.stream().map(FileChangeModel::getPath).toList()));
        ctx.getSubmissionModel().setHasCodeChanges(
                LanguageCapabilities.hasCodeChanges(files.stream().map(FileChangeModel::getLanguage).toList()));
    }
    /**
     * Attaches the compiler-plugin findings on lines the commit added to the innermost added or changed unit holding
     * the line, as the PMD and SpotBugs findings of a changed block are, so the local review's triage judges them and a
     * confirmed one becomes a bug. Only added lines count: the build compiles every file of every module and prints a
     * finding for each, and one on a line the commit did not write is not its author's. A finding outside every unit (a
     * field initializer) has no unit to sit on and is dropped. The severity is always {@code warning}: the fork demotes
     * Error Prone's errors to warnings, so the output no longer says which check was an error, and an {@code error}
     * diagnostic would carry the static-analysis penalty on a guess.
     */
    @VisibleForTesting
    public static void attachCompilerFindings(FileChangeModel file, Collection<CompilerFinding> findings) {
        Set<Integer> added = EffectiveLineParser.parseAddedLines(file.getDiff());
        for (CompilerFinding finding : findings) {
            if (added.contains(finding.getLine())) {
                LocationModel location = new LocationModel();
                location.setStartLine(finding.getLine());
                location.setEndLine(finding.getLine());

                DiagnosticModel diagnostic = new DiagnosticModel();
                diagnostic.setTool(DiagnosticModel.ToolEnum.fromValue(finding.getTool()));
                diagnostic.setRuleId(finding.getRuleId());
                diagnostic.setMessage(finding.getMessage());
                diagnostic.setFilePath(file.getPath());
                diagnostic.setSeverity(DiagnosticModel.SeverityEnum.WARNING);
                diagnostic.setLocation(location);

                file.getCodeUnits().stream()
                        .filter(unit -> unit.getOperation() != CodeUnitModel.OperationEnum.DELETE)
                        .filter(unit -> unit.getLocation().getStartLine() <= finding.getLine() && finding.getLine() <= unit.getLocation().getEndLine())
                        .min(Comparator.comparingInt(unit -> unit.getLocation().getEndLine() - unit.getLocation().getStartLine()))
                        .ifPresent(unit -> unit.getDiagnostics().add(diagnostic));
            }
        }
    }
    @VisibleForTesting
    public static CodeUnitModel removedCodeUnitModel(SubmissionContext ctx, AffectedSymbolInfo symbol, CodeBlockInfo block, Path workTreeRealPath) {
        CodeUnitModel toReturn = new CodeUnitModel();
        toReturn.setName(symbol.getName());
        toReturn.setSignature(block.getSignature());
        toReturn.setModifiers(block.getModifiers());
        toReturn.setOperation(CodeUnitModel.OperationEnum.DELETE);
        toReturn.setBodyBefore(block.getBody());
        toReturn.setLocation(locationModel(symbol.getLocation()));
        toReturn.setIsTrivial(block.isTrivial());

        populateSymbolInfo(ctx, symbol, toReturn, workTreeRealPath);
        symbol.describe(toReturn);
        return toReturn;
    }
    private static CodeUnitModel createCodeUnitModel(SubmissionContext ctx, FileAnalysis fileAnalysis, AffectedSymbolInfo affectedSymbol, CodeBlockInfo block, Path workTreeRealPath) {
        CodeUnitModel codeUnitModel = new CodeUnitModel();
        codeUnitModel.setName(affectedSymbol.getName());
        codeUnitModel.setBody(block.getBody());
        codeUnitModel.setLocation(locationModel(affectedSymbol.getLocation()));
        codeUnitModel.setModifiers(block.getModifiers());
        codeUnitModel.setSignature(block.getSignature());
        codeUnitModel.setIsTrivial(block.isTrivial());

        codeUnitModel.setOperation(switch (fileAnalysis.getChangeType()) {
            case ADD, COPY -> CodeUnitModel.OperationEnum.NEW;
            case DELETE -> CodeUnitModel.OperationEnum.DELETE;
            case MODIFY, RENAME -> CodeUnitModel.OperationEnum.MODIFY;
        });

        populateSymbolInfo(ctx, affectedSymbol, codeUnitModel, workTreeRealPath);

        List<Finding> findings = block.getFindings();
        populateCoverage(block, codeUnitModel);
        populateDiagnostics(findings, codeUnitModel, fileAnalysis.getNewPath());
        populateMetrics(ctx, fileAnalysis, block, findings, codeUnitModel);
        populateAffectedCoverage(ctx, fileAnalysis, block);

        affectedSymbol.describe(codeUnitModel);
        return codeUnitModel;
    }
    private static void populateSymbolInfo(SubmissionContext ctx, AffectedSymbolInfo symbol, CodeUnitModel codeUnitModel, Path workTreeRealPath) {
        codeUnitModel.setKind(symbolKind(symbol.getKind()));

        /**
         * Deliberately no source body per caller: nothing downstream reads one, and on a high fan-in commit the
         * bodies outweighed every other part of the submission. Blast radius is grounded by counting these rows,
         * so the rows themselves must stay complete.
         */
        for (Caller caller : symbol.getCallers()) {
            CallerModel callerModel = new CallerModel();
            callerModel.setName(caller.getName());
            callerModel.setSymbol(caller.getSymbol());
            caller.getPath().ifPresent(path -> callerPath(ctx, callerModel, path, workTreeRealPath));
            caller.getKind().map(FileAnalysisPopulator::symbolKind).ifPresent(callerModel::setKind);
            if (caller.isDeprecated()) {
                callerModel.setIsDeprecated(true);
            }
            caller.getLocation().map(FileAnalysisPopulator::locationModel).ifPresent(callerModel::setLocation);
            caller.getCallSites().stream().map(FileAnalysisPopulator::locationModel).forEach(callerModel.getCallSites()::add);

            codeUnitModel.getCallers().add(callerModel);
        }
    }
    private static void callerPath(SubmissionContext ctx, CallerModel callerModel, Path callerPath, Path workTreeRealPath) {
        for (;;) {
            try {
                File file = callerPath.toFile();
                if (file.exists()) {
                    ctx.getArgs().owner(file).ifPresent(fileSpec -> callerModel.setIsTest(fileSpec.isTestResource(file)));
                }
                callerModel.setPath(workTreeRealPath.relativize(callerPath.toRealPath()).toString());
                return;
            } catch (IOException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    private static void populateCoverage(CodeBlockInfo block, CodeUnitModel codeUnitModel) {
        if (block.coverage().hasCoverageData()) {
            codeUnitModel.setCoverage(buildCoverageModel(block.getLineCoverage()));
        }
    }
    private static CoverageModel buildCoverageModel(IntObjectMap<LineCoverage> lineCoverageMap) {
        CodeBlockCoverage cov = CodeBlockCoverage.from(lineCoverageMap);

        CoverageModel coverageModel = new CoverageModel();
        coverageModel.setTool(CoverageModel.ToolEnum.JACOCO);
        coverageModel.setCoveredLines(cov.getCovered() + cov.getPartial());
        coverageModel.setMissedLines(cov.getMissed());
        coverageModel.setLinePercent(cov.lineCoveragePercent());

        if (cov.totalBranches() > 0) {
            coverageModel.setCoveredBranches(cov.getCoveredBranches());
            coverageModel.setMissedBranches(cov.getMissedBranches());
            coverageModel.setBranchPercent(cov.branchCoveragePercent());
        }

        for (int lineNumber : lineCoverageMap.keySet().toSortedArray()) {
            LineCoverage lineInfo = lineCoverageMap.get(lineNumber);

            LineCoverageModel lineModel = new LineCoverageModel();
            lineModel.setLine(lineNumber);
            lineModel.setHits(lineInfo.getHits());
            lineModel.setStatus(switch (lineInfo.getStatus()) {
                case MISSED -> LineCoverageModel.StatusEnum.MISSED;
                case PARTIAL -> LineCoverageModel.StatusEnum.PARTIAL;
                case COVERED -> LineCoverageModel.StatusEnum.COVERED;
                case EMPTY -> LineCoverageModel.StatusEnum.EMPTY;
            });

            if (lineInfo.totalBranches() > 0) {
                CoverageCounterModel branchModel = new CoverageCounterModel();
                branchModel.setCovered(lineInfo.getCoveredBranches());
                branchModel.setMissed(lineInfo.getMissedBranches());
                lineModel.setBranches(branchModel);
            }

            coverageModel.getLines().add(lineModel);
        }

        return coverageModel;
    }
    private static void populateDiagnostics(List<Finding> findings, CodeUnitModel codeUnitModel, String filePath) {
        for (Finding finding : findings) {
            DiagnosticModel diagnostic = FindingDiagnostics.diagnostic(finding, filePath);
            finding.describe(diagnostic);
            codeUnitModel.getDiagnostics().add(diagnostic);
        }
    }
    private static void populateMetrics(SubmissionContext ctx, FileAnalysis fileAnalysis, CodeBlockInfo block, List<Finding> findings, CodeUnitModel codeUnitModel) {
        CodeBlockMetrics metrics = block.metrics();

        MetricsModel metricsModel = new MetricsModel();
        metricsModel.setCyclomaticComplexity(metrics.cyclo());
        metricsModel.setCognitiveComplexity(metrics.cognitive());
        metricsModel.setCodeLines(metrics.lineCount());
        metricsModel.setNonCommentCodeStatements(metrics.ncss());
        metricsModel.setDirectInvocationCount(metrics.directInvocationCount());
        metricsModel.setDirectInvocationLines(Lists.newArrayList(metrics.directInvocationLines()));
        metricsModel.setFanOut(metrics.fanOut());
        metricsModel.setNpath(metrics.npath());

        metricsModel.setParameterCount(block.getArity());
        metricsModel.setNonCommentCodeLines(metrics.nonCommentCodeLines());
        metricsModel.setCommentLines(metrics.commentLines());
        metricsModel.setDeclarationCodeLines(metrics.declarationCodeLines());
        metricsModel.setBodyCodeLines(metrics.bodyCodeLines());
        metricsModel.setBodyCommentLines(metrics.bodyCommentLines());

        if (metrics.bodyStartLine() > 0 && metrics.bodyEndLine() >= metrics.bodyStartLine()) {
            LocationModel location = codeUnitModel.getLocation();
            location.setBodyStartLine(metrics.bodyStartLine());
            location.setBodyEndLine(metrics.bodyEndLine());
        }

        codeUnitModel.setMetrics(metricsModel);

        fileAnalysis.project().ifPresent(spec -> {
            ModuleQualityTracker tracker = ctx.trackerFor(spec.getId());
            tracker.incrementCodeUnits();
            tracker.addStatements(metrics.ncss());
            tracker.addComplexity(metrics.cyclo());
            tracker.addPmdViolations(FindingDiagnostics.count(findings, DiagnosticModel.ToolEnum.PMD));
            tracker.addSpotbugsIssues(FindingDiagnostics.count(findings, DiagnosticModel.ToolEnum.SPOTBUGS));
        });
    }
    private static void populateAffectedCoverage(SubmissionContext ctx, FileAnalysis fileAnalysis, CodeBlockInfo block) {
        CodeBlockCoverage cov = block.coverage();
        if (cov.hasCoverageData()) {
            fileAnalysis.project().ifPresent(spec -> {
                ModuleQualityTracker tracker = ctx.trackerFor(spec.getId());
                tracker.addCoverage(cov.lineCoveragePercent());
            });
        }
    }
    private static void populateChangedLineCoverage(SubmissionContext ctx, FileChangeModel fileChangeModel, FileAnalysis fileAnalysis) {
        IntObjectMap<LineCoverage> lineCoverage = fileAnalysis.getLineCoverage();

        ChangedLines changed = ChangedLineClassifier.classify(fileAnalysis.getDiffText(), LanguageCapabilities.filterFor(fileChangeModel));

        CodeBlockCoverage addedCov = coverageOfLines(changed.getAdded(), lineCoverage);
        CodeBlockCoverage modifiedCov = coverageOfLines(changed.getModified(), lineCoverage);
        int addedCovered = addedCov.getCovered() + addedCov.getPartial();
        int addedExecutable = addedCovered + addedCov.getMissed();
        int modifiedCovered = modifiedCov.getCovered() + modifiedCov.getPartial();
        int modifiedExecutable = modifiedCovered + modifiedCov.getMissed();

        if (addedExecutable + modifiedExecutable > 0) {
            fileAnalysis.project().ifPresent(spec -> {
                ModuleQualityTracker tracker = ctx.trackerFor(spec.getId());
                tracker.addAddedLineCoverage(addedCovered, addedExecutable);
                tracker.addModifiedLineCoverage(modifiedCovered, modifiedExecutable);
                tracker.addChangedLineCoverage(addedCovered + modifiedCovered, addedExecutable + modifiedExecutable);
            });
        }
    }
    private static CodeBlockCoverage coverageOfLines(Set<Integer> lines, IntObjectMap<LineCoverage> lineCoverage) {
        MutableIntObjectMap<LineCoverage> submap = new IntObjectHashMap<>();
        for (Integer line : lines) {
            LineCoverage lineInfo = lineCoverage.get(line);
            if (Objects.nonNull(lineInfo)) {
                submap.put(line, lineInfo);
            }
        }
        return CodeBlockCoverage.from(submap);
    }
    @VisibleForTesting
    public static SymbolKindModel symbolKind(UnitKind kind) {
        return switch (kind) {
            case CLASS -> SymbolKindModel.propertyClass;
            case INTERFACE -> SymbolKindModel.INTERFACE;
            case ENUM -> SymbolKindModel.ENUM;
            case ENUM_MEMBER -> SymbolKindModel.ENUM_MEMBER;
            case RECORD -> SymbolKindModel.RECORD;
            case METHOD -> SymbolKindModel.METHOD;
            case CONSTRUCTOR -> SymbolKindModel.CONSTRUCTOR;
            case FUNCTION -> SymbolKindModel.FUNCTION;
            case FIELD -> SymbolKindModel.FIELD;
            case CONSTANT -> SymbolKindModel.CONSTANT;
            case MODULE -> SymbolKindModel.MODULE;
        };
    }
    private static LocationModel locationModel(SourceLocation location) {
        LocationModel toReturn = new LocationModel();
        toReturn.setStartLine(location.getStartLine());
        toReturn.setStartColumn(location.getStartColumn());
        toReturn.setEndLine(location.getEndLine());
        toReturn.setEndColumn(location.getEndColumn());
        return toReturn;
    }
    private static Path resolveRealPath(Path path) {
        for (;;) {
            try {
                return path.toRealPath();
            } catch (IOException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
}
