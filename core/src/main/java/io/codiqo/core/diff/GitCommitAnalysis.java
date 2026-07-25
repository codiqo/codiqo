package io.codiqo.core.diff;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.stream.Collectors;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.coverage.ExcludedCoverageClass;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.util.Lazy;
import lombok.Data;

@Data
public class GitCommitAnalysis implements CommitAnalysis {
    private String commitId;
    private String message;
    private String author;
    private String authorEmail;
    private Date authorTimestamp;
    private String committer;
    private String committerEmail;
    private Date commitTimestamp;
    private List<String> parentIds = new ArrayList<>();
    private List<String> branches = new ArrayList<>();
    private boolean mergeCommit;
    private boolean revertCommit;
    private String revertedCommitId;
    private int filesChanged;
    private Set<FileAnalysis> files = new LinkedHashSet<>();
    private List<CopyPasteDetectionSummary> cpd = new ArrayList<>();
    private List<ExcludedCoverageClass> excludedCoverageClasses = new ArrayList<>();
    private final Supplier<Set<File>> destinations = Lazy.of(
            () -> getFiles().stream().map(FileAnalysis::getFile).collect(Collectors.toUnmodifiableSet()));

    @Override
    public Set<File> locations() {
        return destinations.get();
    }
    @Override
    public Collection<CopyPasteDetectionSummary> cpd() {
        return cpd;
    }
    @Override
    public Collection<ExcludedCoverageClass> excludedCoverageClasses() {
        return excludedCoverageClasses;
    }
    @Override
    public boolean isPresent(File destination, CodeBlockInfo block) {
        if (locations().contains(destination)) {
            for (FileAnalysis fileAnalysis : getFiles()) {
                if (destination.equals(fileAnalysis.getFile())) {
                    for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                        if (symbol.block().isPresent()) {
                            if (symbol.block().get().equals(block)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
    @Override
    public Iterator<FileAnalysis> iterator() {
        return files.iterator();
    }
}