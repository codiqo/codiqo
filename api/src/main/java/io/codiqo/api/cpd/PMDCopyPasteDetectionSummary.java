package io.codiqo.api.cpd;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;

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
    private final Map<CodeBlockInfo, Set<CodeBlockInfo>> copyPasteFrom = Maps.newLinkedHashMap();
    private final Set<Set<CodeBlockInfo>> copyPasteNew = Sets.newLinkedHashSet();
    private final Set<DuplicationMatch> affected = Sets.newLinkedHashSet();
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
                    Range<Integer> markRange = Range.closed(mark.getLocation().getStartLine(), mark.getLocation().getEndLine());

                    for (FileAnalysis fileAnalysis : analysis) {
                        if (mark.getFile().equals(fileAnalysis.getFile())) {
                            for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                                Range<Integer> symbolRange = Range.closed(symbol.getLocation().getStartLine(), symbol.getLocation().getEndLine());
                                if (symbolRange.encloses(markRange)) {
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
                    Range<Integer> sourceMarkRange = Range.closed(mark.getLocation().getStartLine(), mark.getLocation().getEndLine());
                    for (CodeBlockInfo block : blocks) {
                        Range<Integer> blockRange = Range.closed(block.getLocation().getStartLine(), block.getLocation().getEndLine());
                        if (blockRange.encloses(sourceMarkRange)) {
                            match.accept(block);
                            mark.accept(block);
                        }
                    }
                }
            }
        });

        affected.forEach(match -> {
            Set<CodeBlockInfo> modifiedSet = Sets.newLinkedHashSet();
            Set<CodeBlockInfo> staticSet = Sets.newLinkedHashSet();

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
                    copyPasteFrom.computeIfAbsent(it, k -> Sets.newLinkedHashSet()).addAll(staticSet);
                }
            }
        });
    }
}
