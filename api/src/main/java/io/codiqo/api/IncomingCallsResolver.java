package io.codiqo.api;

import java.io.IOException;

import io.codiqo.api.diff.CommitAnalysis;

public interface IncomingCallsResolver {
    void resolve(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
}
