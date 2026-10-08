package io.codiqo.api;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.time.StopWatch;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.DeclaredType;
import io.codiqo.api.code.TypeReference;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

@Data
@Builder
@Getter
public class IndexingSummary {
    private File projectRoot;
    private Collection<ProjectSpec> projects;
    private Multimap<File, CodeBlockInfo> blocks;
    @Builder.Default
    private Set<File> parsedFiles = Sets.newHashSet();
    @Builder.Default
    private List<DeclaredType> types = Lists.newArrayList();
    @Builder.Default
    private List<TypeReference> references = Lists.newArrayList();
    private List<Path> totalFiles;
    private List<Path> skippedFiles;
    private List<Path> ignoredFiles;
    private int skippedTrivial;
    private int totalNonTrivial;
    private StopWatch took;
}
