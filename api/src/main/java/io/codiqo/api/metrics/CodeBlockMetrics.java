package io.codiqo.api.metrics;

import java.util.Optional;

import io.codiqo.api.code.CodeBlockInfo;

public interface CodeBlockMetrics {
    CodeBlockInfo block();
    int lineCount();
    int cyclo();
    int cognitive();
    Optional<Integer> ncss();
    long npath();
    int fanOut();
}
