package io.codiqo.api.code;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

import io.codiqo.api.diff.AffectedSymbolInfo;
import net.sourceforge.pmd.reporting.RuleViolation;

public interface CodeBlockInfo extends Consumer<AffectedSymbolInfo> {
    Path getPath();
    SourceLocation getLocation();
    Collection<RuleViolation> getPmdViolations();
    String getBody();
    boolean isTrivial();
    void addPmdViolation(RuleViolation violation);
    Optional<AffectedSymbolInfo> affectedSymbol();
}
