package io.codiqo.api.coverage;

import org.apache.commons.lang3.tuple.ImmutablePair;

public final class ExcludedCoverageClass extends ImmutablePair<String, CoverageExclusionReason> {
    public ExcludedCoverageClass(String className, CoverageExclusionReason reason) {
        super(className, reason);
    }
    public String getClassName() {
        return getLeft();
    }
    public CoverageExclusionReason getReason() {
        return getRight();
    }
}
