package io.codiqo.api;

import java.nio.file.Path;
import java.util.List;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.time.StopWatch;

import com.google.common.collect.Multimap;

import io.codiqo.api.code.CodeBlockInfo;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

@Data
@Builder
@Getter
public class IndexingSummary {
    private Multimap<Path, CodeBlockInfo> blocks;
    private List<Path> totalFiles;
    private List<Path> skippedFiles;
    private List<Path> ignoredFiles;
    private int skippedTrivial;
    private int totalSymbols;
    private StopWatch took;

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this);
    }
}
