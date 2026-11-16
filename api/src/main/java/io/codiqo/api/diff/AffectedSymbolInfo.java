package io.codiqo.api.diff;

import java.io.File;
import java.util.List;
import java.util.Optional;

import io.codiqo.api.code.Caller;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.code.UnitKind;
import io.codiqo.client.model.CodeUnitModel;

public interface AffectedSymbolInfo {
    String getName();
    File getFile();
    SourceLocation getLocation();
    Optional<CodeBlockInfo> block();
    UnitKind getKind();
    List<Caller> getCallers();
    boolean isDeprecated();
    /**
     * Adds what only the unit's language describes to its code unit in the submission (for Java, its
     * {@code javaInfo} and that its signature is a JVM descriptor), once the submission has written everything every
     * language's units have, the unit's operation included.
     */
    void describe(CodeUnitModel unit);
}
