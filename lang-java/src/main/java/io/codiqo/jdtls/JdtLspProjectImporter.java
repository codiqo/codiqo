package io.codiqo.jdtls;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import io.codiqo.api.LanguageServerProjectImporter;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.util.Fetch;

public class JdtLspProjectImporter implements Lsp4jQuery, LanguageServerProjectImporter, Closeable {
    public static final int EXIT_OK = 0;
    public static final int EXIT_SIGTERM = 143;

    private final CompletableFuture<JdtLspClient> clientFuture = new CompletableFuture<>();
    private final AtomicReference<JdtLspClient> curr = new AtomicReference<>();
    private final Log log;
    private final RunArgs args;
    private final int port;
    private final JdtLspProcess jdt;
    private final ServerSocket serverSocket;

    public JdtLspProjectImporter(LogFactory logFactory, RunArgs args, Fetch fetch) throws IOException {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }
        this.serverSocket = new ServerSocket(port);
        this.jdt = new JdtLspProcess(logFactory, args, fetch, port);
        this.jdt.onExit().thenAccept(exitCode -> {
            switch (exitCode) {
                case EXIT_OK:
                case EXIT_SIGTERM:
                    break;
                default:
                    log.error("JDT LSP process exited with code: " + exitCode);
                    clientFuture.completeExceptionally(new IllegalStateException("JDT LSP process exited with code: " + exitCode));
                    break;
            }
        });

        Thread acceptThread = new Thread(() -> {
            try {
                JdtLspClient toSet = new JdtLspClient(logFactory, args, serverSocket.accept());
                curr.set(toSet);
                clientFuture.complete(toSet);
                log.info("JDT LSP client connected on port :" + port);
            } catch (Throwable err) {
                if (serverSocket.isClosed()) {
                    return;
                }
                clientFuture.completeExceptionally(err);
            }
        }, "jdt-lsp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }
    @Override
    public void load() {
        StopWatch stopWatch = StopWatch.createStarted();
        try {
            JdtLspClient c = getClient();
            c.initialize();
            c.ready().get(args.getImportTimeout().getSeconds(), TimeUnit.SECONDS);
            stopWatch.stop();
            log.info("JDT loaded project: %s in: %s ", args.getGit().getWorkTree(), stopWatch);
        } catch (Throwable err) {
            ExceptionUtils.wrapAndThrow(err);
        }
    }
    @Override
    public CompletableFuture<List<? extends WorkspaceSymbol>> symbol(String query) {
        WorkspaceService service = getLangServer().getWorkspaceService();
        WorkspaceSymbolParams params = new WorkspaceSymbolParams(query);
        return service.symbol(params).thenApply(either -> {
            Validate.isTrue(BooleanUtils.isTrue(either.isRight()));
            Validate.isTrue(BooleanUtils.isFalse(either.isLeft()));
            return either.getRight();
        });
    }
    @Override
    public CompletableFuture<WorkspaceSymbol> resolveWorkspaceSymbol(WorkspaceSymbol query) {
        WorkspaceService service = getLangServer().getWorkspaceService();
        return service.resolveWorkspaceSymbol(query);
    }
    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyItem item) {
        TextDocumentService service = getLangServer().getTextDocumentService();
        CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams(item);
        return service.callHierarchyIncomingCalls(params);
    }
    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyItem item) {
        TextDocumentService service = getLangServer().getTextDocumentService();
        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(item);
        return service.callHierarchyOutgoingCalls(params);
    }
    @Override
    public CompletableFuture<List<? extends Location>> definition(Location location) {
        TextDocumentService service = getLangServer().getTextDocumentService();
        DefinitionParams params = new DefinitionParams();
        params.setTextDocument(new TextDocumentIdentifier(location.getUri()));
        params.setPosition(location.getRange().getStart());
        return service.definition(params).thenApply(either -> {
            Validate.isTrue(BooleanUtils.isTrue(either.isLeft()));
            Validate.isTrue(BooleanUtils.isFalse(either.isRight()));
            return either.getLeft();
        });
    }
    @Override
    public CompletableFuture<List<? extends Location>> references(Location location) {
        TextDocumentService service = getLangServer().getTextDocumentService();
        ReferenceParams params = new ReferenceParams();
        params.setContext(new ReferenceContext(true));
        params.setTextDocument(new TextDocumentIdentifier(location.getUri()));
        params.setPosition(location.getRange().getStart());
        return service.references(params);
    }
    @Override
    public CompletableFuture<List<? extends Location>> implementation(Location location) {
        TextDocumentService service = getLangServer().getTextDocumentService();
        ImplementationParams params = new ImplementationParams();
        params.setTextDocument(new TextDocumentIdentifier(location.getUri()));
        params.setPosition(location.getRange().getStart());
        return service.implementation(params).thenApply(either -> {
            Validate.isTrue(BooleanUtils.isTrue(either.isLeft()));
            Validate.isTrue(BooleanUtils.isFalse(either.isRight()));
            return either.getLeft();
        });
    }
    @Override
    public CompletableFuture<List<? extends Location>> typeDefinition(Location location) {
        TextDocumentService service = getLangServer().getTextDocumentService();
        TypeDefinitionParams params = new TypeDefinitionParams();
        params.setTextDocument(new TextDocumentIdentifier(location.getUri()));
        params.setPosition(location.getRange().getStart());
        return service.typeDefinition(params).thenApply(either -> {
            Validate.isTrue(BooleanUtils.isTrue(either.isLeft()));
            Validate.isTrue(BooleanUtils.isFalse(either.isRight()));
            return either.getLeft();
        });
    }
    @Override
    public CompletableFuture<List<DocumentSymbol>> documentSymbol(String uri) {
        TextDocumentService service = getLangServer().getTextDocumentService();
        DocumentSymbolParams params = new DocumentSymbolParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        return service.documentSymbol(params).thenApply(l -> l.stream().map(either -> {
            Validate.isTrue(BooleanUtils.isFalse(either.isLeft()));
            Validate.isTrue(BooleanUtils.isTrue(either.isRight()));
            return either.getRight();
        }).collect(Collectors.toList()));
    }
    @Override
    public void close() throws IOException {
        try {
            JdtLspClient toClose = curr.get();
            if (Objects.nonNull(toClose)) {
                toClose.close();
            }
        } finally {
            try {
                if (Objects.nonNull(jdt)) {
                    jdt.close();
                }
            } finally {
                if (Objects.nonNull(serverSocket)) {
                    serverSocket.close();
                    log.info("disposed server socket on port: " + port);
                }
            }
        }
    }
    private JdtLspClient getClient() {
        for (;;) {
            try {
                return clientFuture.get(args.getImportTimeout().getSeconds(), TimeUnit.SECONDS);
            } catch (Exception err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    private LanguageServer getLangServer() {
        return getClient().get();
    }
}
