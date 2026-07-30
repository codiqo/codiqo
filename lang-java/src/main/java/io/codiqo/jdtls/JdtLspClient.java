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
import java.util.List;
import java.util.HashMap;

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
import org.eclipse.lsp4j.DiagnosticSeverity;
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


import io.codiqo.api.RunArgs;
import io.codiqo.api.jdtls.ServiceStatus;
import io.codiqo.api.jdtls.ServiceStatusAdapter;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;

class JdtLspClient implements LanguageClient, Supplier<LanguageServer>, Closeable {
    private final CompletableFuture<StatusReport> ready = new CompletableFuture<>();
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
                .configureGson(gb -> gb.registerTypeAdapter(ServiceStatus.class, new ServiceStatusAdapter()))
                .create();
        this.startListening = launcher.startListening();
    }
    public CompletableFuture<StatusReport> ready() {
        return ready;
    }
    @Override
    public LanguageServer get() {
        return launcher.getRemoteProxy();
    }
    @JsonNotification("language/status")
    public void languageStatus(StatusReport params) {
        log.info("%s - %s", params.getType().getJsonValue(), params.getMessage());

        switch (params.getType()) {
            case SERVICE_READY: {
                ready.complete(params);
                break;
            }
            case ERROR:
            case MESSAGE:
            case PROJECT_STATUS:
            case STARTED:
            case STARTING:
            default:
                break;
        }
    }
    @JsonNotification("language/actionableNotification")
    public void actionableNotification(ActionableNotification notification) {
        if (Objects.nonNull(notification.getCommands())) {
            for (Command cmd : notification.getCommands()) {
                log.info(cmd.toString());
            }
        }
    }
    public InitializeResult initialize() throws InterruptedException, ExecutionException, TimeoutException {
        Path projectRoot = args.getGit().getWorkTree().toPath().normalize();

        int pid = (int) ProcessHandle.current().pid();

        InitializeParams params = new InitializeParams();
        params.setRootPath(projectRoot.toString());
        params.setRootUri(projectRoot.toUri().toString());
        params.setProcessId(pid);
        params.setLocale("en");
        params.setTrace("verbose");
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(projectRoot.toUri().toString(), projectRoot.getFileName().toString())));

        ClientCapabilities cap = new ClientCapabilities();
        WorkspaceClientCapabilities ws = new WorkspaceClientCapabilities();
        ws.setApplyEdit(true);
        ws.setConfiguration(true);
        ws.setWorkspaceFolders(true);

        WorkspaceEditCapabilities workspaceEdit = new WorkspaceEditCapabilities();
        workspaceEdit.setDocumentChanges(true);
        workspaceEdit.setResourceOperations(List.of(ResourceOperationKind.Create, ResourceOperationKind.Rename, ResourceOperationKind.Delete));
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
        publishDiagnostics.setTagSupport(new DiagnosticsTagSupport(List.of(DiagnosticTag.Unnecessary, DiagnosticTag.Deprecated)));
        publishDiagnostics.setCodeDescriptionSupport(true);
        publishDiagnostics.setDataSupport(true);
        textDocument.setPublishDiagnostics(publishDiagnostics);

        CompletionCapabilities completion = new CompletionCapabilities();
        completion.setDynamicRegistration(true);
        completion.setContextSupport(true);
        CompletionItemCapabilities compItem = new CompletionItemCapabilities(false);
        compItem.setCommitCharactersSupport(true);
        compItem.setDocumentationFormat(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
        compItem.setDeprecatedSupport(true);
        compItem.setPreselectSupport(true);
        compItem.setResolveSupport(new CompletionItemResolveSupportCapabilities(List.of("documentation", "detail", "additionalTextEdits")));
        compItem.setLabelDetailsSupport(true);
        completion.setCompletionItem(compItem);
        completion.setCompletionItemKind(
                new CompletionItemKindCapabilities(IntStream.rangeClosed(1, 25).mapToObj(CompletionItemKind::forValue).collect(Collectors.toList())));
        textDocument.setCompletion(completion);
        textDocument.setHover(new HoverCapabilities(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT), true));

        SignatureHelpCapabilities signatureHelp = new SignatureHelpCapabilities();
        signatureHelp.setDynamicRegistration(true);
        SignatureInformationCapabilities sigInfo = new SignatureInformationCapabilities(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
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
                List.of(
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
                List.of(
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
        semanticTokens.setFormats(List.of(TokenFormat.Relative));
        semanticTokens.setRequests(new SemanticTokensClientCapabilitiesRequests(new SemanticTokensClientCapabilitiesRequestsFull(true), true));
        textDocument.setSemanticTokens(semanticTokens);
        textDocument.setInlayHint(new InlayHintCapabilities(true));
        textDocument.setDiagnostic(new DiagnosticCapabilities(true));
        cap.setTextDocument(textDocument);

        GeneralClientCapabilities general = new GeneralClientCapabilities();
        general.setStaleRequestSupport(new StaleRequestCapabilities(true,
                List.of(
                        "textDocument/semanticTokens/full",
                        "textDocument/semanticTokens/range",
                        "textDocument/semanticTokens/full/delta")));
        general.setPositionEncodings(List.of(PositionEncodingKind.UTF16));
        cap.setGeneral(general);
        params.setCapabilities(cap);

        Map<String, Object> java = new HashMap<>();

        Map<String, Object> jdt = new HashMap<>();
        Map<String, Object> ls = new HashMap<>();
        ls.put("lombokSupport", Map.of("enabled", true));
        ls.put("protobufSupport", Map.of("enabled", true));
        ls.put("androidSupport", Map.of("enabled", true));
        ls.put("aspectjSupportEnabled", Map.of("enabled", true));
        ls.put("javac", Map.of("enabled", false));
        jdt.put("ls", ls);

        java.put("jdt", jdt);
        java.put("errors", Map.of("incompleteClasspath", Map.of("severity", "warning")));

        Map<String, Object> configuration = new HashMap<>();
        configuration.put("checkProjectSettingsExclusions", false);
        configuration.put("updateBuildConfiguration", "automatic");
        configuration.put("workspaceCacheLimit", 90);
        configuration.put("runtimes", List.of());

        Map<String, Object> mavenConfig = new HashMap<>();
        mavenConfig.put("notCoveredPluginExecutionSeverity", "warning");
        mavenConfig.put("defaultMojoExecutionAction", "ignore");
        mavenConfig.put("lifecycleMappings", null);
        configuration.put("maven", mavenConfig);
        java.put("configuration", configuration);

        java.put("trace", Map.of("server", "verbose"));

        Map<String, Object> importOpts = new HashMap<>();

        Map<String, Object> mavenImport = new HashMap<>();
        mavenImport.put("enabled", true);
        mavenImport.put("offline", Map.of("enabled", false));
        mavenImport.put("disableTestClasspathFlag", false);
        importOpts.put("maven", mavenImport);

        Map<String, Object> gradleImport = new HashMap<>();
        gradleImport.put("enabled", true);
        gradleImport.put("wrapper", Map.of("enabled", true));
        gradleImport.put("offline", Map.of("enabled", false));
        gradleImport.put("annotationProcessing", Map.of("enabled", true));
        importOpts.put("gradle", gradleImport);

        importOpts.put("exclusions", List.of(
                "**/node_modules/**",
                "**/.metadata/**",
                "**/archetype-resources/**",
                "**/META-INF/maven/**"));
        importOpts.put("generatesMetadataFilesAtProjectRoot", false);
        java.put("import", importOpts);

        java.put("maven", Map.of("downloadSources", false, "updateSnapshots", false));
        java.put("eclipse", Map.of("downloadSources", false));
        java.put("signatureHelp", Map.of("enabled", true, "description", Map.of("enabled", true)));

        java.put("referencesCodeLens", Map.of("enabled", true));
        java.put("implementationsCodeLens", "all");

        Map<String, Object> format = new HashMap<>();
        format.put("enabled", true);
        format.put("comments", Map.of("enabled", true));
        format.put("onType", Map.of("enabled", true));
        format.put("insertSpaces", true);
        format.put("tabSize", 4);
        java.put("format", format);

        java.put("saveActions", Map.of("organizeImports", false));

        Map<String, Object> project = new HashMap<>();
        project.put("referencedLibraries", List.of("lib/**/*.jar"));
        project.put("importOnFirstTimeStartup", "automatic");
        project.put("importHint", true);
        project.put("resourceFilters", List.of("node_modules", "\\.git"));
        project.put("encoding", "ignore");
        project.put("sourcePaths", List.of());
        project.put("outputPath", null);
        java.put("project", project);

        java.put("autobuild", Map.of("enabled", args.isAutoBuild()));
        java.put("maxConcurrentBuilds", Runtime.getRuntime().availableProcessors());
        java.put("selectionRange", Map.of("enabled", true));

        java.put("server", Map.of("launchMode", "Standard"));
        java.put("imports", Map.of("gradle", Map.of("wrapper", Map.of("checksums", List.of()))));
        java.put("typeHierarchy", Map.of("lazyLoad", false));
        java.put("templates", Map.of("fileHeader", List.of(), "typeComment", List.of()));
        java.put("symbols", Map.of("includeSourceMethodDeclarations", false));
        java.put("search", Map.of("scope", "main"));
        java.put("references", Map.of("includeAccessors", true, "includeDecompiledSources", args.isJdtIncludeDecompiledSources()));

        java.put("quickfix", Map.of("showAt", "line"));
        java.put("codeAction", Map.of("sortMembers", Map.of("avoidVolatileChanges", true)));
        java.put("inlayHints", Map.of("parameterNames", Map.of("enabled", "literals", "exclusions", List.of())));

        Map<String, Object> codeGeneration = new HashMap<>();
        codeGeneration.put("generateComments", false);
        codeGeneration.put("useBlocks", false);
        codeGeneration.put("insertionLocation", "lastMember");
        codeGeneration.put("addFinalForNewDeclaration", "none");

        codeGeneration.put("hashCodeEquals", Map.of("useJava7Objects", true, "useInstanceof", true, "generateComments", false));

        codeGeneration.put("toString", Map.of(
                "codeStyle", "STRING_CONCATENATION",
                "template", "${object.className} [${member.name()}=${member.value}, ${otherMembers}]",
                "skipNullValues", false,
                "listArrayContents", true,
                "limitElements", 0));
        java.put("codeGeneration", codeGeneration);

        java.put("compile", Map.of("nullAnalysis", Map.of(
                "nonnull", List.of(
                        "javax.annotation.Nonnull",
                        "org.eclipse.jdt.annotation.NonNull",
                        "org.springframework.lang.NonNull",
                        "org.jetbrains.annotations.NotNull"),
                "nullable", List.of(
                        "javax.annotation.Nullable",
                        "org.eclipse.jdt.annotation.Nullable",
                        "org.springframework.lang.Nullable",
                        "org.jetbrains.annotations.Nullable"),
                "nonnullbydefault", List.of(
                        "javax.annotation.ParametersAreNonnullByDefault",
                        "org.eclipse.jdt.annotation.NonNullByDefault",
                        "org.springframework.lang.NonNullApi"),
                "mode", "automatic")));

        java.put("sharedIndexes", Map.of("enabled", args.isJdtUseSharedIndex() ? "auto" : "off"));

        Map<String, Object> completions = new HashMap<>();
        completions.put("enabled", true);
        completions.put("overwrite", true);
        completions.put("favoriteStaticMembers",
                List.of(
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
                List.of(
                        "java.awt.*",
                        "com.sun.*",
                        "sun.*",
                        "jdk.*",
                        "org.graalvm.*",
                        "io.micrometer.shaded.*"));
        completions.put("guessMethodArguments", "insertParameterNames");
        completions.put("importOrder", List.of("java", "javax", "org", "com"));
        completions.put("maxResults", 0);
        completions.put("postfix", Map.of("enabled", true));
        completions.put("chain", Map.of("enabled", false));
        completions.put("lazyResolveTextEdit", Map.of("enabled", true));
        completions.put("matchCase", "off");
        completions.put("collapseCompletionItems", false);
        java.put("completion", completions);

        java.put("foldingRange", Map.of("enabled", true));
        java.put("cleanup", Map.of("actionsOnSave", List.of()));
        java.put("recommendations", Map.of("dependency", Map.of("analytics", Map.of("show", true))));
        java.put("diagnostic", Map.of("filter", List.of()));
        java.put("silentNotification", false);
        java.put("showBuildStatusOnStart", Map.of("enabled", "notification"));
        java.put("help", Map.of("firstView", "auto", "showReleaseNotes", false, "collectErrorLog", true));

        java.put("test", Map.of("defaultConfig", "", "config", Map.of()));
        java.put("dependency", Map.of("showMembers", false, "syncWithFolderExplorer", true, "autoRefresh", true, "packagePresentation", "flat"));
        java.put("refactoring", Map.of("extract", Map.of("interface", Map.of("replace", true))));
        java.put("edit", Map.of("smartSemicolonDetection", Map.of("enabled", false), "validateAllOpenBuffersOnChanges", true));

        java.put("memberSortOrder", "T,SF,SI,SM,F,I,C,M");
        java.put("rename", Map.of("enabled", true));
        java.put("telemetry", Map.of("enabled", false));

        Map<String, Serializable> toApply = Map.<String, Serializable>of("bundles", (Serializable) List.of(), "settings", (Serializable) Map.of("java", java));
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
        /**
         * a failed project import arrives here as an Error diagnostic on the module POM, and it is the only
         * signal that the workspace has no Java model — at info level it stayed buried in millions of build
         * lines while every call-hierarchy query silently answered null and the analysis reported zero callers
         */
        params.getDiagnostics().forEach(diag -> log.log(
                DiagnosticSeverity.Error.equals(diag.getSeverity()) ? Level.WARN : Level.INFO,
                "[%s] L %s:%s - %s",
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