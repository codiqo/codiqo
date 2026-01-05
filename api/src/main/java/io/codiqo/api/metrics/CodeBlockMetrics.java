package io.codiqo.api.metrics;

import io.codiqo.api.code.CodeBlockInfo;

public interface CodeBlockMetrics {
    CodeBlockInfo block();
    int lineCount();
    int cyclo();
    int cognitive();
    int ncss();
    long npath();
    int fanOut();
}
