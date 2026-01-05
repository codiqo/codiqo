package io.codiqo.api.code;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.jacoco.core.analysis.ILine;

import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.metrics.CodeBlockMetrics;
import net.sourceforge.pmd.reporting.RuleViolation;
import reactor.core.publisher.Mono;

public interface CodeBlockInfo extends Consumer<AffectedSymbolInfo> {
    File getFile();
    SourceLocation getLocation();
    Collection<RuleViolation> getPmdViolations();
    Map<Integer, ILine> getLineCoverage();
    String getBody();
    boolean isTrivial();
    void pmdViolation(RuleViolation violation);
    void lineCoverage(int lineNumber, ILine line);
    boolean hasMethodCalls();
    int countMethodCalls();
    Optional<AffectedSymbolInfo> affectedSymbol();
    Mono<CodeBlockMetrics> metrics();
    Mono<CodeBlockCoverage> coverage();
}
