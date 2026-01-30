package io.codiqo.maven.populator;

import java.util.stream.Collectors;

import io.codiqo.api.DuplicateMark;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.cpd.DuplicationMatch;
import io.codiqo.client.model.CloneFromExistingModel;
import io.codiqo.client.model.CloneLocationModel;
import io.codiqo.client.model.CloneModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.NewCloneGroupModel;
import lombok.Getter;

public class DuplicationReportPopulator implements SubmissionPopulator {
    @Getter
    private int totalDuplicatedLines = 0;

    @Override
    public void accept(SubmissionContext ctx) {
        DuplicationReportModel duplicationReportModel = new DuplicationReportModel();
        duplicationReportModel.setTool(DuplicationReportModel.ToolEnum.PMD_CPD);
        duplicationReportModel.setMinimumTokens(ctx.getArgs().getCpdMinimumTileSize());

        int totalDuplicatedTokens = 0;
        int duplicatedLines = 0;

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
                }
            }

            cpd.copyPasteFrom().forEach((targetBlock, sourceBlocks) -> {
                CloneFromExistingModel fromModel = new CloneFromExistingModel();
                fromModel.setAffectedSignature(targetBlock.getSignature());
                fromModel.setSourceSignatures(sourceBlocks.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toList()));
                duplicationReportModel.getClonesFromExisting().add(fromModel);
            });

            cpd.copyPasteNew().forEach(newCloneSet -> {
                NewCloneGroupModel newModel = new NewCloneGroupModel();
                newModel.setMemberSignatures(newCloneSet.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toList()));
                duplicationReportModel.getNewClones().add(newModel);
            });
        }

        this.totalDuplicatedLines = duplicatedLines;

        duplicationReportModel.setTotalDuplicatedTokens(totalDuplicatedTokens);
        duplicationReportModel.setTotalDuplicatedLines(duplicatedLines);

        ctx.getSubmissionModel().setDuplication(duplicationReportModel);
    }
}
