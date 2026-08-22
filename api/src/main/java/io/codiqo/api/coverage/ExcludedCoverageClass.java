package io.codiqo.api.coverage;

import lombok.Value;

@Value
public class ExcludedCoverageClass {
    String className;
    CoverageExclusionReason reason;
}
