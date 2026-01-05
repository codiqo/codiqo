package io.codiqo.core.diff;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GitCommitAnalysis implements CommitAnalysis {
    private String commitId;
    private String commitIdShort;
    private String message;
    private String author;
    private String authorEmail;
    private Instant authorTimestamp;
    private String committer;
    private String committerEmail;
    private Instant commitTimestamp;
    private List<String> parentIds = Lists.newArrayList();
    private List<String> branches = Lists.newArrayList();
    private boolean mergeCommit;
    private String gpgSignature;
    private String encoding;
    private int filesChanged;
    private Set<FileAnalysis> files = Sets.newLinkedHashSet();
    private final Supplier<Set<Path>> paths = Suppliers.memoize(new Supplier<Set<Path>>() {
        @Override
        public Set<Path> get() {
            return getFiles().stream().map(new Function<FileAnalysis, Path>() {
                @Override
                public Path apply(FileAnalysis file) {
                    return file.getPath();
                }
            }).collect(ImmutableSet.toImmutableSet());
        }
    });

    @Override
    public Set<Path> paths() {
        return paths.get();
    }
    @Override
    public boolean isPresent(Path path, CodeBlockInfo block) {
        if (paths().contains(path)) {
            for (FileAnalysis fileAnalysis : getFiles()) {
                if (path.equals(fileAnalysis.getPath())) {
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
}