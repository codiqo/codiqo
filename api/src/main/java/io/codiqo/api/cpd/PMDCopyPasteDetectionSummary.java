package io.codiqo.api.cpd;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.BitSet;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;

import org.apache.commons.lang3.CharUtils;

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
    private static final int READ_BUFFER_BYTES = 64 * 1024;

    private final int duplicatedLines;
    private final int scannedLines;

    public PMDCopyPasteDetectionSummary(
            Map<File, Integer> tokensPerFile,
            Set<DuplicationMatch> matches,
            IndexingSummary summary,
            CommitAnalysis analysis) {
        this.tokensPerFile = Objects.requireNonNull(tokensPerFile);

        /**
         * measured over every match, before the commit filter below narrows the set: a duplication density describes
         * the codebase, so both of its sides have to be drawn from everything the detector read.
         */
        this.duplicatedLines = countDuplicatedLines(matches);
        this.scannedLines = countSourceLines(tokensPerFile.keySet());

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
    static int countDuplicatedLines(Set<DuplicationMatch> matches) {
        Map<File, BitSet> linesByFile = new HashMap<>();

        for (DuplicationMatch match : matches) {
            for (DuplicateMark mark : match) {
                BitSet lines = linesByFile.computeIfAbsent(mark.getFile(), file -> new BitSet());
                lines.set(mark.getLocation().getStartLine(), mark.getLocation().getEndLine() + 1);
            }
        }

        return linesByFile.values().stream().mapToInt(BitSet::cardinality).sum();
    }
    static int countSourceLines(Collection<File> files) {
        int toReturn = 0;
        byte[] buffer = new byte[READ_BUFFER_BYTES];

        for (File file : files) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                int read;
                int last = -1;
                while ((read = in.read(buffer)) > 0) {
                    for (int i = 0; i < read; i++) {
                        if (buffer[i] == CharUtils.LF) {
                            toReturn++;
                        }
                    }
                    last = buffer[read - 1];
                }
                if (last >= 0 && last != CharUtils.LF) {
                    toReturn++;
                }
            } catch (IOException err) {
                continue;
            }
        }
        return toReturn;
    }
}
