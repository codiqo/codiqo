package io.codiqo.core.java;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;

import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugCollectionBugReporter;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.Plugin;
import edu.umd.cs.findbugs.PluginLoader;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.config.UserPreferences;
import edu.umd.cs.findbugs.plugins.DuplicatePluginIdException;
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
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
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
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

public class JavaLanguageSpec implements LanguageSpec {
    public static final EnumSet<SymbolKind> TYPES = EnumSet.of(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum);
    public static final EnumSet<SymbolKind> SYMBOLS = EnumSet.of(SymbolKind.Method, SymbolKind.Function, SymbolKind.Constructor);

    private final Log log;
    private final RunArgs args;
    private final JavaLanguageModule language = new JavaLanguageModule();
    @Delegate
    private final JdtLspProjectImporter importer;
    private final ImmutableList<String> pmdRules = ImmutableList.of(
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

        if (FilenameUtils.isExtension(destination.getName(), lang().getExtensions())) {
            LanguagePropertyBundle bundle = language.newPropertyBundle();
            bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);

            args.owner(destination).ifPresent(new Consumer<Project>() {
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
                    project.getTestClasspathElements().stream().forEach(new Consumer<File>() {
                        @Override
                        @SneakyThrows
                        public void accept(File element) {
                            jars.add(element.getAbsolutePath());
                        }
                    });
                    bundle.setProperty(JvmLanguagePropertyBundle.AUX_CLASSPATH, jars.stream().collect(Collectors.joining(File.pathSeparator)));
                }
            });

