package io.codiqo.jdtls;

import static java.util.function.Predicate.not;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
import org.eclipse.lsp4j.ExecuteCommandParams;
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

import com.google.gson.JsonArray;

import io.codiqo.api.LanguageServerProjectImporter;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.util.Fetch;

public class JdtLspProjectImporter implements Lsp4jQuery, LanguageServerProjectImporter, Closeable {
    public static final int EXIT_OK = 0;
    public static final int EXIT_SIGTERM = 143;
    private static final long DETACH_TIMEOUT_SECONDS = 60;

    private final CompletableFuture<JdtLspClient> clientFuture = new CompletableFuture<>();
    private final AtomicReference<JdtLspClient> curr = new AtomicReference<>();
    private final LogFactory logFactory;
    private final Log log;
    private final RunArgs args;
    private final Fetch fetch;
    private int port;
    private JdtLspProcess jdt;
    private ServerSocket serverSocket;

    public JdtLspProjectImporter(LogFactory logFactory, RunArgs args, Fetch fetch) {
        this.logFactory = logFactory;
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        this.fetch = fetch;
    }
    @Override
    public void load() {
        StopWatch stopWatch = StopWatch.createStarted();
        for (;;) {
            try {
                start();

                JdtLspClient c = getClient();
                c.initialize();
                c.ready().get(args.getImportTimeout().getSeconds(), TimeUnit.SECONDS);
                detachShadowingProjects();
                stopWatch.stop();
                log.info("JDT loaded project: %s in: %s ", args.getGit().getWorkTree(), stopWatch);
                return;
            } catch (Throwable err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    /**
     * Removes the non-Java projects that would win the URI lookup for sources a module declares outside its own
     * directory (see {@link ShadowingProjects}). Only the workspace entry goes: jdt.ls deletes with
     * deleteContent=false, so the work tree is untouched. The deletion runs as a workspace job, so this waits until
     * the projects are gone rather than racing the first query. The job then re-runs the importers and updates
     * projects, both over the empty lists passed here, so nothing of substance is left once the deletion shows.
     *
     * <p>Never wait for it with java/buildWorkspace: autobuild is off for a reason, and a build compiles into m2e's
     * output folders, which for a module like kryo's are linked to the forked build's own target/classes. ECJ's class
     * files replace javac's, the class ids stop matching the JaCoCo execution data, and coverage drops to zero.
     */
    private void detachShadowingProjects() {
        List<File> externalRoots = ShadowingProjects.externalSourceRoots(args.getProjects());
        if (externalRoots.isEmpty()) {
            return;
        }
        /**
         * best effort: the import itself succeeded, and a language server that rejects these commands (a pinned older
         * jdtls-version) should cost the callers of those sources, not the whole analysis
         */
        try {
            detachShadowingProjects(externalRoots);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while removing the projects that hide source roots declared outside their module; queries on those sources may report no callers");
        } catch (Exception err) {
            log.warn("could not remove the projects that hide source roots declared outside their module (%s); queries on those sources may report no callers",
                    ExceptionUtils.getRootCause(err));
        }
    }
    private void detachShadowingProjects(List<File> externalRoots) throws Exception {
        Set<URI> javaProjects = new HashSet<>(projectUris(false));
        List<URI> nonJavaProjects = projectUris(true).stream().filter(not(javaProjects::contains)).toList();
        List<URI> shadowing = ShadowingProjects.select(nonJavaProjects, externalRoots);
        if (shadowing.isEmpty()) {
            return;
        }

        log.info("removing %d non-Java project(s) from the language server workspace, since they hold source roots declared outside their module and would hide them from call hierarchy queries: %s",
                shadowing.size(),
                shadowing);
        List<String> toDelete = shadowing.stream().map(URI::toString).toList();
        executeCommand("java.project.changeImportedProjects", List.of(List.of(), List.of(), toDelete));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DETACH_TIMEOUT_SECONDS);
        while (projectUris(true).stream().anyMatch(shadowing::contains)) {
            if (System.nanoTime() >= deadline) {
                log.warn("the language server still lists %s after %ds; queries on sources under them may report no callers",
                        shadowing,
                        DETACH_TIMEOUT_SECONDS);
                return;
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
    }
    private List<URI> projectUris(boolean includeNonJava) throws Exception {
        /**
         * the option travels as a JSON string: jdt.ls receives command arguments as plain objects, so a JSON object
         * arrives as a map, which JSONUtility.toModel turns into null and the handler then dereferences
         */
        List<Object> arguments = includeNonJava ? List.of("{\"includeNonJava\":true}") : List.of();
        Object result = executeCommand("java.project.getAll", arguments);
        List<URI> uris = new ArrayList<>();
        if (result instanceof JsonArray array) {
            array.forEach(element -> uris.add(URI.create(element.getAsString())));
        } else if (result instanceof Collection<?> collection) {
            collection.forEach(element -> uris.add(URI.create(String.valueOf(element))));
        }
        return uris;
    }
    private Object executeCommand(String command, List<Object> arguments) throws Exception {
        ExecuteCommandParams params = new ExecuteCommandParams(command, arguments);
        return getLangServer().getWorkspaceService().executeCommand(params).get(args.getLspQueryTimeout().getSeconds(), TimeUnit.SECONDS);
    }
    private void start() throws IOException {
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
