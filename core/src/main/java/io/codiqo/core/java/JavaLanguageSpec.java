package io.codiqo.core.java;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolKind;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.slf4j.event.Level;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageSpec;
import io.codiqo.api.Project;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.Fetch;
import io.codiqo.jdtls.JdtLspProjectImporter;
import io.codiqo.jdtls.Lsp4jGitAffectedSymbolInfo;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import net.sourceforge.pmd.lang.JvmLanguagePropertyBundle;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.LanguagePropertyBundle;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.ast.Parser;
import net.sourceforge.pmd.lang.ast.Parser.ParserTask;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.FileLocation;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.document.TextRegion;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTBlock;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;

public class JavaLanguageSpec implements LanguageSpec {
    public static final EnumSet<SymbolKind> TYPES = EnumSet.of(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum);
    public static final EnumSet<SymbolKind> SYMBOLS = EnumSet.of(SymbolKind.Method, SymbolKind.Constructor);
    public static final String ID = JavaLanguageModule.getInstance().getId();

    private final Log log;
    private final RunArgs args;
    private final JavaLanguageModule language = new JavaLanguageModule();
    @Delegate
    private final JdtLspProjectImporter importer;
    private final ImmutableList<String> rules = ImmutableList.of(
            "category/java/bestpractices.xml",
            "category/java/codestyle.xml",
            "category/java/design.xml",
            "category/java/errorprone.xml",
            "category/java/performance.xml",
            "category/java/multithreading.xml",
            "category/java/security.xml");

    public JavaLanguageSpec(LogFactory logFactory, RunArgs args, Fetch fetch) {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        this.importer = new JdtLspProjectImporter(logFactory, args, fetch);
    }
    @Override
    public Language lang() {
        return language;
    }
    @Override
    public boolean supportsCpd() {
        return true;
    }
    @Override
    public Collection<String> pmdRules() {
        return rules;
    }
    @Override
    public void identifyAffectedSymbols(FileAnalysis analysis, Object untyped, File destination, Collection<Integer> modifiedLines) {
        DocumentSymbol symbol = (DocumentSymbol) untyped;
        if (CollectionUtils.isNotEmpty(symbol.getChildren())) {
            for (DocumentSymbol child : symbol.getChildren()) {
                if (SYMBOLS.contains(child.getKind())) {
                    Position start = child.getRange().getStart();
                    Position end = child.getRange().getEnd();
                    int startLine = start.getLine();
                    int endLine = end.getLine();

                    boolean isAffected = modifiedLines.stream().anyMatch(line -> line >= startLine && line <= endLine);
                    if (isAffected) {
                        Lsp4jGitAffectedSymbolInfo info = new Lsp4jGitAffectedSymbolInfo();
                        info.setFile(destination);
                        info.setSymbol(child);
                        info.setLocation(SourceLocation.builder()
                                .startLine(startLine + BigDecimal.ONE.intValue())
                                .endLine(endLine + BigDecimal.ONE.intValue())
                                .startColumn(start.getCharacter() + BigDecimal.ONE.intValue())
                                .endColumn(end.getCharacter() + BigDecimal.ONE.intValue())
                                .build());

                        //
                        // ~ fetch incoming calls (callers) for this symbol (fails in very rare cases)
                        //
                        CallHierarchyItem item = new CallHierarchyItem();
                        item.setName(child.getName());
                        item.setKind(child.getKind());
                        item.setUri(destination.toPath().toUri().toString());
                        item.setRange(child.getRange());
                        item.setSelectionRange(child.getRange());

                        try {
                            CompletableFuture<List<CallHierarchyIncomingCall>> future = importer.callHierarchyIncomingCalls(item);
                            List<CallHierarchyIncomingCall> calls = future.get(args.getImportTimeout().getSeconds(), TimeUnit.SECONDS);
                            if (CollectionUtils.isNotEmpty(calls)) {
                                info.setIncomingCalls(calls);
                            }
                        } catch (Exception err) {
                            log.error(String.format("failed to fetch incoming calls for symbol %s in file %s: %s",
                                    child.getName(),
                                    destination.getAbsolutePath(),
                                    err.getMessage()),
                                    err);
                        }

                        log.info("identified potentially affected " + info);
                        analysis.getPotentiallyAffectedSymbols().add(info);
                    }
                }

                //
                // ~ recursively check nested types (inner classes, etc.)
                //
                if (TYPES.contains(child.getKind())) {
                    identifyAffectedSymbols(analysis, child, destination, modifiedLines);
                }
            }
        }
    }
    @Override
    @SneakyThrows
    public List<CodeBlockInfo> parse(File destination, String source) {
        ImmutableList.Builder<CodeBlockInfo> builder = ImmutableList.builder();

        LanguagePropertyBundle bundle = language.newPropertyBundle();
        bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);

        args.owningProject(destination).ifPresent(new Consumer<Project>() {
            @Override
            public void accept(Project project) {
                Set<String> jars = Sets.newLinkedHashSet();
                project.getCompileClasspathElements().stream().forEach(new Consumer<File>() {
                    @Override
                    @SneakyThrows
                    public void accept(File element) {
                        jars.add(element.getAbsolutePath());
                    }
                });
                project.getCompileClasspathElements().stream().forEach(new Consumer<File>() {
                    @Override
                    @SneakyThrows
                    public void accept(File element) {
                        jars.add(element.getAbsolutePath());
                    }
                });
                bundle.setProperty(JvmLanguagePropertyBundle.AUX_CLASSPATH, jars.stream().collect(Collectors.joining(File.pathSeparator)));
                log.log(Level.DEBUG, "configured auxiliary classpath resource: %s %s, elements: %s", project.getName(), destination, jars);
            }
        });

