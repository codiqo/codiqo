package io.codiqo.api.code;

import java.io.File;
import java.util.Collection;

import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.metrics.CodeBlockMetrics;
import net.sourceforge.pmd.reporting.RuleViolation;

public interface CodeBlockInfo {
    String getSignature();
    File getFile();
    SourceLocation getLocation();
    Collection<RuleViolation> getPmdViolations();
    String getBody();
    boolean isTrivial();
    void pmdViolation(RuleViolation violation);
    CodeBlockMetrics metrics();
    CodeBlockCoverage coverage();
}
