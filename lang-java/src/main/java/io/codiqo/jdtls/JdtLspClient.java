package io.codiqo.jdtls;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.lsp4j.CallHierarchyCapabilities;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeLensWorkspaceCapabilities;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemKindCapabilities;
import org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities;
import org.eclipse.lsp4j.DeclarationCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DiagnosticCapabilities;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.DiagnosticWorkspaceCapabilities;
import org.eclipse.lsp4j.DiagnosticsTagSupport;
import org.eclipse.lsp4j.DidChangeConfigurationCapabilities;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.FailureHandlingKind;
import org.eclipse.lsp4j.FormattingCapabilities;
import org.eclipse.lsp4j.GeneralClientCapabilities;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.ImplementationCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InlayHintCapabilities;
import org.eclipse.lsp4j.InlayHintWorkspaceCapabilities;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.OnTypeFormattingCapabilities;
import org.eclipse.lsp4j.ParameterInformationCapabilities;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RangeFormattingCapabilities;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.SemanticTokensCapabilities;
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests;
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequestsFull;
import org.eclipse.lsp4j.SemanticTokensWorkspaceCapabilities;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowDocumentResult;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelpCapabilities;
import org.eclipse.lsp4j.SignatureInformationCapabilities;
import org.eclipse.lsp4j.StaleRequestCapabilities;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolKindCapabilities;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TokenFormat;
import org.eclipse.lsp4j.TypeDefinitionCapabilities;
import org.eclipse.lsp4j.TypeHierarchyCapabilities;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.launch.LSPLauncher.Builder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.event.Level;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.api.common.AsFlux;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

class JdtLspClient implements LanguageClient, AsFlux<StatusReport>, Supplier<LanguageServer>, Closeable {
    private final Sinks.Many<StatusReport> processor = Sinks.many().multicast().directBestEffort();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Log log;
    private final RunArgs args;
    private final Launcher<LanguageServer> launcher;
    private final Future<Void> startListening;

