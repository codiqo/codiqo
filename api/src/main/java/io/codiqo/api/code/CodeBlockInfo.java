package io.codiqo.api.code;

import java.io.File;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.metrics.CodeBlockMetrics;
import net.sourceforge.pmd.reporting.RuleViolation;
import reactor.core.publisher.Mono;

public interface CodeBlockInfo extends Consumer<AffectedSymbolInfo> {
    String getSignature();
    File getFile();
    SourceLocation getLocation();
    Collection<RuleViolation> getPmdViolations();
    String getBody();
    boolean isTrivial();
    void pmdViolation(RuleViolation violation);
    boolean hasMethodCalls();
    int countMethodCalls();
    Optional<AffectedSymbolInfo> affectedSymbol();
    Mono<CodeBlockMetrics> metrics();
    Mono<CodeBlockCoverage> coverage();
}
