package io.codiqo.api.cpd;

import java.io.File;
import java.util.Map;
import java.util.Set;

import io.codiqo.api.code.CodeBlockInfo;

public interface CopyPasteDetectionSummary {
    Map<File, Integer> tokensPerFile();
    Set<DuplicationMatch> affected();
    Map<CodeBlockInfo, Set<CodeBlockInfo>> copyPasteFrom();
    Set<Set<CodeBlockInfo>> copyPasteNew();
}