        try (TextFile file = TextFile.forCharSeq(source, FileId.fromPath(destination.toPath()), language.getDefaultVersion())) {
            try (TextDocument doc = TextDocument.create(file)) {
                try (LanguageProcessorRegistry registry = LanguageProcessorRegistry.create(LanguageRegistry.singleton(language),
                        ImmutableMap.of(language, bundle), log)) {
                    Parser pmd = registry.getProcessor(language).services().getParser();
                    SemanticErrorReporter errorReporter = SemanticErrorReporter.reportToLogger(log);
                    ParserTask task = new ParserTask(doc, errorReporter, registry);
                    ASTCompilationUnit tree = (ASTCompilationUnit) pmd.parse(task);
                    for (ASTTypeDeclaration type : tree.descendants(ASTTypeDeclaration.class)) {
                        for (ASTExecutableDeclaration executable : type.getDeclarations(ASTExecutableDeclaration.class)) {
                            ASTBlock block = executable.getBody();
                            TextDocument textDocument = tree.getTextDocument();
                            TextRegion textRegion = executable.getTextRegion();
                            FileLocation reportLocation = textDocument.toLocation(textRegion);

                            SourceLocation location = SourceLocation.builder()
                                    .startLine(reportLocation.getStartLine())
                                    .endLine(reportLocation.getEndLine())
                                    .startColumn(reportLocation.getStartColumn())
                                    .endColumn(reportLocation.getEndColumn())
                                    .build();

                            if (Objects.nonNull(block)) {
                                if (Boolean.FALSE.equals(block.isEmpty())) {
                                    String body = executable.getText().toString();
                                    if (Objects.nonNull(type)) {
                                        if (executable instanceof ASTMethodDeclaration) {
                                            ASTMethodDeclaration node = (ASTMethodDeclaration) executable;
                                            builder.add(JavaPmdMethodInfo.builder()
                                                    .file(destination)
                                                    .location(location)
                                                    .type(type)
                                                    .node(node)
                                                    .body(body)
                                                    .build());
                                        } else if (executable instanceof ASTConstructorDeclaration) {
                                            ASTConstructorDeclaration node = (ASTConstructorDeclaration) executable;
                                            builder.add(JavaPmdConstructorInfo.builder()
                                                    .file(destination)
                                                    .location(location)
                                                    .type(type)
                                                    .node(node)
                                                    .body(body)
                                                    .build());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return builder.build();
    }
    @Override
    public void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) {
        ExecFileLoader loader = new ExecFileLoader();
        summary.getProjects().forEach(new Consumer<Project>() {
            @Override
            public void accept(Project project) {
                project.coverage().ifPresent(new Consumer<File>() {
                    @Override
                    @SneakyThrows
                    public void accept(File exec) {
                        loader.load(exec);
                    }
                });
            }
        });

        ExecutionDataStore data = loader.getExecutionDataStore();
        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(data, coverageBuilder);

        summary.getProjects().forEach(new Consumer<Project>() {
            @Override
            public void accept(Project project) {
                project.coverage().ifPresent(new Consumer<File>() {
                    @Override
                    @SneakyThrows
                    public void accept(File exec) {
                        File output = new File(project.getOutputDirectory());
                        if (output.exists()) {
                            analyzer.analyzeAll(output);
                        }
                    }
                });
            }
        });

        IBundleCoverage bundle = coverageBuilder.getBundle(ID);

        Map<File, ISourceFileCoverage> coverages = Maps.newHashMap();
        for (Project project : summary.getProjects()) {
            for (File sourceRoot : project.getCompileSourceRoots()) {
                Path normalized = sourceRoot.toPath().toAbsolutePath().normalize();
                for (IPackageCoverage pkg : bundle.getPackages()) {
                    for (ISourceFileCoverage source : pkg.getSourceFiles()) {
                        Path sourcePath = Paths.get(source.getPackageName(), source.getName());
                        File resolved = normalized.resolve(sourcePath).normalize().toFile();
                        coverages.put(resolved, source);
                    }
                }
            }
        }

        for (FileAnalysis fileAnalysis : analysis.getFiles()) {
            if (FilenameUtils.isExtension(fileAnalysis.getFile().getName(), lang().getExtensions())) {
                ISourceFileCoverage source = coverages.get(fileAnalysis.getFile());
                if (Objects.nonNull(source)) {
                    log.info("analyzing coverage for file: " + fileAnalysis.getFile());

                    for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                        symbol.block().ifPresent(new Consumer<CodeBlockInfo>() {
                            @Override
                            public void accept(CodeBlockInfo block) {
                                int startLine = symbol.getLocation().getStartLine();
                                int endLine = symbol.getLocation().getEndLine();

                                for (int lineNum = startLine; lineNum <= endLine; lineNum++) {
                                    ILine line = source.getLine(lineNum);
                                    block.lineCoverage(lineNum, line);
                                    switch (line.getStatus()) {
                                        case ICounter.EMPTY:
                                        case ICounter.NOT_COVERED:
                                            break;
                                        case ICounter.FULLY_COVERED:
                                        case ICounter.PARTLY_COVERED:
                                        default:
                                            break;
                                    }
                                }
                            }
                        });
                    }
                }
            }
        }
    }
    @Override
    public int hashCode() {
        return language.getId().hashCode();
    }
    @Override
    public boolean equals(Object obj) {
        if (this == obj) { return true; }
        if (obj == null || getClass() != obj.getClass()) { return false; }
        return Objects.equals(language, ((JavaLanguageSpec) obj).language);
    }
    @Override
    public String toString() {
        return language.toString();
    }
    @Override
    public void close() throws IOException {
        if (Objects.nonNull(importer)) {
            importer.close();
        }
    }
}
