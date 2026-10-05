package io.codiqo.api.cpd;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.codiqo.api.code.CodeBlockInfo;

public interface CopyPasteDetectionSummary {
    Map<File, Integer> tokensPerFile();
    Set<DuplicationMatch> affected();
    /** Every clone the detector found, for the hotspot snapshot commit only; empty for any other commit. */
    List<CloneLocations> clones();
    Map<CodeBlockInfo, Set<CodeBlockInfo>> copyPasteFrom();
    Set<Set<CodeBlockInfo>> copyPasteNew();
    int duplicatedLines();
    int scannedLines();
}