    public JdtLspClient(LogFactory logFactory, RunArgs args, Socket socket) throws IOException {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        this.launcher = new Builder<LanguageServer>()
                .setLocalService(this)
                .setRemoteInterface(LanguageServer.class)
                .setInput(socket.getInputStream())
                .setOutput(socket.getOutputStream())
                .setExecutorService(executor)
                .create();
        this.startListening = launcher.startListening();
    }
    @Override
    public Flux<StatusReport> asFlux() {
        return processor.asFlux();
    }
    @Override
    public LanguageServer get() {
        return launcher.getRemoteProxy();
    }
    @JsonNotification("language/status")
    public void languageStatus(StatusReport params) {
        log.info("%s - %s", params.getType().name(), params.getMessage());

        switch (params.getType()) {
            case ServiceReady: {
                EmitResult emitNext = processor.tryEmitNext(params);
                if (emitNext.isSuccess()) {

                }
                break;
            }
            case Error:
            case Message:
            case ProjectStatus:
            case Started:
            case Starting:
            default:
                break;

        }
    }
    @JsonNotification("language/actionableNotification")
    public void actionableNotification(ActionableNotification notification) {
        if (notification.getCommands() != null) {
            for (Command cmd : notification.getCommands()) {
                log.info(cmd.toString());
            }
        }
    }
    @SuppressWarnings("deprecation")
    public InitializeResult initialize() throws InterruptedException, ExecutionException, TimeoutException {
        Path projectRoot = args.getGit().getWorkTree().toPath().normalize();

        int pid = (int) ProcessHandle.current().pid();

        InitializeParams params = new InitializeParams();
        params.setRootPath(projectRoot.toString());
        params.setRootUri(projectRoot.toUri().toString());
        params.setProcessId(pid);
        params.setLocale("en");
        params.setTrace("verbose");
        params.setWorkspaceFolders(ImmutableList.of(new WorkspaceFolder(projectRoot.toUri().toString(), projectRoot.getFileName().toString())));

        ClientCapabilities cap = new ClientCapabilities();
        WorkspaceClientCapabilities ws = new WorkspaceClientCapabilities();
        ws.setApplyEdit(true);
        ws.setConfiguration(true);
        ws.setWorkspaceFolders(true);

        WorkspaceEditCapabilities workspaceEdit = new WorkspaceEditCapabilities();
        workspaceEdit.setDocumentChanges(true);
        workspaceEdit.setResourceOperations(ImmutableList.of(ResourceOperationKind.Create, ResourceOperationKind.Rename, ResourceOperationKind.Delete));
        workspaceEdit.setFailureHandling(FailureHandlingKind.TextOnlyTransactional);
        workspaceEdit.setNormalizesLineEndings(true);
        ws.setWorkspaceEdit(workspaceEdit);
        ws.setDidChangeConfiguration(new DidChangeConfigurationCapabilities(true));

        DidChangeWatchedFilesCapabilities didChangeWatched = new DidChangeWatchedFilesCapabilities(true);
        didChangeWatched.setRelativePatternSupport(true);
        ws.setDidChangeWatchedFiles(didChangeWatched);
        SymbolCapabilities sym = new SymbolCapabilities();
        sym.setDynamicRegistration(true);
        sym.setSymbolKind(new SymbolKindCapabilities(IntStream.rangeClosed(1, 26).mapToObj(SymbolKind::forValue).collect(Collectors.toList())));
        ws.setSymbol(sym);
        ws.setExecuteCommand(new ExecuteCommandCapabilities(true));
        ws.setSemanticTokens(new SemanticTokensWorkspaceCapabilities(true));
        ws.setCodeLens(new CodeLensWorkspaceCapabilities(true));
        ws.setInlayHint(new InlayHintWorkspaceCapabilities(true));
        ws.setDiagnostics(new DiagnosticWorkspaceCapabilities(true));
        cap.setWorkspace(ws);

        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        SynchronizationCapabilities sync = new SynchronizationCapabilities(true, true, true);
        sync.setDynamicRegistration(true);
        textDocument.setSynchronization(sync);

        PublishDiagnosticsCapabilities publishDiagnostics = new PublishDiagnosticsCapabilities();
        publishDiagnostics.setRelatedInformation(true);
        publishDiagnostics.setTagSupport(new DiagnosticsTagSupport(ImmutableList.of(DiagnosticTag.Unnecessary, DiagnosticTag.Deprecated)));
        publishDiagnostics.setCodeDescriptionSupport(true);
        publishDiagnostics.setDataSupport(true);
        textDocument.setPublishDiagnostics(publishDiagnostics);

        CompletionCapabilities completion = new CompletionCapabilities();
        completion.setDynamicRegistration(true);
        completion.setContextSupport(true);
        CompletionItemCapabilities compItem = new CompletionItemCapabilities(false);
        compItem.setCommitCharactersSupport(true);
        compItem.setDocumentationFormat(ImmutableList.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
        compItem.setDeprecatedSupport(true);
        compItem.setPreselectSupport(true);
        compItem.setResolveSupport(new CompletionItemResolveSupportCapabilities(ImmutableList.of("documentation", "detail", "additionalTextEdits")));
        compItem.setLabelDetailsSupport(true);
        completion.setCompletionItem(compItem);
        completion.setCompletionItemKind(
                new CompletionItemKindCapabilities(IntStream.rangeClosed(1, 25).mapToObj(CompletionItemKind::forValue).collect(Collectors.toList())));
        textDocument.setCompletion(completion);
        textDocument.setHover(new HoverCapabilities(ImmutableList.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT), true));

        SignatureHelpCapabilities signatureHelp = new SignatureHelpCapabilities();
        signatureHelp.setDynamicRegistration(true);
        SignatureInformationCapabilities sigInfo = new SignatureInformationCapabilities(ImmutableList.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
        sigInfo.setParameterInformation(new ParameterInformationCapabilities(true));
        sigInfo.setActiveParameterSupport(true);
        signatureHelp.setSignatureInformation(sigInfo);
        textDocument.setSignatureHelp(signatureHelp);
        textDocument.setDefinition(new DefinitionCapabilities(true, true));
        textDocument.setTypeDefinition(new TypeDefinitionCapabilities(true, true));
        textDocument.setImplementation(new ImplementationCapabilities(true, true));
        textDocument.setReferences(new ReferencesCapabilities(true));

        DocumentSymbolCapabilities documentSymbol = new DocumentSymbolCapabilities(true);
        documentSymbol.setSymbolKind(new SymbolKindCapabilities(IntStream.rangeClosed(1, 26).mapToObj(SymbolKind::forValue).collect(Collectors.toList())));
        documentSymbol.setHierarchicalDocumentSymbolSupport(true);
        documentSymbol.setLabelSupport(true);
        textDocument.setDocumentSymbol(documentSymbol);
        textDocument.setCodeAction(new CodeActionCapabilities(true));
        textDocument.setFormatting(new FormattingCapabilities(true));
        textDocument.setRangeFormatting(new RangeFormattingCapabilities(true));
        textDocument.setOnTypeFormatting(new OnTypeFormattingCapabilities(true));

        RenameCapabilities rename = new RenameCapabilities(true, true);
        rename.setHonorsChangeAnnotations(true);
        textDocument.setRename(rename);
        textDocument.setDeclaration(new DeclarationCapabilities(true, true));
        textDocument.setCallHierarchy(new CallHierarchyCapabilities(true));
        textDocument.setTypeHierarchy(new TypeHierarchyCapabilities(true));

        SemanticTokensCapabilities semanticTokens = new SemanticTokensCapabilities(true);
        semanticTokens.setTokenTypes(
                ImmutableList.of(
                        "namespace",
                        "type",
                        "class",
                        "enum",
                        "interface",
                        "struct",
                        "typeParameter",
                        "parameter",
                        "variable",
                        "property",
                        "enumMember",
                        "event",
                        "function",
                        "method",
                        "macro",
                        "keyword",
                        "modifier",
                        "comment",
                        "string",
                        "number",
                        "regexp",
                        "operator",
                        "decorator"));
        semanticTokens.setTokenModifiers(
                ImmutableList.of(
                        "declaration",
                        "definition",
                        "readonly",
                        "static",
                        "deprecated",
                        "abstract",
                        "async",
                        "modification",
                        "documentation",
                        "defaultLibrary"));
        semanticTokens.setFormats(ImmutableList.of(TokenFormat.Relative));
        semanticTokens.setRequests(new SemanticTokensClientCapabilitiesRequests(new SemanticTokensClientCapabilitiesRequestsFull(true), true));
        textDocument.setSemanticTokens(semanticTokens);
        textDocument.setInlayHint(new InlayHintCapabilities(true));
        textDocument.setDiagnostic(new DiagnosticCapabilities(true));
        cap.setTextDocument(textDocument);

        GeneralClientCapabilities general = new GeneralClientCapabilities();
        general.setStaleRequestSupport(new StaleRequestCapabilities(true,
                ImmutableList.of(
                        "textDocument/semanticTokens/full",
                        "textDocument/semanticTokens/range",
                        "textDocument/semanticTokens/full/delta")));
        general.setPositionEncodings(ImmutableList.of(PositionEncodingKind.UTF16));
        cap.setGeneral(general);
        params.setCapabilities(cap);

        Map<String, Object> java = Maps.newHashMap();

        Map<String, Object> jdt = Maps.newHashMap();
        Map<String, Object> ls = Maps.newHashMap();
        ls.put("lombokSupport", ImmutableMap.of("enabled", true));
        ls.put("protobufSupport", ImmutableMap.of("enabled", true));
        ls.put("androidSupport", ImmutableMap.of("enabled", false));
        ls.put("javac", ImmutableMap.of("enabled", false));
        jdt.put("ls", ls);

        java.put("jdt", jdt);
        java.put("errors", ImmutableMap.of("incompleteClasspath", ImmutableMap.of("severity", "warning")));

        Map<String, Object> configuration = Maps.newHashMap();
        configuration.put("checkProjectSettingsExclusions", false);
        configuration.put("updateBuildConfiguration", "automatic");
        configuration.put("workspaceCacheLimit", 90);
        configuration.put("runtimes", ImmutableList.of());

        Map<String, Object> mavenConfig = Maps.newHashMap();
        mavenConfig.put("notCoveredPluginExecutionSeverity", "warning");
        mavenConfig.put("defaultMojoExecutionAction", "ignore");
        mavenConfig.put("lifecycleMappings", null);
        configuration.put("maven", mavenConfig);
        java.put("configuration", configuration);

        java.put("trace", ImmutableMap.of("server", "verbose"));

        Map<String, Object> importOpts = Maps.newHashMap();

        Map<String, Object> mavenImport = Maps.newHashMap();
        mavenImport.put("enabled", true);
        mavenImport.put("offline", ImmutableMap.of("enabled", false));
        mavenImport.put("disableTestClasspathFlag", false);
        importOpts.put("maven", mavenImport);

        Map<String, Object> gradleImport = Maps.newHashMap();
        gradleImport.put("enabled", true);
        gradleImport.put("wrapper", ImmutableMap.of("enabled", true));
        gradleImport.put("offline", ImmutableMap.of("enabled", false));
        gradleImport.put("annotationProcessing", ImmutableMap.of("enabled", true));
        importOpts.put("gradle", gradleImport);

        importOpts.put("exclusions", ImmutableList.of(
                "**/node_modules/**",
                "**/.metadata/**",
                "**/archetype-resources/**",
                "**/META-INF/maven/**"));
        importOpts.put("generatesMetadataFilesAtProjectRoot", false);
        java.put("import", importOpts);

        java.put("maven", ImmutableMap.of("downloadSources", true, "updateSnapshots", false));
        java.put("eclipse", ImmutableMap.of("downloadSources", true));
        java.put("signatureHelp", ImmutableMap.of("enabled", true, "description", ImmutableMap.of("enabled", true)));

        java.put("referencesCodeLens", ImmutableMap.of("enabled", true));
        java.put("implementationsCodeLens", "all");

        Map<String, Object> format = Maps.newHashMap();
        format.put("enabled", true);
        format.put("comments", ImmutableMap.of("enabled", true));
        format.put("onType", ImmutableMap.of("enabled", true));
        format.put("insertSpaces", true);
        format.put("tabSize", 4);
        java.put("format", format);

        java.put("saveActions", ImmutableMap.of("organizeImports", false));

        Map<String, Object> project = Maps.newHashMap();
        project.put("referencedLibraries", ImmutableList.of("lib/**/*.jar"));
        project.put("importOnFirstTimeStartup", "automatic");
        project.put("importHint", true);
        project.put("resourceFilters", ImmutableList.of("node_modules", "\\.git"));
        project.put("encoding", "ignore");
        project.put("sourcePaths", ImmutableList.of());
        project.put("outputPath", null);
        java.put("project", project);

        java.put("autobuild", ImmutableMap.of("enabled", args.isAutoBuild()));
        java.put("maxConcurrentBuilds", Runtime.getRuntime().availableProcessors());
        java.put("selectionRange", ImmutableMap.of("enabled", true));

        java.put("server", ImmutableMap.of("launchMode", "Standard"));
        java.put("imports", ImmutableMap.of("gradle", ImmutableMap.of("wrapper", ImmutableMap.of("checksums", ImmutableList.of()))));
        java.put("typeHierarchy", ImmutableMap.of("lazyLoad", false));
        java.put("templates", ImmutableMap.of("fileHeader", ImmutableList.of(), "typeComment", ImmutableList.of()));
        java.put("symbols", ImmutableMap.of("includeSourceMethodDeclarations", false));
        java.put("references", ImmutableMap.of("includeAccessors", true, "includeDecompiledSources", true));

        java.put("quickfix", ImmutableMap.of("showAt", "line"));
        java.put("codeAction", ImmutableMap.of("sortMembers", ImmutableMap.of("avoidVolatileChanges", true)));
        java.put("inlayHints", ImmutableMap.of("parameterNames", ImmutableMap.of("enabled", "literals", "exclusions", ImmutableList.of())));

        Map<String, Object> codeGeneration = Maps.newHashMap();
        codeGeneration.put("generateComments", false);
        codeGeneration.put("useBlocks", false);
        codeGeneration.put("insertionLocation", "lastMember");
        codeGeneration.put("addFinalForNewDeclaration", "none");

        codeGeneration.put("hashCodeEquals", ImmutableMap.of("useJava7Objects", true, "useInstanceof", true, "generateComments", false));

        codeGeneration.put("toString", ImmutableMap.of(
                "codeStyle", "STRING_CONCATENATION",
                "template", "${object.className} [${member.name()}=${member.value}, ${otherMembers}]",
                "skipNullValues", false,
                "listArrayContents", true,
                "limitElements", 0));
        java.put("codeGeneration", codeGeneration);

        java.put("compile", ImmutableMap.of("nullAnalysis", ImmutableMap.of(
                "nonnull", ImmutableList.of(
                        "javax.annotation.Nonnull",
                        "org.eclipse.jdt.annotation.NonNull",
                        "org.springframework.lang.NonNull",
                        "org.jetbrains.annotations.NotNull"),
                "nullable", ImmutableList.of(
                        "javax.annotation.Nullable",
                        "org.eclipse.jdt.annotation.Nullable",
                        "org.springframework.lang.Nullable",
                        "org.jetbrains.annotations.Nullable"),
                "nonnullbydefault", ImmutableList.of(
                        "javax.annotation.ParametersAreNonnullByDefault",
                        "org.eclipse.jdt.annotation.NonNullByDefault",
                        "org.springframework.lang.NonNullApi"),
                "mode", "automatic")));

        java.put("sharedIndexes", ImmutableMap.of("enabled", "auto", "location", ""));

        Map<String, Object> completions = Maps.newHashMap();
        completions.put("enabled", true);
        completions.put("overwrite", true);
        completions.put("favoriteStaticMembers",
                ImmutableList.of(
                        "org.junit.Assert.*",
                        "org.junit.Assume.*",
                        "org.junit.jupiter.api.Assertions.*",
                        "org.junit.jupiter.api.Assumptions.*",
                        "org.junit.jupiter.api.DynamicContainer.*",
                        "org.junit.jupiter.api.DynamicTest.*",
                        "org.mockito.Mockito.*",
                        "org.mockito.ArgumentMatchers.*",
                        "org.mockito.Answers.*",
                        "org.assertj.core.api.Assertions.*"));
        completions.put("filteredTypes",
                ImmutableList.of(
                        "java.awt.*",
                        "com.sun.*",
                        "sun.*",
                        "jdk.*",
                        "org.graalvm.*",
                        "io.micrometer.shaded.*"));
        completions.put("guessMethodArguments", "insertParameterNames");
        completions.put("importOrder", ImmutableList.of("java", "javax", "org", "com"));
        completions.put("maxResults", 0);
        completions.put("postfix", ImmutableMap.of("enabled", true));
        completions.put("chain", ImmutableMap.of("enabled", false));
        completions.put("lazyResolveTextEdit", ImmutableMap.of("enabled", true));
        completions.put("matchCase", "off");
        completions.put("collapseCompletionItems", false);
        java.put("completion", completions);

        java.put("foldingRange", ImmutableMap.of("enabled", true));
        java.put("cleanup", ImmutableMap.of("actionsOnSave", ImmutableList.of()));
        java.put("recommendations", ImmutableMap.of("dependency", ImmutableMap.of("analytics", ImmutableMap.of("show", true))));
        java.put("diagnostic", ImmutableMap.of("filter", ImmutableList.of()));
        java.put("silentNotification", false);
        java.put("showBuildStatusOnStart", ImmutableMap.of("enabled", "notification"));
        java.put("help", ImmutableMap.of("firstView", "auto", "showReleaseNotes", false, "collectErrorLog", true));

        java.put("test", ImmutableMap.of("defaultConfig", "", "config", ImmutableMap.of()));
        java.put("dependency", ImmutableMap.of("showMembers", false, "syncWithFolderExplorer", true, "autoRefresh", true, "packagePresentation", "flat"));
        java.put("refactoring", ImmutableMap.of("extract", ImmutableMap.of("interface", ImmutableMap.of("replace", true))));
        java.put("edit", ImmutableMap.of("smartSemicolonDetection", ImmutableMap.of("enabled", false), "validateAllOpenBuffersOnChanges", true));

        java.put("memberSortOrder", "T,SF,SI,SM,F,I,C,M");
        java.put("rename", ImmutableMap.of("enabled", true));
        java.put("telemetry", ImmutableMap.of("enabled", false));

        ImmutableMap<String, Serializable> toApply = ImmutableMap.of("bundles", ImmutableList.of(), "settings", ImmutableMap.of("java", java));
        params.setInitializationOptions(toApply);

        LanguageServer remoteProxy = launcher.getRemoteProxy();
        CompletableFuture<InitializeResult> task = remoteProxy.initialize(params);
        InitializeResult initResult = task.whenComplete((result, err) -> {
            if (Objects.nonNull(result)) {
                log.info("initialized project with server: %s", result.getServerInfo());
            } else {
                log.error(err.getMessage(), err);
            }
        }).get(args.getImportTimeout().getSeconds(), TimeUnit.SECONDS);

        //
        // ~ notify server that client is initialised (required by LSP specification)
        // ~ this triggers JDT LS to copy shared indexes and complete initialisation
        //
        remoteProxy.initialized(new org.eclipse.lsp4j.InitializedParams());

        return initResult;
    }
    @Override
    public void telemetryEvent(Object object) {
        log.log(Level.DEBUG, "telemetry: " + object);
    }
    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        params.getDiagnostics().forEach(diag -> log.info("[%s] L %s:%s - %s",
                diag.getSeverity(),
                diag.getRange().getStart().getLine(),
                diag.getRange().getStart().getCharacter(),
                diag.getMessage()));
    }
    @Override
    public void showMessage(MessageParams params) {
        log.info(params.toString());
    }
    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        log.info("message request:" + params.getMessage());
        return CompletableFuture.completedFuture(null);
    }
    @Override
    public void logMessage(MessageParams params) {
        switch (params.getType()) {
            case Error: {
                log.error(params.getMessage());
                break;
            }
            case Warning: {
                log.warn(params.getMessage());
                break;
            }
            case Info: {
                log.info(params.getMessage());
                break;
            }
            case Log: {
                log.log(Level.DEBUG, params.getMessage());
                break;
            }
            default: {
                throw new IllegalArgumentException("Unexpected value: " + params.getType());
            }
        }
    }
    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return CompletableFuture.completedFuture(null);
    }
    @Override
    public void notifyProgress(ProgressParams params) {
        log.log(Level.DEBUG, "progress: " + params);
    }
    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        for (Registration reg : params.getRegistrations()) {
            log.info("registering capability: %s (method: %s)", reg.getId(), reg.getMethod());
        }
        return CompletableFuture.completedFuture(null);
    }
    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        for (Unregistration unreg : params.getUnregisterations()) {
            log.info("unregistering capability: %s (method: %s)", unreg.getId(), unreg.getMethod());
        }
        return CompletableFuture.completedFuture(null);
    }
    @Override
    public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
        log.info("show document: " + params.getUri());
        return CompletableFuture.completedFuture(new ShowDocumentResult(true));
    }
    @Override
    public void close() throws IOException {
        try {
            log.info("gracefully shutting down LSP client now ...");
            LanguageServer remoteProxy = launcher.getRemoteProxy();
            remoteProxy.shutdown().get(1, TimeUnit.MINUTES);
        } catch (InterruptedException err) {
            throw new IOException(err);
        } catch (ExecutionException err) {
            throw new IOException(err.getCause());
        } catch (TimeoutException err) {
            throw new IOException(err);
        } finally {
            startListening.cancel(true);
            executor.shutdown();
        }
    }
}