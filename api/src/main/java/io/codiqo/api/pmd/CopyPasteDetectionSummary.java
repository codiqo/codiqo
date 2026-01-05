package io.codiqo.api.pmd;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.collections4.CollectionUtils;

import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;

import io.codiqo.api.DuplicateMark;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import lombok.Data;

@Data
public class CopyPasteDetectionSummary {
    private int total;
    private Map<CodeBlockInfo, Set<CodeBlockInfo>> existing = Maps.newLinkedHashMap();
    private Set<Set<CodeBlockInfo>> sameCommit = Sets.newLinkedHashSet();

    public CopyPasteDetectionSummary(int total, Set<DuplicationMatch> matches, IndexingSummary summary, CommitAnalysis analysis) {
        this.total = total;

        Set<Path> diff = analysis.paths();
        Map<CodeBlockInfo, Set<CodeBlockInfo>> graph = Maps.newHashMap();

        matches.forEach(new Consumer<DuplicationMatch>() {
            @Override
            public void accept(DuplicationMatch match) {
                for (DuplicateMark mark : match.getLocations()) {
                    if (diff.contains(mark.getPath())) {
                        Range<Integer> markRange = Range.closed(mark.getLocation().getStartLine(), mark.getLocation().getEndLine());
                        Set<CodeBlockInfo> affectedBlocks = Sets.newLinkedHashSet();

                        for (FileAnalysis fileAnalysis : analysis.getFiles()) {
                            if (mark.getPath().equals(fileAnalysis.getPath())) {
                                for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                                    Range<Integer> symbolRange = Range.closed(symbol.getLocation().getStartLine(), symbol.getLocation().getEndLine());
                                    if (symbolRange.encloses(markRange)) {
                                        symbol.block().ifPresent(new Consumer<CodeBlockInfo>() {
                                            @Override
                                            public void accept(CodeBlockInfo block) {
                                                mark.accept(block);
                                                affectedBlocks.add(block);
                                            }
                                        });
                                    }
                                }
                            }
                        }

                        if (affectedBlocks.isEmpty()) {
                            continue;
                        }

                        for (DuplicateMark sourceMark : match.getLocations()) {
                            if (sourceMark.equals(mark)) {
                                continue;
                            }

                            Range<Integer> sourceMarkRange = Range.closed(sourceMark.getLocation().getStartLine(), sourceMark.getLocation().getEndLine());
                            Collection<CodeBlockInfo> blocks = summary.getBlocks().get(sourceMark.getPath());

                            if (CollectionUtils.isNotEmpty(blocks)) {
                                for (CodeBlockInfo sourceBlock : blocks) {
                                    if (affectedBlocks.contains(sourceBlock)) {
                                        continue;
                                    }

                                    Range<Integer> blockRange = Range.closed(sourceBlock.getLocation().getStartLine(), sourceBlock.getLocation().getEndLine());
                                    if (blockRange.encloses(sourceMarkRange)) {
                                        sourceMark.accept(sourceBlock);

                                        if (analysis.isPresent(sourceMark.getPath(), sourceBlock)) {
                                            for (CodeBlockInfo affected : affectedBlocks) {
                                                graph.computeIfAbsent(affected, k -> Sets.newLinkedHashSet()).add(sourceBlock);
                                                graph.computeIfAbsent(sourceBlock, k -> Sets.newLinkedHashSet()).add(affected);
                                            }
                                        } else {
                                            for (CodeBlockInfo affected : affectedBlocks) {
                                                existing.computeIfAbsent(affected, k -> Sets.newLinkedHashSet()).add(sourceBlock);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        Set<CodeBlockInfo> visited = Sets.newHashSet();
        for (CodeBlockInfo block : graph.keySet()) {
            if (visited.contains(block)) {
                continue;
            }

            Deque<CodeBlockInfo> queue = new ArrayDeque<>();
            queue.add(block);

            Set<CodeBlockInfo> component = Sets.newLinkedHashSet();
            while (CollectionUtils.isNotEmpty(queue)) {
                CodeBlockInfo current = queue.poll();
                if (visited.add(current)) {
                    component.add(current);
                    Set<CodeBlockInfo> neighbors = graph.get(current);
                    if (CollectionUtils.isNotEmpty(neighbors)) {
                        for (CodeBlockInfo neighbor : neighbors) {
                            if (Boolean.FALSE.equals(visited.contains(neighbor))) {
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }

            if (component.size() > BigDecimal.ONE.intValue()) {
                sameCommit.add(component);
            }
        }
    }
}