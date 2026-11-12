package io.codiqo.api.code;

import java.io.File;

import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.metrics.CodeBlockMetrics;

public interface CodeBlockInfo {
    String getSignature();
    File getFile();
    SourceLocation getLocation();
    String getBody();
    boolean isTrivial();
    CodeBlockMetrics metrics();
    CodeBlockCoverage coverage();
}
