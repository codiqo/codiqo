package io.codiqo.api.pmd;

import java.nio.file.Path;
import java.util.Collection;

import io.codiqo.api.DuplicateMark;
import io.codiqo.api.code.CodeBlockInfo;

public interface DuplicationMatch extends Comparable<PmdDuplicationMatch> {
    int getTokenCount();
    int getLineCount();
    Collection<DuplicateMark> getLocations();
    Collection<Path> getPaths();
    boolean isCrossFile();
    CodeBlockInfo getBlock();
}
