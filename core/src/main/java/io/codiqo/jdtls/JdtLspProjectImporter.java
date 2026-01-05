package io.codiqo.jdtls;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.lang3.BooleanUtils;
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
import org.eclipse.lsp4j.InitializeResult;
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

import com.google.common.base.Preconditions;

import io.codiqo.api.LanguageServerProjectImporter;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.Fetch;
import lombok.SneakyThrows;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.scheduler.Schedulers;

public class JdtLspProjectImporter implements LanguageServerProjectImporter, Lsp4jQuery, Closeable {
    private final Sinks.Many<Integer> processor = Sinks.many().replay().latest();
    private final Sinks.Many<JdtLspClient> client = Sinks.many().replay().latest();
    private final AtomicReference<JdtLspClient> curr = new AtomicReference<>();
    private final Log log;
    private final RunArgs args;
    private final int port;
    private final JdtLspProcess jdt;
    private final ServerSocket serverSocket;
    private Disposable disposable;

    @SneakyThrows
    public JdtLspProjectImporter(LogFactory logFactory, RunArgs args, Fetch fetch) {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }
        this.serverSocket = new ServerSocket(port);
        this.jdt = new JdtLspProcess(logFactory, args, fetch, port);
        this.jdt.asFlux().subscribe(new Consumer<Integer>() {
            @Override
            public void accept(Integer exitCode) {
                if (exitCode != BigInteger.ZERO.intValue()) {
                    EmitResult result = processor.tryEmitNext(exitCode);
                    if (result.isSuccess()) {
                        log.error("JDT LSP process exited with code: " + exitCode);
                        client.tryEmitError(new IllegalStateException("JDT LSP process exited with code: " + exitCode));
                    }
                }
            }
        });
        this.disposable = Mono.defer(new Supplier<Mono<Socket>>() {
            @Override
            public Mono<Socket> get() {
                try {
                    return Mono.just(serverSocket.accept());
                } catch (IOException err) {
                    return serverSocket.isClosed() ? Mono.empty() : Mono.error(err);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe(new Consumer<Socket>() {
            @Override
            public void accept(Socket accept) {
                JdtLspClient toSet = new JdtLspClient(logFactory, args, accept);
                EmitResult result = client.tryEmitNext(toSet);
                if (result.isSuccess()) {
                    log.info("JDT LSP client connected on port :" + port);
                    curr.set(toSet);
                }
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable err) {
                log.error(err.getMessage(), err);
            }
        });
    }
    @Override
    public Mono<?> load() {
        return Mono.defer(new Supplier<Mono<? extends InitializeResult>>() {
            @Override
            public Mono<InitializeResult> get() {
                StopWatch stopWatch = StopWatch.createStarted();
                CompletableFuture<StatusReport> future = new CompletableFuture<>();
                Disposable subscription = null;

                try {
                    JdtLspClient c = getClient();
                    subscription = c.asFlux().subscribe(future::complete, future::completeExceptionally);
                    InitializeResult result = c.initialize();
                    future.get(args.getImportTimeout().getSeconds(), TimeUnit.SECONDS);
                    stopWatch.stop();
                    log.info("JDT loaded project: %s in: %s ", args.getRepo(), stopWatch);
                    return Mono.just(result);
                } catch (Throwable err) {
                    return Mono.error(err);
                } finally {
                    if (Objects.nonNull(subscription)) {
                        subscription.dispose();
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @Override
    public CompletableFuture<List<? extends WorkspaceSymbol>> symbol(String query) {
        WorkspaceService service = getLangServer().getWorkspaceService();
        WorkspaceSymbolParams params = new WorkspaceSymbolParams(query);
        return service.symbol(params).thenApply(either -> {
            Preconditions.checkArgument(BooleanUtils.isTrue(either.isRight()));
            Preconditions.checkArgument(BooleanUtils.isFalse(either.isLeft()));
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
            Preconditions.checkArgument(BooleanUtils.isTrue(either.isLeft()));
            Preconditions.checkArgument(BooleanUtils.isFalse(either.isRight()));
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
            Preconditions.checkArgument(BooleanUtils.isTrue(either.isLeft()));
            Preconditions.checkArgument(BooleanUtils.isFalse(either.isRight()));
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
            Preconditions.checkArgument(BooleanUtils.isTrue(either.isLeft()));
            Preconditions.checkArgument(BooleanUtils.isFalse(either.isRight()));
            return either.getLeft();
        });
    }
    @Override
    public CompletableFuture<List<DocumentSymbol>> documentSymbol(String uri) {
        TextDocumentService service = getLangServer().getTextDocumentService();
        DocumentSymbolParams params = new DocumentSymbolParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        return service.documentSymbol(params).thenApply(l -> l.stream().map(either -> {
            Preconditions.checkArgument(BooleanUtils.isFalse(either.isLeft()));
            Preconditions.checkArgument(BooleanUtils.isTrue(either.isRight()));
            return either.getRight();
        }).collect(Collectors.toList()));
    }
    @Override
    public void close() throws IOException {
        try {
            if (Objects.nonNull(disposable)) {
                disposable.dispose();
            }
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
        return client.asFlux().blockFirst();
    }
    private LanguageServer getLangServer() {
        return getClient().get();
    }
}
