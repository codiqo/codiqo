package io.codiqo.llm;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.eclipse.jgit.patch.FormatError;
import org.eclipse.jgit.patch.Patch;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.EffectiveLineParser;
import io.codiqo.api.diff.EffectiveLineParser.LineKind;
import io.codiqo.api.diff.IneffectiveLineProfile;
import io.codiqo.api.diff.NestedBlockRanges;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CallerModel;
import io.codiqo.client.model.CloneLocationModel;
import io.codiqo.client.model.CloneModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CodeUnitModel.OperationEnum;
import io.codiqo.client.model.CoverageModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.DimensionStatsModel;
import io.codiqo.client.model.DriverScalerModel;
import io.codiqo.client.model.DriverScalersModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FullProjectCoverageModel;
import io.codiqo.client.model.JavaInfoModel;
import io.codiqo.client.model.LineCoverageModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.llm.lang.LanguageCapabilities;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CallerInfo;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import io.codiqo.llm.schema.LlmScoringRequest.ComplexityMetrics;
import io.codiqo.llm.schema.LlmScoringRequest.CoverageInfo;
import io.codiqo.llm.schema.LlmScoringRequest.DiagnosticInfo;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
public class SubmissionToRequestMapper implements Function<AnalysisSubmissionModel, LlmScoringRequest> {
    private static final String DEV_NULL = "/dev/null";
    private static final EnumSet<SymbolKindModel> METHOD_OR_CONSTRUCTOR = EnumSet.of(SymbolKindModel.METHOD, SymbolKindModel.CONSTRUCTOR);
    private static final int MAX_UNCOVERED_CHANGED_LINES = 20;
    private static final EnumSet<LineCoverageModel.StatusEnum> COVERED_LINE_STATUSES =
            EnumSet.of(LineCoverageModel.StatusEnum.COVERED, LineCoverageModel.StatusEnum.FULLY_COVERED);
    private static final EnumSet<LineCoverageModel.StatusEnum> PARTIAL_LINE_STATUSES =
            EnumSet.of(LineCoverageModel.StatusEnum.PARTIAL, LineCoverageModel.StatusEnum.PARTIALLY_COVERED);
    private static final EnumSet<LineCoverageModel.StatusEnum> MISSED_LINE_STATUSES =
            EnumSet.of(LineCoverageModel.StatusEnum.MISSED, LineCoverageModel.StatusEnum.NOT_COVERED);

    private final RunArgs args;

    @Override
    public LlmScoringRequest apply(AnalysisSubmissionModel submission) {
        List<FileChangeModel> files = submission.getFiles();
        FileContext fileContext = buildFileContext(files);
        DriverScalers scalers = extractScalers(submission);
        List<String> branches = submission.getCommit().getBranches();
        List<CodeBlockChange> codeBlockChanges = mapCodeBlockChanges(files, fileContext);

        return LlmScoringRequest.builder()
                .commitHash(submission.getCommit().getSha())
                .commitMessage(submission.getCommit().getMessage())
                .author(submission.getCommit().getAuthor())
                .timestamp(submission.getCommit().getTimestamp().toString())
                .branch(CollectionUtils.isNotEmpty(branches) ? branches.iterator().next() : null)
                .revertCommit(Boolean.TRUE.equals(submission.getCommit().getIsRevert()))
                .revertedCommitId(submission.getCommit().getRevertedCommitId())
                .repository(submission.getProject().getCode())
                .changeSummary(mapChangeSummary(files, codeBlockChanges))
                .fileChanges(mapFileChanges(files))
                .codeBlockChanges(codeBlockChanges)
                .coverage(mapCoverage(submission))
                .complexity(mapComplexityMetrics(files))
                .duplication(mapDuplication(submission.getDuplication(), fileContext))
                .methodScalerProd(scalers.methodProd())
                .methodScalerTest(scalers.methodTest())
                .constructorScalerProd(scalers.ctorProd())
                .constructorScalerTest(scalers.ctorTest())
                .build();
    }
    private static DriverScalers extractScalers(AnalysisSubmissionModel submission) {
        DriverScalers toReturn = new DriverScalers();
        if (Objects.isNull(submission.getProjectMetrics()) || Objects.isNull(submission.getProjectMetrics().getDriverScalers())) {
            return toReturn;
        }
        DriverScalersModel model = submission.getProjectMetrics().getDriverScalers();
        toReturn.methodProd = toScaler(model.getMethodScalerProd());
        toReturn.methodTest = toScaler(model.getMethodScalerTest());
        toReturn.ctorProd = toScaler(model.getConstructorScalerProd());
        toReturn.ctorTest = toScaler(model.getConstructorScalerTest());
        return toReturn;
    }
    private static DriverScaler toScaler(DriverScalerModel model) {
        if (Objects.isNull(model) || Objects.isNull(model.getPopulation()) || model.getPopulation() == 0) {
            return DriverScaler.EMPTY;
        }
        return DriverScaler.fromPersisted(
                model.getPopulation(),
                statsOf(model.getLines()),
                statsOf(model.getNcss()),
                statsOf(model.getInvocations()));
    }
    private static DriverScaler.DimensionStats statsOf(DimensionStatsModel model) {
        if (Objects.isNull(model)) {
            return DriverScaler.DimensionStats.ZERO;
        }
        return DriverScaler.DimensionStats.builder()
                .min(Optional.ofNullable(model.getMin()).orElse(0))
                .p50(Optional.ofNullable(model.getP50()).orElse(0.0))
                .p75(Optional.ofNullable(model.getP75()).orElse(0.0))
                .p90(Optional.ofNullable(model.getP90()).orElse(0.0))
                .p95(Optional.ofNullable(model.getP95()).orElse(0.0))
                .max(Optional.ofNullable(model.getMax()).orElse(0))
                .build();
    }

