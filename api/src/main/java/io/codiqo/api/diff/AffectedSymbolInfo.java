package io.codiqo.api.diff;

import java.io.File;
import java.util.Optional;
import java.util.function.Consumer;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import net.sourceforge.pmd.lang.Language;

public interface AffectedSymbolInfo extends Consumer<CodeBlockInfo> {
    String getName();
    File getFile();
    Language getLanguage();
    SourceLocation getLocation();
    Optional<CodeBlockInfo> block();
    default void blockIfPresent(Class<?> type, Consumer<CodeBlockInfo> consumer) {
        block().ifPresent(t -> {
            if (type.isInstance(t)) {
                consumer.accept(t);
            }
        });
    }
}
