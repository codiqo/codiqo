package io.codiqo.api.metrics;

import java.util.Collections;
import java.util.List;

import io.codiqo.api.code.CodeBlockInfo;

public interface CodeBlockMetrics {
    CodeBlockInfo block();
    int lineCount();
    int nonCommentCodeLines();
    int commentLines();
    int cyclo();
    int cognitive();
    int ncss();
    long npath();
    int fanOut();
    int directInvocationCount();
    default List<Integer> directInvocationLines() {
        return Collections.emptyList();
    }
}
