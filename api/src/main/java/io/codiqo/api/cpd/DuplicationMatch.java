package io.codiqo.api.cpd;

import java.io.File;
import java.util.Set;
import java.util.function.Consumer;

import io.codiqo.api.DuplicateMark;
import io.codiqo.api.code.CodeBlockInfo;

public interface DuplicationMatch extends Comparable<PmdDuplicationMatch>, Consumer<CodeBlockInfo>, Iterable<DuplicateMark> {
    int getTokenCount();
    int getLineCount();
    boolean isCrossFile();
    Set<CodeBlockInfo> getBlocks();
    Set<File> getFiles();
}
