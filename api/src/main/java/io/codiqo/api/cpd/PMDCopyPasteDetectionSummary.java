package io.codiqo.api.cpd;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;


import io.codiqo.api.DuplicateMark;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class PMDCopyPasteDetectionSummary implements CopyPasteDetectionSummary {
    private final Map<CodeBlockInfo, Set<CodeBlockInfo>> copyPasteFrom = new LinkedHashMap<>();
    private final Set<Set<CodeBlockInfo>> copyPasteNew = new LinkedHashSet<>();
    private final Set<DuplicationMatch> affected = new LinkedHashSet<>();
    private final Map<File, Integer> tokensPerFile;

    public PMDCopyPasteDetectionSummary(
            Map<File, Integer> tokensPerFile,
            Set<DuplicationMatch> matches,
            IndexingSummary summary,
            CommitAnalysis analysis) {
        this.tokensPerFile = Objects.requireNonNull(tokensPerFile);

        matches.forEach(match -> {
            Collection<File> locations = analysis.locations();
            for (DuplicateMark mark : match) {
                if (locations.contains(mark.getFile())) {
                    int markStart = mark.getLocation().getStartLine();
                    int markEnd = mark.getLocation().getEndLine();

                    for (FileAnalysis fileAnalysis : analysis) {
                        if (mark.getFile().equals(fileAnalysis.getFile())) {
                            for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                                if (symbol.getLocation().getStartLine() <= markStart && markEnd <= symbol.getLocation().getEndLine()) {
                                    symbol.block().ifPresent(block -> {
                                        affected.add(match);
                                    });
                                }
                            }
                        }
                    }
                }
            }
        });

        matches.forEach(match -> {
            if (affected.contains(match)) {
                for (DuplicateMark mark : match) {
                    Collection<CodeBlockInfo> blocks = summary.getBlocks().get(mark.getFile());
                    int markStart = mark.getLocation().getStartLine();
                    int markEnd = mark.getLocation().getEndLine();
                    for (CodeBlockInfo block : blocks) {
                        if (block.getLocation().getStartLine() <= markStart && markEnd <= block.getLocation().getEndLine()) {
                            match.accept(block);
                            mark.accept(block);
                        }
                    }
                }
            }
        });

        affected.forEach(match -> {
            Set<CodeBlockInfo> modifiedSet = new LinkedHashSet<>();
            Set<CodeBlockInfo> staticSet = new LinkedHashSet<>();

            for (DuplicateMark mark : match) {
                mark.block().ifPresent(block -> {
                    if (analysis.isPresent(mark.getFile(), block)) {
                        modifiedSet.add(block);
                    } else {
                        staticSet.add(block);
                    }
                });
            }

            if (staticSet.isEmpty()) {
                copyPasteNew.add(modifiedSet);
            } else {
                for (CodeBlockInfo it : modifiedSet) {
                    copyPasteFrom.computeIfAbsent(it, k -> new LinkedHashSet<>()).addAll(staticSet);
                }
            }
        });
    }
}
