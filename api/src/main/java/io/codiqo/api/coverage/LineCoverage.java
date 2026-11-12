package io.codiqo.api.coverage;

import lombok.Value;

/** One source line's coverage, as the language's coverage tool measured it: whether it ran, how often, and its branches */
@Value
public class LineCoverage {
    Status status;
    /** the instructions of the line that ran */
    int hits;
    int coveredBranches;
    int missedBranches;

    public int totalBranches() {
        return coveredBranches + missedBranches;
    }

    public enum Status {
        EMPTY,
        MISSED,
        PARTIAL,
        COVERED
    }
}
