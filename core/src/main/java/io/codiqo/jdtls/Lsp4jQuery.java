package io.codiqo.jdtls;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.WorkspaceSymbol;

public interface Lsp4jQuery {
    CompletableFuture<WorkspaceSymbol> resolveWorkspaceSymbol(WorkspaceSymbol query);
    CompletableFuture<List<? extends WorkspaceSymbol>> symbol(String query);
    CompletableFuture<List<? extends Location>> definition(Location location);
    CompletableFuture<List<? extends Location>> references(Location location);
    CompletableFuture<List<? extends Location>> implementation(Location location);
    CompletableFuture<List<? extends Location>> typeDefinition(Location location);
    CompletableFuture<List<DocumentSymbol>> documentSymbol(String uri);
    CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyItem item);
    CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyItem item);
}