    @lombok.Getter
    @Accessors(fluent = true)
    private static final class DriverScalers {
        private DriverScaler methodProd = DriverScaler.EMPTY;
        private DriverScaler methodTest = DriverScaler.EMPTY;
        private DriverScaler ctorProd = DriverScaler.EMPTY;
        private DriverScaler ctorTest = DriverScaler.EMPTY;
    }

    private LlmScoringRequest.DuplicationInfo mapDuplication(DuplicationReportModel duplication, FileContext fileContext) {
        LlmScoringRequest.DuplicationInfo.DuplicationInfoBuilder builder = LlmScoringRequest.DuplicationInfo.builder()
                .duplicatedPercentage(Optional.ofNullable(duplication.getDuplicatedPercentage()).orElse(0.0))
                .totalDuplicatedLines(Optional.ofNullable(duplication.getTotalDuplicatedLines()).orElse(0))
                .totalDuplicatedTokens(Optional.ofNullable(duplication.getTotalDuplicatedTokens()).orElse(0))
                .minimumTokens(Optional.ofNullable(duplication.getMinimumTokens()).orElse(0));
        if (CollectionUtils.isNotEmpty(duplication.getClones())) {
            builder.cloneDetails(duplication.getClones().stream()
                    .map(clone -> mapCloneDetail(clone, fileContext))
                    .collect(Collectors.toList()));
        }
        if (CollectionUtils.isNotEmpty(duplication.getClonesFromExisting())) {
            builder.clonesFromExisting(duplication.getClonesFromExisting().stream()
                    .map(clone -> LlmScoringRequest.DuplicationInfo.CloneFromExisting.builder()
                            .affectedSignature(clone.getAffectedSignature())
                            .sourceSignatures(Optional.ofNullable(clone.getSourceSignatures()).orElse(Lists.newArrayList()))
                            .build())
                    .collect(Collectors.toList()));
        }
        if (CollectionUtils.isNotEmpty(duplication.getNewClones())) {
            builder.newClones(duplication.getNewClones().stream()
                    .map(clone -> LlmScoringRequest.DuplicationInfo.NewCloneGroup.builder()
                            .memberSignatures(Optional.ofNullable(clone.getMemberSignatures()).orElse(Lists.newArrayList()))
                            .build())
                    .collect(Collectors.toList()));
        }
        return builder.build();
    }
    private LlmScoringRequest.DuplicationInfo.CloneDetail mapCloneDetail(CloneModel clone, FileContext fileContext) {
        List<CloneLocationModel> locations = clone.getLocations();
        boolean selfDuplication = false;
        if (CollectionUtils.isNotEmpty(locations) && locations.size() >= 2) {
            Set<String> uniqueSignatures = locations.stream()
                    .map(CloneLocationModel::getCodeUnitSignature)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            selfDuplication = uniqueSignatures.size() == 1;
        }

        List<LlmScoringRequest.DuplicationInfo.CloneLocation> mappedLocations = Lists.newArrayList();
        if (CollectionUtils.isNotEmpty(locations)) {
            mappedLocations = locations.stream().map(loc -> mapCloneLocation(loc, fileContext)).collect(Collectors.toList());
        }

        boolean allTestCode = CollectionUtils.isNotEmpty(mappedLocations)
                && mappedLocations.stream().allMatch(LlmScoringRequest.DuplicationInfo.CloneLocation::isTestCode);
        boolean introducedInCommit = mappedLocations.stream().anyMatch(LlmScoringRequest.DuplicationInfo.CloneLocation::isIntroducedInCommit);

        return LlmScoringRequest.DuplicationInfo.CloneDetail.builder()
                .tokenCount(Optional.ofNullable(clone.getTokenCount()).orElse(0))
                .lineCount(Optional.ofNullable(clone.getLineCount()).orElse(0))
                .crossFile(Boolean.TRUE.equals(clone.getIsCrossFile()))
                .selfDuplication(selfDuplication)
                .allTestCode(allTestCode)
                .introducedInCommit(introducedInCommit)
                .locations(mappedLocations)
                .build();
    }
    private LlmScoringRequest.DuplicationInfo.CloneLocation mapCloneLocation(CloneLocationModel loc, FileContext fileContext) {
        int startLine = 0;
        int endLine = 0;
        if (Objects.nonNull(loc.getLocation())) {
            startLine = Optional.ofNullable(loc.getLocation().getStartLine()).orElse(0);
            endLine = Optional.ofNullable(loc.getLocation().getEndLine()).orElse(0);
        }
        String filePath = loc.getPath();
        boolean isTestCode = fileContext.testFiles().contains(filePath);
        int linesOverlapping = 0;
        int totalCloneLines = Math.max(1, endLine - startLine + 1);
        Set<Integer> addedLines = fileContext.addedLinesByFile().get(filePath);
        if (Objects.nonNull(addedLines) && startLine > 0 && endLine >= startLine) {
            for (int line = startLine; line <= endLine; line++) {
                if (addedLines.contains(line)) {
                    linesOverlapping++;
                }
            }
        }
        double overlapRatio = (double) linesOverlapping / totalCloneLines;
        boolean introducedInCommit = overlapRatio > args.getCpdIntroducedThreshold();
        return LlmScoringRequest.DuplicationInfo.CloneLocation.builder()
                .file(filePath)
                .startLine(startLine)
                .endLine(endLine)
                .methodSignature(loc.getCodeUnitSignature())
                .sourceSlice(loc.getSourceSlice())
                .testCode(isTestCode)
                .introducedInCommit(introducedInCommit)
                .linesOverlappingDiff(linesOverlapping)
                .build();
    }
    private CoverageInfo mapCoverage(AnalysisSubmissionModel submission) {
        CoverageInfo.CoverageInfoBuilder builder = CoverageInfo.builder();
        mapProjectCoverage(submission.getFullProjectCoverage(), builder);
        mapFileCoverage(submission.getFiles(), builder);
        return builder.build();
    }
    private void mapFileCoverage(List<FileChangeModel> files, CoverageInfo.CoverageInfoBuilder builder) {
        int totalCoveredLines = 0;
        int totalLines = 0;
        int totalCoveredBranches = 0;
        int totalBranches = 0;
        Map<String, CoverageInfo.MethodCoverage> methodCoverages = Maps.newHashMap();
        List<CoverageInfo.UncoveredPath> uncoveredPaths = Lists.newArrayList();
        for (FileChangeModel file : files) {
            if (CollectionUtils.isEmpty(file.getCodeUnits())) {
                continue;
            }
            for (CodeUnitModel codeUnit : file.getCodeUnits()) {
                CoverageModel coverage = codeUnit.getCoverage();
                if (Objects.isNull(coverage)) {
                    continue;
                }
                int coveredLines = Optional.ofNullable(coverage.getCoveredLines()).orElse(BigDecimal.ZERO.intValue());
                int missedLines = Optional.ofNullable(coverage.getMissedLines()).orElse(BigDecimal.ZERO.intValue());
                int coveredBranches = Optional.ofNullable(coverage.getCoveredBranches()).orElse(BigDecimal.ZERO.intValue());
                int missedBranches = Optional.ofNullable(coverage.getMissedBranches()).orElse(BigDecimal.ZERO.intValue());
                totalCoveredLines += coveredLines;
                totalLines += coveredLines + missedLines;
                totalCoveredBranches += coveredBranches;
                totalBranches += coveredBranches + missedBranches;
                double linePercent = Optional.ofNullable(coverage.getLinePercent()).orElse(BigDecimal.ZERO.doubleValue());
                double branchPercent = Optional.ofNullable(coverage.getBranchPercent()).orElse(BigDecimal.ZERO.doubleValue());
                methodCoverages.put(codeUnit.getName(), CoverageInfo.MethodCoverage.builder()
                        .methodName(codeUnit.getName())
                        .lineCoverage(linePercent)
                        .branchCoverage(branchPercent)
                        .coveredLines(coveredLines)
                        .missedLines(missedLines)
                        .coveredBranches(coveredBranches)
                        .missedBranches(missedBranches)
                        .build());
                if (linePercent < args.getCoverageLowThreshold()) {
                    uncoveredPaths.add(buildUncoveredPath(file, codeUnit, linePercent));
                }
            }
        }
        double changedLineCoverage = totalLines > 0 ? totalCoveredLines * 100.0 / totalLines : BigDecimal.ZERO.doubleValue();
        double changedBranchCoverage = totalBranches > 0 ? totalCoveredBranches * 100.0 / totalBranches : BigDecimal.ZERO.doubleValue();
        builder.changedLineCoverage(changedLineCoverage)
                .changedBranchCoverage(changedBranchCoverage)
                .methodCoverages(methodCoverages)
                .uncoveredPaths(uncoveredPaths);
    }
    private CoverageInfo.UncoveredPath buildUncoveredPath(FileChangeModel file, CodeUnitModel codeUnit, double linePercent) {
        return CoverageInfo.UncoveredPath.builder()
                .file(file.getPath())
                .method(codeUnit.getName())
                .startLine(codeUnit.getLocation().getStartLine())
                .endLine(codeUnit.getLocation().getEndLine())
                .riskLevel(classifyRiskLevel(linePercent))
                .build();
    }
    private LlmScoringRequest.CoverageInfo.RiskLevel classifyRiskLevel(double linePercent) {
        if (linePercent < args.getCoverageCriticalThreshold()) {
            return LlmScoringRequest.CoverageInfo.RiskLevel.CRITICAL;
        }
        if (linePercent < args.getCoverageHighThreshold()) {
            return LlmScoringRequest.CoverageInfo.RiskLevel.HIGH;
        }
        return LlmScoringRequest.CoverageInfo.RiskLevel.MEDIUM;
    }
    private ComplexityMetrics mapComplexityMetrics(List<FileChangeModel> files) {
        int totalCyclomatic = 0;
        int totalCognitive = 0;
        int maxComplexity = 0;
        int newHighComplexity = 0;
        int modifiedHighComplexity = 0;
        List<Integer> perMethodCyclomatic = Lists.newArrayList();

        for (FileChangeModel file : files) {
            if (CollectionUtils.isEmpty(file.getCodeUnits())) {
                continue;
            }
            for (CodeUnitModel codeUnit : file.getCodeUnits()) {
                if (isMethodOrConstructor(codeUnit.getKind())) {
                    MetricsModel metrics = codeUnit.getMetrics();
                    if (Objects.isNull(metrics)) {
                        continue;
                    }
                    int cyclomatic = Optional.ofNullable(metrics.getCyclomaticComplexity()).orElse(BigDecimal.ZERO.intValue());
                    int cognitive = Optional.ofNullable(metrics.getCognitiveComplexity()).orElse(BigDecimal.ZERO.intValue());
                    totalCyclomatic += cyclomatic;
                    totalCognitive += cognitive;
                    maxComplexity = Math.max(maxComplexity, cyclomatic);
                    perMethodCyclomatic.add(cyclomatic);
                    if (cyclomatic > args.getHighComplexityThreshold()) {
                        OperationEnum operation = codeUnit.getOperation();
                        if (operation == OperationEnum.NEW) {
                            newHighComplexity++;
                        } else if (operation == OperationEnum.MODIFY) {
                            modifiedHighComplexity++;
                        }
                    }
                }
            }
        }

        double quantileLevel = args.getStatsQuantile() * 100.0;
        double complexityQuantile = 0;
        if (CollectionUtils.isNotEmpty(perMethodCyclomatic)) {
            double[] values = perMethodCyclomatic.stream().mapToDouble(Integer::doubleValue).toArray();
            complexityQuantile = new Percentile().evaluate(values, quantileLevel);
        }

        return ComplexityMetrics.builder()
                .totalCyclomaticComplexity(totalCyclomatic)
                .totalCognitiveComplexity(totalCognitive)
                .maxMethodComplexity(maxComplexity)
                .methodComplexityQuantile(complexityQuantile)
                .newHighComplexityMethods(newHighComplexity)
                .modifiedHighComplexityMethods(modifiedHighComplexity)
                .complexityThreshold(args.getHighComplexityThreshold())
                .build();
    }
    private static List<FileChange> mapFileChanges(List<FileChangeModel> files) {
        return files.stream().map(SubmissionToRequestMapper::mapFileChange).collect(Collectors.toList());
    }
    private static FileChange mapFileChange(FileChangeModel file) {
        IneffectiveLineProfile profile = LanguageCapabilities.profileFor(file);
        DiffStats stats = DiffStats.fromPatch(file.getDiff(), profile);
        return FileChange.builder()
                .path(resolveEffectivePath(file))
                .changeType(mapFileChangeType(file.getChangeType()))
                .diff(file.getDiff())
                .isTest(Boolean.TRUE.equals(file.getIsTest()))
                .isConfig(LanguageCapabilities.isLineCountScored(file))
                .language(mapLanguage(file))
                .linesAdded(stats.effectiveAdded())
                .linesDeleted(stats.effectiveDeleted())
                .lineProfile(profile)
                .linesJustificationRequired(LanguageCapabilities.requiresDiffClassification(file))
                .build();
    }
    private static List<CodeBlockChange> mapCodeBlockChanges(List<FileChangeModel> files, FileContext fileContext) {
        List<CodeBlockChange> toReturn = Lists.newArrayList();
        for (FileChangeModel file : files) {
            if (CollectionUtils.isEmpty(file.getCodeUnits())) {
                continue;
            }
            DiffStats stats = DiffStats.fromPatch(file.getDiff(), LanguageCapabilities.profileFor(file));
            if (!stats.effectiveChanges()) {
                continue;
            }
            for (CodeUnitModel codeUnit : file.getCodeUnits()) {
                if (isMethodOrConstructor(codeUnit.getKind())) {
                    if (Boolean.TRUE.equals(codeUnit.getIsTrivial())) {
                        continue;
                    }
                    CodeBlockChange block = mapCodeBlockChange(file, codeUnit, fileContext);
                    // a modify block whose only changed lines belong to nested blocks carries no effective change of its own
                    if (BooleanUtils.and(new boolean[] { block.isModify(), block.getTotalLinesChanged() == 0, block.getEffectiveInvocationsChanged() == 0 })) {
                        continue;
                    }
                    toReturn.add(block);
                }
            }
        }
        return toReturn;
    }
    private static CodeBlockChange mapCodeBlockChange(FileChangeModel file, CodeUnitModel codeUnit, FileContext fileContext) {
        int startLine = codeUnit.getLocation().getStartLine();
        int endLine = codeUnit.getLocation().getEndLine();
        int bodyStartLine = Optional.ofNullable(codeUnit.getLocation().getBodyStartLine()).orElse(0);
        int bodyEndLine = Optional.ofNullable(codeUnit.getLocation().getBodyEndLine()).orElse(0);
        String filePath = file.getPath();
        boolean requiresLineFiltering = LanguageCapabilities.requiresLineFiltering(file);

        CodeBlockChange.CodeBlockChangeBuilder builder = CodeBlockChange.builder()
                .name(codeUnit.getName())
                .signature(codeUnit.getSignature())
                .file(resolveEffectivePath(file))
                .className(extractClassName(codeUnit))
                .operation(mapOperation(codeUnit.getOperation()))
                .isConstructor(codeUnit.getKind() == SymbolKindModel.CONSTRUCTOR)
                .isTest(Boolean.TRUE.equals(file.getIsTest()))
                .startLine(startLine)
                .endLine(endLine)
                .bodyStartLine(bodyStartLine)
                .bodyEndLine(bodyEndLine);

        mapMetrics(codeUnit.getMetrics(), builder);
        mapCallers(codeUnit.getCallers(), builder);
        mapDiagnostics(codeUnit.getDiagnostics(), filePath, fileContext, builder);
        mapChangeMetrics(codeUnit, filePath, bodyStartLine, bodyEndLine, startLine, endLine, requiresLineFiltering, fileContext, builder);

        return builder.build();
    }
    private static void mapChangeMetrics(CodeUnitModel codeUnit, String filePath, int bodyStartLine, int bodyEndLine,
            int startLine, int endLine, boolean requiresLineFiltering, FileContext fileContext, CodeBlockChange.CodeBlockChangeBuilder builder) {
        MetricsModel metrics = codeUnit.getMetrics();

        int nonCommentCodeLines = Optional.ofNullable(metrics.getNonCommentCodeLines())
                .orElse(Optional.ofNullable(metrics.getCodeLines()).orElse(0));
        builder.nonCommentCodeLines(nonCommentCodeLines);
        builder.commentLines(Optional.ofNullable(metrics.getCommentLines()).orElse(0));
        builder.declarationCodeLines(Optional.ofNullable(metrics.getDeclarationCodeLines()).orElse(0));
        builder.bodyCodeLines(Optional.ofNullable(metrics.getBodyCodeLines()).orElse(nonCommentCodeLines));

        if (codeUnit.getOperation() != OperationEnum.MODIFY) {
            return;
        }

        int rangeStart = bodyStartLine > 0 ? bodyStartLine : startLine;
        int rangeEnd = bodyEndLine >= bodyStartLine && bodyEndLine > 0 ? bodyEndLine : endLine;
        List<int[]> nestedRanges = NestedBlockRanges.nestedWithin(rangeStart, rangeEnd,
                fileContext.blockRangesByFile().getOrDefault(filePath, Collections.emptyList()));

        int effectiveLinesChanged = Optional.ofNullable(codeUnit.getEffectiveLinesChanged()).orElse(0);
        if (effectiveLinesChanged <= 0) {
            Set<Integer> effectiveAdded = fileContext.effectiveAddedLinesByFile().get(filePath);
            if (Objects.nonNull(effectiveAdded) && rangeStart > 0 && rangeEnd >= rangeStart) {
                effectiveLinesChanged = countEffectiveAddedInRange(effectiveAdded, rangeStart, rangeEnd, nestedRanges);
            }
        }

        int effectiveLinesDeleted = 0;
        Map<Integer, Integer> deletionAnchors = fileContext.effectiveDeletionAnchorsByFile().get(filePath);
        if (Objects.nonNull(deletionAnchors) && rangeStart > 0 && rangeEnd >= rangeStart) {
            effectiveLinesDeleted = countEffectiveDeletionsInRange(deletionAnchors, rangeStart, rangeEnd, nestedRanges);
        }

        builder.linesAdded(effectiveLinesChanged);
        builder.totalLinesChanged(effectiveLinesChanged + effectiveLinesDeleted);
        builder.effectiveInvocationsChanged(Optional.ofNullable(codeUnit.getEffectiveInvocationsChanged()).orElse(0));

        mapChangedLineCoverage(codeUnit, filePath, fileContext, builder);
    }
    private static void mapChangedLineCoverage(CodeUnitModel codeUnit, String filePath,
            FileContext fileContext, CodeBlockChange.CodeBlockChangeBuilder builder) {
        CoverageModel coverage = codeUnit.getCoverage();
        Set<Integer> changedLines = fileContext.addedLinesByFile().get(filePath);
        boolean hasCoverageLines = Objects.nonNull(coverage) && CollectionUtils.isNotEmpty(coverage.getLines());
        if (BooleanUtils.and(new boolean[] { hasCoverageLines, CollectionUtils.isNotEmpty(changedLines) })) {
            int covered = 0;
            int missed = 0;
            int partial = 0;
            List<Integer> uncoveredChangedLines = Lists.newArrayList();
            for (LineCoverageModel line : coverage.getLines()) {
                if (changedLines.contains(line.getLine())) {
                    LineCoverageModel.StatusEnum status = line.getStatus();
                    if (COVERED_LINE_STATUSES.contains(status)) {
                        covered++;
                    } else if (PARTIAL_LINE_STATUSES.contains(status)) {
                        partial++;
                    } else if (MISSED_LINE_STATUSES.contains(status)) {
                        missed++;
                        if (uncoveredChangedLines.size() < MAX_UNCOVERED_CHANGED_LINES) {
                            uncoveredChangedLines.add(line.getLine());
                        }
                    }
                }
            }

            builder.changedLinesCovered(covered);
            builder.changedLinesMissed(missed);
            builder.changedLinesPartiallyCovered(partial);
            builder.uncoveredChangedLines(uncoveredChangedLines);
        }
    }
    private static void mapCallers(List<CallerModel> callers, CodeBlockChange.CodeBlockChangeBuilder builder) {
        builder.callers(callers.stream().map(SubmissionToRequestMapper::mapCaller).collect(Collectors.toList()));
    }
    private static CallerInfo mapCaller(CallerModel caller) {
        return CallerInfo.builder()
                .callerMethod(caller.getName())
                .file(caller.getPath())
                .line(caller.getLocation().getStartLine())
                .isTestCaller(Boolean.TRUE.equals(caller.getIsTest()))
                .signature(caller.getSignature())
                .kind(Objects.nonNull(caller.getKind()) ? caller.getKind().getValue() : null)
                .symbol(caller.getSymbol())
                .isDeprecated(Boolean.TRUE.equals(caller.getIsDeprecated()))
                .callSiteCount(CollectionUtils.size(caller.getCallSites()))
                .callerBody(caller.getCallerBody())
                .build();
    }
    private static ChangeSummary mapChangeSummary(List<FileChangeModel> files, List<CodeBlockChange> codeBlocks) {
        int linesAdded = 0;
        int linesDeleted = 0;
        int testLinesAdded = 0;
        int testLinesDeleted = 0;
        int codeBlocksAdded = 0;
        int codeBlocksModified = 0;
        int codeBlocksDeleted = 0;
        int testCodeBlocksAdded = 0;
        int testCodeBlocksModified = 0;
        int classesAdded = 0;
        int classesModified = 0;
        int testClassesAdded = 0;
        int testClassesModified = 0;
        int filesWithChanges = 0;
        int testFilesWithChanges = 0;
        Set<String> packagesAffected = Sets.newHashSet();
        for (FileChangeModel file : files) {
            boolean isTest = Boolean.TRUE.equals(file.getIsTest());
            DiffStats stats = DiffStats.fromPatch(file.getDiff(), LanguageCapabilities.profileFor(file));
            if (!stats.effectiveChanges()) {
                continue;
            }

            /**
             * files without code units (config, resources) always count their lines
             */
            if (CollectionUtils.isEmpty(file.getCodeUnits())) {
                linesAdded += stats.effectiveAdded();
                linesDeleted += stats.effectiveDeleted();

                /**
                 * line-count-scored files (pom.xml, proto) contribute scored effort, so they count as changed files
                 */
                boolean lineCountScored = LanguageCapabilities.isLineCountScored(file);
                if (lineCountScored) {
                    filesWithChanges++;
                }
                if (isTest) {
                    testLinesAdded += stats.effectiveAdded();
                    testLinesDeleted += stats.effectiveDeleted();
                    if (lineCountScored) {
                        testFilesWithChanges++;
                    }
                }
                continue;
            }

            boolean fileHasCodeBlockChange = false;
            boolean fileHasClassChange = false;
            for (CodeUnitModel codeUnit : file.getCodeUnits()) {
                SymbolKindModel kind = codeUnit.getKind();
                OperationEnum operation = codeUnit.getOperation();
                if (isMethodOrConstructor(kind)) {
                    if (Boolean.TRUE.equals(codeUnit.getIsTrivial())) {
                        continue;
                    }
                    fileHasCodeBlockChange = true;
                } else if (kind == SymbolKindModel.propertyClass) {
                    if (operation == OperationEnum.NEW) {
                        fileHasClassChange = true;
                        classesAdded++;
                        if (isTest) {
                            testClassesAdded++;
                        }
                    } else if (operation == OperationEnum.MODIFY) {
                        fileHasClassChange = true;
                        classesModified++;
                        if (isTest) {
                            testClassesModified++;
                        }
                    }
                }
                extractPackageName(codeUnit).ifPresent(packagesAffected::add);
            }

            if (BooleanUtils.or(new boolean[] { fileHasCodeBlockChange, fileHasClassChange })) {
                linesAdded += stats.effectiveAdded();
                linesDeleted += stats.effectiveDeleted();
                filesWithChanges++;
                if (isTest) {
                    testLinesAdded += stats.effectiveAdded();
                    testLinesDeleted += stats.effectiveDeleted();
                    testFilesWithChanges++;
                }
            }
        }

        for (CodeBlockChange block : codeBlocks) {
            if (block.isNew()) {
                codeBlocksAdded++;
                if (block.isTest()) {
                    testCodeBlocksAdded++;
                }
            } else if (block.isModify()) {
                codeBlocksModified++;
                if (block.isTest()) {
                    testCodeBlocksModified++;
                }
            } else if (block.isDelete()) {
                codeBlocksDeleted++;
            }
        }

        return ChangeSummary.builder()
                .totalFilesChanged(filesWithChanges)
                .totalLinesChanged(linesAdded + linesDeleted)
                .linesAdded(linesAdded)
                .linesDeleted(linesDeleted)
                .codeBlocksAdded(codeBlocksAdded)
                .codeBlocksModified(codeBlocksModified)
                .codeBlocksDeleted(codeBlocksDeleted)
                .classesAdded(classesAdded)
                .classesModified(classesModified)
                .testLinesChanged(testLinesAdded + testLinesDeleted)
                .testCodeBlocksAdded(testCodeBlocksAdded)
                .testCodeBlocksModified(testCodeBlocksModified)
                .testClassesAdded(testClassesAdded)
                .testClassesModified(testClassesModified)
                .testFilesChanged(testFilesWithChanges)
                .packagesAffected(Lists.newArrayList(packagesAffected))
                .build();
    }
    private static FileContext buildFileContext(List<FileChangeModel> files) {
        Set<String> testFiles = Sets.newHashSet();
        Map<String, Set<Integer>> addedByFile = Maps.newHashMap();
        Map<String, Set<Integer>> effectiveAddedByFile = Maps.newHashMap();
        Map<String, Map<Integer, Integer>> effectiveDeletionAnchorsByFile = Maps.newHashMap();
        Map<String, List<int[]>> blockRangesByFile = Maps.newHashMap();
        for (FileChangeModel file : files) {
            String path = file.getPath();
            if (Boolean.TRUE.equals(file.getIsTest())) {
                testFiles.add(path);
            }

            List<int[]> blockRanges = collectBlockRanges(file.getCodeUnits());
            if (CollectionUtils.isNotEmpty(blockRanges)) {
                blockRangesByFile.put(path, blockRanges);
            }
            if (Objects.nonNull(file.getDiff())) {
                IneffectiveLineProfile profile = LanguageCapabilities.profileFor(file);
                Predicate<String> addedIneffective = profile.commentFilter();
                Predicate<String> deletedIneffective = profile.commentOrImportFilter();

                Set<Integer> addedLines = EffectiveLineParser.parseAddedLines(file.getDiff());
                if (CollectionUtils.isNotEmpty(addedLines)) {
                    addedByFile.put(path, addedLines);
                }

                Set<Integer> effectiveAdded = EffectiveLineParser.parseEffectiveAddedLines(file.getDiff(), addedIneffective);
                if (CollectionUtils.isNotEmpty(effectiveAdded)) {
                    effectiveAddedByFile.put(path, effectiveAdded);
                }

                Map<Integer, Integer> deletionAnchors = EffectiveLineParser.parseEffectiveDeletionAnchors(file.getDiff(), deletedIneffective);
                if (MapUtils.isNotEmpty(deletionAnchors)) {
                    effectiveDeletionAnchorsByFile.put(path, deletionAnchors);
                }
            }
        }
        return new FileContext(testFiles, addedByFile, effectiveAddedByFile, effectiveDeletionAnchorsByFile, blockRangesByFile);
    }
    private static List<int[]> collectBlockRanges(List<CodeUnitModel> codeUnits) {
        List<int[]> toReturn = Lists.newArrayList();
        for (CodeUnitModel codeUnit : CollectionUtils.emptyIfNull(codeUnits)) {
            if (Boolean.TRUE.equals(codeUnit.getIsTrivial())) {
                continue;
            }
            if (isMethodOrConstructor(codeUnit.getKind())) {
                LocationModel location = codeUnit.getLocation();
                if (Objects.nonNull(location) && Objects.nonNull(location.getStartLine()) && Objects.nonNull(location.getEndLine())) {
                    toReturn.add(new int[] { location.getStartLine(), location.getEndLine() });
                }
            }
        }
        return toReturn;
    }
    private static int countEffectiveAddedInRange(Set<Integer> effectiveAdded, int startLine, int endLine, List<int[]> nestedRanges) {
        int count = 0;
        for (int line = startLine; line <= endLine; line++) {
            if (effectiveAdded.contains(line) && !NestedBlockRanges.coversLine(nestedRanges, line)) {
                count++;
            }
        }
        return count;
    }
    private static int countEffectiveDeletionsInRange(Map<Integer, Integer> anchors, int startLine, int endLine, List<int[]> nestedRanges) {
        int count = 0;
        for (Map.Entry<Integer, Integer> entry : anchors.entrySet()) {
            int anchor = entry.getKey();
            if (anchor >= startLine && anchor <= endLine + 1 && !NestedBlockRanges.coversAnchor(nestedRanges, anchor)) {
                count += entry.getValue();
            }
        }
        return count;
    }
    private static void mapDiagnostics(List<DiagnosticModel> diagnostics, String filePath, FileContext fileContext,
            CodeBlockChange.CodeBlockChangeBuilder builder) {
        Set<Integer> addedLines = fileContext.addedLinesByFile().get(filePath);
        builder.diagnostics(diagnostics.stream()
                .map(diag -> mapDiagnostic(diag, addedLines))
                .collect(Collectors.toList()));
    }
    private static DiagnosticInfo mapDiagnostic(DiagnosticModel diag, Set<Integer> addedLines) {
        int startLine = 0;
        int endLine = 0;
        if (Objects.nonNull(diag.getLocation())) {
            startLine = diag.getLocation().getStartLine();
            endLine = diag.getLocation().getEndLine();
        }
        boolean introducedInCommit = false;
        if (Objects.nonNull(addedLines) && startLine > 0) {
            for (int line = startLine; line <= Math.max(startLine, endLine); line++) {
                if (addedLines.contains(line)) {
                    introducedInCommit = true;
                    break;
                }
            }
        }
        return DiagnosticInfo.builder()
                .tool(Objects.nonNull(diag.getTool()) ? diag.getTool().getValue() : null)
                .ruleId(diag.getRuleId())
                .message(diag.getMessage())
                .category(diag.getCategory())
                .severity(mapDiagnosticSeverity(diag.getSeverity()))
                .startLine(startLine)
                .endLine(endLine)
                .introducedInCommit(introducedInCommit)
                .build();
    }
    private static LlmScoringRequest.DiagnosticSeverity mapDiagnosticSeverity(DiagnosticModel.SeverityEnum severity) {
        if (Objects.isNull(severity)) {
            return null;
        }
        return switch (severity) {
            case ERROR -> LlmScoringRequest.DiagnosticSeverity.ERROR;
            case WARNING -> LlmScoringRequest.DiagnosticSeverity.WARNING;
            case INFO -> LlmScoringRequest.DiagnosticSeverity.INFO;
            case NOTE -> LlmScoringRequest.DiagnosticSeverity.NOTE;
            case NONE -> LlmScoringRequest.DiagnosticSeverity.NONE;
            default -> throw new IllegalArgumentException("Unknown diagnostic severity: " + severity);
        };
    }
    private static void mapMetrics(MetricsModel metrics, CodeBlockChange.CodeBlockChangeBuilder builder) {
        builder.nonCommentCodeStatements(Optional.ofNullable(metrics.getNonCommentCodeStatements())
                .orElse(Optional.ofNullable(metrics.getCodeLines()).orElse(0)));
        builder.directInvocationCount(Optional.ofNullable(metrics.getDirectInvocationCount()).orElse(0));
        builder.cyclomaticComplexity(Optional.ofNullable(metrics.getCyclomaticComplexity()).orElse(BigDecimal.ZERO.intValue()));
        builder.cognitiveComplexity(Optional.ofNullable(metrics.getCognitiveComplexity()).orElse(BigDecimal.ZERO.intValue()));
        builder.parameterCount(Optional.ofNullable(metrics.getParameterCount()).orElse(BigDecimal.ZERO.intValue()));
        builder.fanOut(Optional.ofNullable(metrics.getFanOut()).orElse(BigDecimal.ZERO.intValue()));
        builder.npath(Optional.ofNullable(metrics.getNpath()).orElse(BigDecimal.ZERO.longValue()));
    }
    private static void mapProjectCoverage(FullProjectCoverageModel projectCoverage, CoverageInfo.CoverageInfoBuilder builder) {
        builder.projectLineCoverage(Optional.ofNullable(projectCoverage.getLinePercentage()).orElse(BigDecimal.ZERO.doubleValue()));
        builder.projectBranchCoverage(Optional.ofNullable(projectCoverage.getBranchPercentage()).orElse(BigDecimal.ZERO.doubleValue()));
    }
    private static String resolveEffectivePath(FileChangeModel file) {
        String path = file.getPath();
        if (BooleanUtils.or(new boolean[] { DEV_NULL.equals(path), StringUtils.isEmpty(path) })) {
            String previousPath = file.getPreviousPath();
            if (StringUtils.isNotEmpty(previousPath)) {
                return previousPath;
            }
        }
        return path;
    }
    private static boolean isMethodOrConstructor(SymbolKindModel kind) {
        return METHOD_OR_CONSTRUCTOR.contains(kind);
    }
    private static Optional<String> extractPackageName(CodeUnitModel codeUnit) {
        return Optional.ofNullable(codeUnit.getJavaInfo()).map(JavaInfoModel::getPackageName);
    }
    private static String extractClassName(CodeUnitModel codeUnit) {
        JavaInfoModel java = codeUnit.getJavaInfo();
        if (Objects.nonNull(java) && Objects.nonNull(java.getClassName())) {
            return java.getClassName();
        }
        return extractClassNameFromSignature(codeUnit.getSignature());
    }
    private static String extractClassNameFromSignature(String signature) {
        int lastDot = signature.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String beforeMethod = signature.substring(0, lastDot);
        int classNameStart = Math.max(beforeMethod.lastIndexOf('/'), beforeMethod.lastIndexOf('.'));
        return classNameStart >= 0 ? beforeMethod.substring(classNameStart + 1) : beforeMethod;
    }
    private static String mapLanguage(FileChangeModel file) {
        if (Objects.nonNull(file.getLanguage())) {
            return file.getLanguage().getValue();
        }
        String extension = FilenameUtils.getExtension(file.getPath());
        return StringUtils.isNotEmpty(extension) ? extension : null;
    }
    private static LlmScoringRequest.FileChangeType mapFileChangeType(FileChangeModel.ChangeTypeEnum changeType) {
        return switch (changeType) {
            case ADD, COPY -> LlmScoringRequest.FileChangeType.ADDED;
            case MODIFY -> LlmScoringRequest.FileChangeType.MODIFIED;
            case DELETE -> LlmScoringRequest.FileChangeType.DELETED;
            case RENAME -> LlmScoringRequest.FileChangeType.RENAMED;
        };
    }
    private static LlmScoringRequest.Operation mapOperation(OperationEnum operation) {
        return switch (operation) {
            case NEW -> LlmScoringRequest.Operation.NEW;
            case MODIFY -> LlmScoringRequest.Operation.MODIFY;
            case DELETE -> LlmScoringRequest.Operation.DELETE;
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class DiffStats {
        private final int added;
        private final int deleted;
        private final int importAdded;
        private final int importDeleted;
        private final int commentAdded;
        private final int commentDeleted;
        private final int blankAdded;
        private final int blankDeleted;

        private int effectiveAdded() {
            return added - importAdded - commentAdded - blankAdded;
        }
        private int effectiveDeleted() {
            return deleted - importDeleted - commentDeleted - blankDeleted;
        }
        private boolean effectiveChanges() {
            return BooleanUtils.or(new boolean[] { effectiveAdded() > 0, effectiveDeleted() > 0 });
        }
        private static DiffStats fromPatch(String diff, IneffectiveLineProfile profile) {
            Patch patch = EffectiveLineParser.parsePatch(diff);
            List<FormatError> errors = patch.getErrors().stream()
                    .filter(err -> err.getSeverity() == FormatError.Severity.ERROR)
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(errors)) {
                throw new IllegalArgumentException("failed to parse diff: " + errors);
            }

            MutableInt added = new MutableInt();
            MutableInt deleted = new MutableInt();
            MutableInt blankAdded = new MutableInt();
            MutableInt commentAdded = new MutableInt();
            MutableInt importAdded = new MutableInt();
            MutableInt blankDeleted = new MutableInt();
            MutableInt commentDeleted = new MutableInt();
            MutableInt importDeleted = new MutableInt();

            EffectiveLineParser.walk(patch, (kind, newLine, content) -> {
                if (kind == LineKind.ADDED) {
                    added.increment();
                    categorize(content.trim(), profile, blankAdded, commentAdded, importAdded);
                } else if (kind == LineKind.DELETED) {
                    deleted.increment();
                    categorize(content.trim(), profile, blankDeleted, commentDeleted, importDeleted);
                }
            });

            return new DiffStats(
                    added.intValue(),
                    deleted.intValue(),
                    importAdded.intValue(),
                    importDeleted.intValue(),
                    commentAdded.intValue(),
                    commentDeleted.intValue(),
                    blankAdded.intValue(),
                    blankDeleted.intValue());
        }
        private static void categorize(String trimmed, IneffectiveLineProfile profile, MutableInt blank, MutableInt comment, MutableInt imports) {
            if (trimmed.isEmpty()) {
                blank.increment();
            } else if (profile.isComment(trimmed)) {
                comment.increment();
            } else if (profile.isImport(trimmed)) {
                imports.increment();
            }
        }
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class FileContext {
        private final Set<String> testFiles;
        private final Map<String, Set<Integer>> addedLinesByFile;
        private final Map<String, Set<Integer>> effectiveAddedLinesByFile;
        private final Map<String, Map<Integer, Integer>> effectiveDeletionAnchorsByFile;
        private final Map<String, List<int[]>> blockRangesByFile;
    }
}