            try (TextFile file = TextFile.forCharSeq(source, FileId.fromPath(destination.toPath()), language.getDefaultVersion())) {
                try (TextDocument doc = TextDocument.create(file)) {
                    LanguageRegistry languageRegistry = LanguageRegistry.singleton(language);
                    ImmutableMap<Language, LanguagePropertyBundle> languageProperties = ImmutableMap.of(language, bundle);
                    try (LanguageProcessorRegistry processingRegistry = LanguageProcessorRegistry.create(languageRegistry, languageProperties, log)) {
                        Parser pmd = processingRegistry.getProcessor(language).services().getParser();
                        SemanticErrorReporter errorReporter = SemanticErrorReporter.reportToLogger(log);
                        ParserTask task = new ParserTask(doc, errorReporter, processingRegistry);
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
        }

        return builder.build();
    }
    @Override
    public void captureViolations(IndexingSummary summary, CommitAnalysis analysis) {
        PMDConfiguration cfg = new PMDConfiguration(LanguageRegistry.singleton(lang()));
        cfg.setReporter(log);
        cfg.setDefaultLanguageVersion(lang().getDefaultVersion());
        cfg.setIgnoreIncrementalAnalysis(true);
        cfg.setFailOnViolation(false);
        cfg.setFailOnError(true);
        cfg.setSourceEncoding(StandardCharsets.UTF_8);
        cfg.setMinimumPriority(RulePriority.HIGH);
        pmdRules.forEach(cfg::addRuleSet);

        try (PmdAnalysis pmd = PmdAnalysis.create(cfg)) {
            MutableBoolean toApply = new MutableBoolean();
            for (FileAnalysis fileAnalysis : analysis.getFiles()) {
                if (fileAnalysis.isExtension(language)) {
                    if (pmd.files().addFile(fileAnalysis.getFile().toPath())) {
                        toApply.setTrue();
                    }
                }
            }

            if (toApply.isTrue()) {
                Report report = pmd.performAnalysisAndCollectReport();
                List<RuleViolation> violations = report.getViolations();
                violations.forEach(new Consumer<RuleViolation>() {
                    @Override
                    @SneakyThrows
                    public void accept(RuleViolation violation) {
                        Range<Integer> markRange = Range.closed(violation.getLocation().getStartLine(), violation.getLocation().getEndLine());
                        for (FileAnalysis fileAnalysis : analysis.getFiles()) {
                            if (violation.getFileId().getAbsolutePath().equals(fileAnalysis.getFile().getAbsolutePath())) {
                                for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                                    Range<Integer> symbolRange = Range.closed(symbol.getLocation().getStartLine(), symbol.getLocation().getEndLine());
                                    if (symbolRange.encloses(markRange)) {
                                        symbol.block().ifPresent(new Consumer<CodeBlockInfo>() {
                                            @Override
                                            public void accept(CodeBlockInfo block) {
                                                log.info("detected PMD violation for %s : line(%d:%d-%d:%d)  %s",
                                                        block,
                                                        violation.getBeginLine(),
                                                        violation.getBeginColumn(),
                                                        violation.getEndLine(),
                                                        violation.getEndColumn(),
                                                        violation.getDescription());
                                                block.pmdViolation(violation);
                                            }
                                        });
                                    }
                                }
                            }
                        }
                    }
                });
            }
        }

        Map<Project, edu.umd.cs.findbugs.Project> perProject = Maps.newHashMap();
        for (FileAnalysis fileAnalysis : analysis.getFiles()) {
            if (fileAnalysis.isExtension(language)) {
                args.owner(fileAnalysis.getFile()).ifPresent(new Consumer<Project>() {
                    @Override
                    public void accept(Project project) {
                        perProject.computeIfAbsent(project, new Function<Project, edu.umd.cs.findbugs.Project>() {
                            @Override
                            @SneakyThrows
                            public edu.umd.cs.findbugs.Project apply(Project target) {
                                edu.umd.cs.findbugs.Project spotbugs = new edu.umd.cs.findbugs.Project();
                                spotbugs.setProjectName(target.getName());
                                spotbugs.addFile(target.getOutputDirectory().getAbsolutePath());

                                target.getCompileClasspathElements().stream().forEach(new Consumer<File>() {
                                    @Override
                                    public void accept(File element) {
                                        spotbugs.addAuxClasspathEntry(element.getAbsolutePath());
                                    }
                                });
                                target.getTestClasspathElements().stream().forEach(new Consumer<File>() {
                                    @Override
                                    public void accept(File element) {
                                        spotbugs.addAuxClasspathEntry(element.getAbsolutePath());
                                    }
                                });

                                for (File dir : target.getCompileSourceRoots()) {
                                    spotbugs.addSourceDirs(Collections.singletonList(dir.getAbsolutePath()));
                                }
                                for (File dir : target.getTestCompileSourceRoots()) {
                                    spotbugs.addSourceDirs(Collections.singletonList(dir.getAbsolutePath()));
                                }

                                return spotbugs;
                            }
                        });
                    }
                });
            }
        }

        Set<Plugin> plugins = Sets.newHashSet();
        Set<URL> current = Plugin.getAllPlugins().stream().map(Plugin::getPluginLoader).map(PluginLoader::getURL).collect(Collectors.toSet());

        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources("findbugs.xml");
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String path = resource.toString();
                if (path.startsWith("jar:")) {
                    JarURLConnection jarConnection = (JarURLConnection) resource.openConnection();
                    resource = jarConnection.getJarFileURL();
                }

                if (current.contains(resource)) {
                    continue;
                }

                try {
                    plugins.add(Plugin.addCustomPlugin(resource));
                } catch (DuplicatePluginIdException err) {

                }
            }
        } catch (Exception err) {
            ExceptionUtils.wrapAndThrow(err);
        }

        SpotbugsDetectors detectorFactory = new SpotbugsDetectors(plugins);
        DetectorFactoryCollection.resetInstance(detectorFactory);

        perProject.values().parallelStream().forEach(new Consumer<edu.umd.cs.findbugs.Project>() {
            @Override
            @SneakyThrows
            public void accept(edu.umd.cs.findbugs.Project spotbugs) {
                try (StringWriter writer = new StringWriter()) {
                    try (PrintWriter printer = new PrintWriter(writer)) {
                        BugCollectionBugReporter bugReporter = new BugCollectionBugReporter(spotbugs, printer);
                        bugReporter.setPriorityThreshold(Priorities.HIGH_PRIORITY);

                        try (FindBugs2 findBugs = new FindBugs2()) {
                            findBugs.setProject(spotbugs);
                            findBugs.setBugReporter(bugReporter);
                            findBugs.setDetectorFactoryCollection(detectorFactory);

                            UserPreferences prefs = UserPreferences.createDefaultUserPreferences();
                            prefs.setEffort(UserPreferences.EFFORT_DEFAULT);

                            findBugs.setUserPreferences(prefs);
                            findBugs.setAnalysisFeatureSettings(FindBugs.DEFAULT_EFFORT);
                            findBugs.execute();

                            BugCollection bugCollection = bugReporter.getBugCollection();
                            for (BugInstance bug : bugCollection) {
                                SourceLineAnnotation sourceLine = bug.getPrimarySourceLineAnnotation();
                                Range<Integer> markRange = Range.closed(sourceLine.getStartLine(), sourceLine.getEndLine());
                                for (FileAnalysis fileAnalysis : analysis.getFiles()) {
                                    if (fileAnalysis.getFile().toPath().endsWith(sourceLine.getSourcePath())) {
                                        for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                                            Range<Integer> symbolRange = Range.closed(symbol.getLocation().getStartLine(), symbol.getLocation().getEndLine());
                                            if (symbolRange.encloses(markRange)) {
                                                symbol.block().ifPresent(new Consumer<CodeBlockInfo>() {
                                                    @Override
                                                    public void accept(CodeBlockInfo block) {
                                                        log.info("detected spotbug violation for %s : line(%d-%d)  %s ( %s )",
                                                                block,
                                                                sourceLine.getStartLine(),
                                                                sourceLine.getEndLine(),
                                                                bug.getType(),
                                                                bug.getMessage());
                                                        ((JavaCodeBlockInfo) block).spotbug(bug);
                                                    }
                                                });
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    spotbugs.close();
                }
            }
        });
    }
    @Override
    public void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) {
        ExecFileLoader loader = new ExecFileLoader();
        MutableBoolean toApply = new MutableBoolean();
        summary.getProjects().forEach(new Consumer<Project>() {
            @Override
            public void accept(Project project) {
                project.coverage().ifPresent(new Consumer<File>() {
                    @Override
                    @SneakyThrows
                    public void accept(File exec) {
                        loader.load(exec);
                        toApply.setTrue();
                    }
                });
            }
        });

        if (toApply.isTrue()) {
            ExecutionDataStore data = loader.getExecutionDataStore();
            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(data, coverageBuilder);

            summary.getProjects().parallelStream().forEach(new Consumer<Project>() {
                @Override
                public void accept(Project project) {
                    project.coverage().ifPresent(new Consumer<File>() {
                        @Override
                        @SneakyThrows
                        public void accept(File exec) {
                            if (project.getOutputDirectory().exists()) {
                                analyzer.analyzeAll(project.getOutputDirectory());
                            }
                        }
                    });
                }
            });

            IBundleCoverage bundle = coverageBuilder.getBundle(language.getName());
            Map<File, ISourceFileCoverage> coverages = Maps.newConcurrentMap();
            summary.getProjects().forEach(new Consumer<Project>() {
                @Override
                public void accept(Project project) {
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
            });

            analysis.getFiles().forEach(new Consumer<FileAnalysis>() {
                @Override
                public void accept(FileAnalysis fileAnalysis) {
                    if (fileAnalysis.isExtension(lang())) {
                        ISourceFileCoverage source = coverages.get(fileAnalysis.getFile());
                        if (Objects.nonNull(source)) {
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
            });
        }
    }
    @Override
    public int hashCode() {
        return language.getId().hashCode();
    }
    @Override
    public boolean equals(Object other) {
        return Objects.equals(language, ((JavaLanguageSpec) other).language);
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
