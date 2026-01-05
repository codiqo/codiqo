package io.codiqo.api.diff;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;

public interface AffectedSymbolInfo extends Consumer<CodeBlockInfo> {
    Path getPath();
    SourceLocation getLocation();
    Optional<CodeBlockInfo> block();
}