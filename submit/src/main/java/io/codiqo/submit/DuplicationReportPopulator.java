package io.codiqo.submit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;


import io.codiqo.api.DuplicateMark;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.cpd.DuplicationMatch;
import io.codiqo.client.model.CloneFromExistingModel;
import io.codiqo.client.model.CloneLocationModel;
import io.codiqo.client.model.CloneModel;
import io.codiqo.client.model.CodeUnitDuplicationModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.NewCloneGroupModel;
import io.codiqo.llm.lang.LanguageCapabilities;
import lombok.Getter;

public class DuplicationReportPopulator implements SubmissionPopulator {
    @Getter
    private int totalDuplicatedLines = 0;

    @Override
    public void accept(SubmissionContext ctx) {
        DuplicationReportModel duplicationReportModel = new DuplicationReportModel();
        duplicationReportModel.setTool(DuplicationReportModel.ToolEnum.PMD_CPD);
        duplicationReportModel.setMinimumTokens(ctx.getArgs().getCpdMinimumTileSize());

        Path workTreeRealPath = resolveRealPath(ctx.getWorkTree());
        int totalDuplicatedTokens = 0;
        int duplicatedLines = 0;
        Map<String, Set<String>> duplicateOfBySignature = new LinkedHashMap<>();
        Map<String, Set<Integer>> duplicatedLinesByPath = new HashMap<>();

        for (CopyPasteDetectionSummary cpd : ctx.getAnalysis().cpd()) {
            for (DuplicationMatch match : cpd.affected()) {
                CloneModel cloneModel = new CloneModel();
                cloneModel.setTokenCount(match.getTokenCount());
                cloneModel.setLineCount(match.getLineCount());
                cloneModel.setIsCrossFile(match.isCrossFile());
                duplicationReportModel.getClones().add(cloneModel);

                totalDuplicatedTokens += match.getTokenCount();
                duplicatedLines += match.getLineCount();

                for (DuplicateMark mark : match) {
                    CloneLocationModel locationModel = new CloneLocationModel();
                    locationModel.setPath(ctx.getWorkTree().relativize(mark.getFile().toPath()).toString());

                    LocationModel loc = new LocationModel();
                    loc.setStartLine(mark.getLocation().getStartLine());
                    loc.setStartColumn(mark.getLocation().getStartColumn());
                    loc.setEndLine(mark.getLocation().getEndLine());
                    loc.setEndColumn(mark.getLocation().getEndColumn());
                    locationModel.setLocation(loc);
                    locationModel.setSourceSlice(mark.getSourceCodeSlice().toString());
                    mark.block().map(CodeBlockInfo::getSignature).ifPresent(locationModel::setCodeUnitSignature);
                    cloneModel.getLocations().add(locationModel);

                    Set<Integer> dupLines = duplicatedLinesByPath.computeIfAbsent(relativePath(workTreeRealPath, mark.getFile()), path -> new HashSet<>());
                    for (int line = loc.getStartLine(); line <= loc.getEndLine(); line++) {
                        dupLines.add(line);
                    }
                }
            }

            cpd.copyPasteFrom().forEach((targetBlock, sourceBlocks) -> {
                List<String> sourceSignatures = sourceBlocks.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toList());

                CloneFromExistingModel fromModel = new CloneFromExistingModel();
                fromModel.setAffectedSignature(targetBlock.getSignature());
                fromModel.setSourceSignatures(sourceSignatures);
                duplicationReportModel.getClonesFromExisting().add(fromModel);

                duplicateOfBySignature.computeIfAbsent(targetBlock.getSignature(), signature -> new LinkedHashSet<>()).addAll(sourceSignatures);
            });

            cpd.copyPasteNew().forEach(newCloneSet -> {
                List<String> memberSignatures = newCloneSet.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toList());

                NewCloneGroupModel newModel = new NewCloneGroupModel();
                newModel.setMemberSignatures(memberSignatures);
                duplicationReportModel.getNewClones().add(newModel);

                for (String memberSignature : memberSignatures) {
                    Set<String> others = new LinkedHashSet<>(memberSignatures);
                    others.remove(memberSignature);
                    duplicateOfBySignature.computeIfAbsent(memberSignature, signature -> new LinkedHashSet<>()).addAll(others);
                }
            });
        }

        this.totalDuplicatedLines = duplicatedLines;

        duplicationReportModel.setTotalDuplicatedTokens(totalDuplicatedTokens);
        duplicationReportModel.setTotalDuplicatedLines(duplicatedLines);

        ctx.getSubmissionModel().setDuplication(duplicationReportModel);

        Map<String, ChangedLines> changedByPath = classifyChangedFiles(ctx);

        populateChangedLineCpd(ctx, duplicationReportModel, duplicatedLinesByPath, changedByPath);

        applyDuplicationToCodeUnits(ctx, duplicateOfBySignature, duplicatedLinesByPath, changedByPath);
    }
    private static Map<String, ChangedLines> classifyChangedFiles(SubmissionContext ctx) {
        Map<String, ChangedLines> toReturn = new HashMap<>();
        for (FileChangeModel fileChangeModel : ctx.getSubmissionModel().getFiles()) {
            toReturn.put(fileChangeModel.getPath(),
                    ChangedLineClassifier.classify(fileChangeModel.getDiff(), LanguageCapabilities.filterFor(fileChangeModel)));
        }
        return toReturn;
    }
    private static void populateChangedLineCpd(SubmissionContext ctx, DuplicationReportModel duplicationReportModel,
            Map<String, Set<Integer>> duplicatedLinesByPath, Map<String, ChangedLines> changedByPath) {
        int addedDuplicated = 0;
        int addedTotal = 0;
        int modifiedDuplicated = 0;
        int modifiedTotal = 0;
        for (FileChangeModel fileChangeModel : ctx.getSubmissionModel().getFiles()) {
            Set<Integer> duplicatedLines = duplicatedLinesByPath.getOrDefault(fileChangeModel.getPath(), Collections.emptySet());
            ChangedLines changed = changedByPath.get(fileChangeModel.getPath());
            for (Integer line : changed.getAdded()) {
                addedTotal++;
                if (duplicatedLines.contains(line)) {
                    addedDuplicated++;
                }
            }
            for (Integer line : changed.getModified()) {
                modifiedTotal++;
                if (duplicatedLines.contains(line)) {
                    modifiedDuplicated++;
                }
            }
        }

        if (addedTotal > 0) {
            duplicationReportModel.setAddedLineCpdPercent(addedDuplicated * 100.0 / addedTotal);
        }
        if (modifiedTotal > 0) {
            duplicationReportModel.setModifiedLineCpdPercent(modifiedDuplicated * 100.0 / modifiedTotal);
        }
        int changedTotal = addedTotal + modifiedTotal;
        if (changedTotal > 0) {
            duplicationReportModel.setChangedLineCpdPercent((addedDuplicated + modifiedDuplicated) * 100.0 / changedTotal);
        }
    }
    private static void applyDuplicationToCodeUnits(SubmissionContext ctx, Map<String, Set<String>> duplicateOfBySignature,
            Map<String, Set<Integer>> duplicatedLinesByPath, Map<String, ChangedLines> changedByPath) {
        for (FileChangeModel fileChangeModel : ctx.getSubmissionModel().getFiles()) {
            Set<Integer> duplicatedLines = duplicatedLinesByPath.getOrDefault(fileChangeModel.getPath(), Collections.emptySet());
            ChangedLines changed = changedByPath.get(fileChangeModel.getPath());
            for (CodeUnitModel codeUnitModel : fileChangeModel.getCodeUnits()) {
                Set<String> duplicateOf = duplicateOfBySignature.get(codeUnitModel.getSignature());
                if (CollectionUtils.isNotEmpty(duplicateOf)) {
                    CodeUnitDuplicationModel duplicationModel = new CodeUnitDuplicationModel();
                    duplicationModel.setIsDuplicated(true);
                    duplicationModel.setDuplicateOf(new ArrayList<>(duplicateOf));
                    populateUnitDuplicationSpan(duplicationModel, codeUnitModel, duplicatedLines, changed);
                    codeUnitModel.setDuplication(duplicationModel);
                }
            }
        }
    }
    /**
     * duplicatePercent = share of the code unit's line span that lies inside a detected clone;
     * changedLinesAffected = whether any of the commit's changed lines in the unit are duplicated
     */
    private static void populateUnitDuplicationSpan(CodeUnitDuplicationModel duplicationModel, CodeUnitModel codeUnitModel,
            Set<Integer> duplicatedLines, ChangedLines changed) {
        LocationModel location = codeUnitModel.getLocation();
        if (Objects.nonNull(location) && Objects.nonNull(location.getStartLine()) && Objects.nonNull(location.getEndLine())) {
            int startLine = location.getStartLine();
            int endLine = location.getEndLine();

            int duplicatedSpanLines = 0;
            boolean changedLineDuplicated = false;
            for (int line = startLine; line <= endLine; line++) {
                if (duplicatedLines.contains(line)) {
                    duplicatedSpanLines++;
                    if (changed.contains(line)) {
                        changedLineDuplicated = true;
                    }
                }
            }

            duplicationModel.setChangedLinesAffected(changedLineDuplicated);
            int spanLines = endLine - startLine + 1;
            if (spanLines > 0) {
                duplicationModel.setDuplicatePercent(duplicatedSpanLines * 100.0 / spanLines);
            }
        }
    }
    private static String relativePath(Path workTreeRealPath, File file) {
        return workTreeRealPath.relativize(resolveRealPath(file.toPath())).toString().replace(File.separatorChar, '/');
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
