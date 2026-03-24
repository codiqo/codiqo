package io.codiqo.api.diff;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import net.sourceforge.pmd.lang.Language;

public interface AffectedSymbolInfo {
    String getName();
    File getFile();
    Language getLanguage();
    SourceLocation getLocation();
    Optional<CodeBlockInfo> block();
    SymbolKind getKind();
    List<CallHierarchyIncomingCall> getIncomingCalls();
    default List<SymbolTag> getTags() {
        return Collections.emptyList();
    }
    default void acceptBlockIfPresent(Class<?> type, Consumer<CodeBlockInfo> consumer) {
        block().ifPresent(t -> {
            if (type.isInstance(t)) {
                consumer.accept(t);
            }
        });
    }
}
