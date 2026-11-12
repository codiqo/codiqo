package io.codiqo.api.diff;

import java.io.File;
import java.util.List;
import java.util.Optional;

import io.codiqo.api.code.Caller;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.code.UnitKind;

public interface AffectedSymbolInfo {
    String getName();
    File getFile();
    SourceLocation getLocation();
    Optional<CodeBlockInfo> block();
    UnitKind getKind();
    List<Caller> getCallers();
    boolean isDeprecated();
}
