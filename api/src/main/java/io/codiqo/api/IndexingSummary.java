package io.codiqo.api;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.lang3.time.StopWatch;

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
    private MultiValuedMap<File, CodeBlockInfo> blocks;
    @Builder.Default
    private Set<File> parsedFiles = new HashSet<>();
    @Builder.Default
    private List<DeclaredType> types = new ArrayList<>();
    @Builder.Default
    private List<TypeReference> references = new ArrayList<>();
    private List<Path> totalFiles;
    private List<Path> skippedFiles;
    private List<Path> ignoredFiles;
    private int skippedTrivial;
    private int totalNonTrivial;
    private StopWatch took;
}
