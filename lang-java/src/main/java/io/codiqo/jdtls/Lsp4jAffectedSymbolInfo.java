package io.codiqo.jdtls;

import java.util.List;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;

import io.codiqo.api.diff.AffectedSymbolInfo;

public interface Lsp4jAffectedSymbolInfo extends AffectedSymbolInfo {
    SymbolKind getKind();
    List<CallHierarchyIncomingCall> getIncomingCalls();
    List<SymbolTag> getTags();
}
