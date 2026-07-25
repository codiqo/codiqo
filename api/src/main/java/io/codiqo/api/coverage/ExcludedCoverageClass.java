package io.codiqo.api.coverage;

public record ExcludedCoverageClass(String className, CoverageExclusionReason reason) {
}
