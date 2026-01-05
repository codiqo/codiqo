package io.codiqo.core.java;

public interface JavaCodeBlockMetrics {
    JavaCodeBlockInfo block();
    int lineCount();
    int cyclo();
    int cognitive();
    int ncss();
    long npath();
    int fanOut();
}
