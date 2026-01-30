package io.codiqo.maven.populator;

import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.client.model.CodeUnitRefModel;
import io.codiqo.client.model.CodebaseIndexModel;
import io.codiqo.client.model.LocationModel;

public class IndexModelPopulator implements SubmissionPopulator {

    @Override
    public void accept(SubmissionContext ctx) {
        CodebaseIndexModel indexModel = new CodebaseIndexModel();
        indexModel.setTotalFiles(ctx.getIndex().getTotalFiles().size());
        indexModel.setTotalCodeUnits(ctx.getIndex().getBlocks().size());
        indexModel.setIgnoredFiles(ctx.getIndex().getIgnoredFiles().size());
        indexModel.setSkippedFiles(ctx.getIndex().getSkippedFiles().size());
        indexModel.setTotalNonTrivial(ctx.getIndex().getTotalNonTrivial());
        indexModel.setSkippedTrivial(ctx.getIndex().getSkippedTrivial());

        for (FileAnalysis fileAnalysis : ctx.getAnalysis()) {
            for (AffectedSymbolInfo affectedSymbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                CodeUnitRefModel refModel = new CodeUnitRefModel();

                LocationModel locationModel = new LocationModel();
                locationModel.setStartLine(affectedSymbol.getLocation().getStartLine());
                locationModel.setStartColumn(affectedSymbol.getLocation().getStartColumn());
                locationModel.setEndLine(affectedSymbol.getLocation().getEndLine());
                locationModel.setEndColumn(affectedSymbol.getLocation().getEndColumn());
                refModel.setLocation(locationModel);
                refModel.setPath(fileAnalysis.getNewPath());
                ctx.getArgs().owner(fileAnalysis.getFile()).ifPresent(spec -> refModel.setModule(spec.getId()));
                affectedSymbol.block().ifPresent(block -> {
                    refModel.setSignature(block.getSignature());
                });
                indexModel.getCodeUnits().add(refModel);
            }
        }
        ctx.getSubmissionModel().setIndex(indexModel);
    }
}
