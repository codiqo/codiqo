package io.codiqo.core.java;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
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
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
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
import io.codiq.lang.spec.JBinaryMethodSig;
import io.codiq.lang.spec.JavaCodeBlockInfo;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageSpec;
import io.codiqo.api.MavenProjectSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.jdtls.JdtLspProjectImporter;
import io.codiqo.jdtls.Lsp4jGitAffectedSymbolInfo;
import io.codiqo.util.Fetch;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.JvmLanguagePropertyBundle;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.LanguagePropertyBundle;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.ast.NodeStream;
import net.sourceforge.pmd.lang.ast.Parser;
import net.sourceforge.pmd.lang.ast.Parser.ParserTask;
import net.sourceforge.pmd.lang.ast.SemanticErrorReporter;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.FileLocation;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTAnonymousClassDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTBlock;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.JavaNode;
import net.sourceforge.pmd.lang.java.ast.MethodUsage;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.OverloadSelectionResult;
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
    private final Function<NodeStream<? extends JavaNode>, Collection<JBinaryMethodSig>> outboundASTconverter = stream -> {
        ImmutableList.Builder<JBinaryMethodSig> builder = ImmutableList.builder();
        stream.toStream().forEach(node -> {
            if (node instanceof MethodUsage) {
                MethodUsage usage = (MethodUsage) node;
                OverloadSelectionResult overload = usage.getOverloadSelectionInfo();

                if (Objects.nonNull(overload) && BooleanUtils.negate(overload.isFailed())) {
                    JMethodSig signature = overload.getMethodType();
                    boolean isConstructor = BooleanUtils.or(new boolean[] {
                            signature.isConstructor(),
                            node instanceof ASTConstructorCall
                    });

                    String methodName = isConstructor ? JavaBinaryFormat.CONSTRUCTOR_NAME : usage.getMethodName();
                    JTypeMirror declaringType = signature.getDeclaringType();
                    if (Objects.nonNull(declaringType)) {
                        JTypeDeclSymbol symbol = declaringType.getSymbol();
                        if (symbol instanceof JClassSymbol) {
                            JClassSymbol classSymbol = (JClassSymbol) symbol;
                            builder.add(new PmdJBinaryMethodSig(classSymbol, methodName, node, signature));
                        }
                    }
                }
            }
        });
        return builder.build();
    };

    public JavaLanguageSpec(LogFactory logFactory, RunArgs args, Fetch fetch) throws IOException {
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
                        info.setLanguage(language);
                        info.setLocation(SourceLocation.builder()
                                .startLine(startLine + BigDecimal.ONE.intValue())
                                .endLine(endLine + BigDecimal.ONE.intValue())
                                .startColumn(start.getCharacter() + BigDecimal.ONE.intValue())
                                .endColumn(end.getCharacter() + BigDecimal.ONE.intValue())
                                .build());

                        CallHierarchyItem item = new CallHierarchyItem();
                        item.setName(child.getName());
                        item.setKind(child.getKind());
                        item.setUri(destination.toPath().normalize().toUri().toString());
                        item.setRange(child.getRange());
                        item.setSelectionRange(child.getRange());

                        /**
                         * try to fetch incoming calls for this symbol (caller functions/methods).
                         * may fail in some rare cases due to bugs in JDT LS (https://github.com/eclipse-jdtls/eclipse.jdt.ls/issues/3657).
                         * should not block the analysis if it fails, just log the error and continue.
                         */
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
    public List<CodeBlockInfo> parse(File destination, String source) throws IOException {
        ImmutableList.Builder<CodeBlockInfo> builder = ImmutableList.builder();

        if (FilenameUtils.isExtension(destination.getName(), lang().getExtensions())) {
            LanguagePropertyBundle bundle = language.newPropertyBundle();
            bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);

            Optional<ProjectSpec> owner = args.owner(destination);
            owner.ifPresent(project -> {
                if (project instanceof MavenProjectSpec) {
                    MavenProjectSpec mvn = (MavenProjectSpec) project;

                    Set<String> jars = Sets.newLinkedHashSet();
                    mvn.getCompileClasspathElements().stream().forEach(element -> jars.add(element.getAbsolutePath()));
                    mvn.getTestClasspathElements().stream().forEach(element -> jars.add(element.getAbsolutePath()));
                    bundle.setProperty(JvmLanguagePropertyBundle.AUX_CLASSPATH, jars.stream().collect(Collectors.joining(File.pathSeparator)));
                }
            });

            try (TextFile file = TextFile.forCharSeq(source, FileId.fromPath(destination.toPath().normalize()), language.getDefaultVersion())) {
                try (TextDocument doc = TextDocument.create(file)) {
                    LanguageRegistry languageRegistry = LanguageRegistry.singleton(language);
                    ImmutableMap<Language, LanguagePropertyBundle> languageProperties = ImmutableMap.of(language, bundle);
                    try (LanguageProcessorRegistry processingRegistry = LanguageProcessorRegistry.create(languageRegistry, languageProperties, log)) {
                        Parser pmd = processingRegistry.getProcessor(language).services().getParser();
                        SemanticErrorReporter errorReporter = SemanticErrorReporter.reportToLogger(log);
                        ParserTask task = new ParserTask(doc, errorReporter, processingRegistry);
                        ASTCompilationUnit tree = (ASTCompilationUnit) pmd.parse(task);

                        Consumer<ASTExecutableDeclaration> consumer = executable -> {
                            ASTBlock block = executable.getBody();
                            ASTTypeDeclaration enclosing = executable.getEnclosingType();
                            ASTTypeDeclaration type = executable
                                    .ancestors(ASTTypeDeclaration.class)
                                    .filter(t -> BooleanUtils.negate(t instanceof ASTAnonymousClassDeclaration))
                                    .first();

                            if (Objects.nonNull(block) && !block.isEmpty()) {
                                String body = executable.getText().toString();
                                FileLocation reportLocation = tree.getTextDocument().toLocation(executable.getTextRegion());

                                SourceLocation location = SourceLocation.builder()
                                        .startLine(reportLocation.getStartLine())
                                        .endLine(reportLocation.getEndLine())
                                        .startColumn(reportLocation.getStartColumn())
                                        .endColumn(reportLocation.getEndColumn())
                                        .build();

                                NodeStream<JavaNode> calls = executable.descendants(JavaNode.class).filter(node -> node instanceof MethodUsage).cached();
                                Collection<JBinaryMethodSig> toAdd = outboundASTconverter.apply(calls);

                                if (executable instanceof ASTMethodDeclaration) {
                                    builder.add(JavaPmdMethodInfo.builder()
                                            .file(destination)
                                            .location(location)
                                            .type(type)
                                            .enclosingType(enclosing)
                                            .node(executable)
                                            .methodCalls(toAdd)
                                            .body(body)
                                            .build());
                                } else if (executable instanceof ASTConstructorDeclaration) {
                                    builder.add(JavaPmdConstructorInfo.builder()
                                            .file(destination)
                                            .location(location)
                                            .type(type)
                                            .enclosingType(enclosing)
                                            .node(executable)
                                            .methodCalls(toAdd)
                                            .body(body)
                                            .build());
                                }

                                owner.ifPresent(project -> {
                                    if (project instanceof MavenProjectSpec) {
                                        MavenProjectSpec mvn = (MavenProjectSpec) project;
                                        toAdd.stream().forEach(signature -> signature.accept(mvn));
                                    }
                                });
                            }
                        };

                        tree.descendants(ASTExecutableDeclaration.class).forEach(consumer);
                    }
                }
            }
        }

        return builder.build();
    }
    @Override
    public void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
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
            for (FileAnalysis fileAnalysis : analysis) {
                if (fileAnalysis.isExtension(language)) {
                    if (pmd.files().addFile(fileAnalysis.getFile().toPath().normalize())) {
                        toApply.setTrue();
                    }
                }
            }

            if (toApply.isTrue()) {
                Report report = pmd.performAnalysisAndCollectReport();
                List<RuleViolation> violations = report.getViolations();
                violations.forEach(violation -> {
                    Range<Integer> markRange = Range.closed(violation.getLocation().getStartLine(), violation.getLocation().getEndLine());
                    for (FileAnalysis fileAnalysis : analysis) {
                        if (violation.getFileId().getAbsolutePath().equals(fileAnalysis.getFile().getAbsolutePath())) {
                            for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                                Range<Integer> symbolRange = Range.closed(symbol.getLocation().getStartLine(), symbol.getLocation().getEndLine());
                                if (symbolRange.encloses(markRange)) {
                                    symbol.block().ifPresent(block -> {
                                        log.info("detected PMD violation for %s : line(%d:%d-%d:%d)  %s",
                                                block,
                                                violation.getBeginLine(),
                                                violation.getBeginColumn(),
                                                violation.getEndLine(),
                                                violation.getEndColumn(),
                                                violation.getDescription());
                                        block.pmdViolation(violation);
                                    });
                                }
                            }
                        }
                    }
                });
            }
        }

        Map<ProjectSpec, edu.umd.cs.findbugs.Project> projects = Maps.newHashMap();
        for (FileAnalysis fileAnalysis : analysis) {
            if (fileAnalysis.isExtension(language)) {
                args.owner(fileAnalysis.getFile()).ifPresent(project -> {
                    if (project instanceof MavenProjectSpec) {
                        MavenProjectSpec mvn = (MavenProjectSpec) project;
                        projects.computeIfAbsent(project, new Function<>() {
                            @Override
                            @SneakyThrows
                            public edu.umd.cs.findbugs.Project apply(ProjectSpec target) {
                                edu.umd.cs.findbugs.Project spotbugs = new edu.umd.cs.findbugs.Project();
                                spotbugs.setProjectName(target.getName());
                                spotbugs.addFile(target.getOutputDirectory().getAbsolutePath());

                                mvn.getCompileClasspathElements().stream().forEach(new Consumer<>() {
                                    @Override
                                    public void accept(File element) {
                                        spotbugs.addAuxClasspathEntry(element.getAbsolutePath());
                                    }
                                });
                                mvn.getTestClasspathElements().stream().forEach(new Consumer<>() {
                                    @Override
                                    public void accept(File element) {
                                        spotbugs.addAuxClasspathEntry(element.getAbsolutePath());
                                    }
                                });

                                for (File dir : mvn.getCompileSourceRoots()) {
                                    spotbugs.addSourceDirs(Collections.singletonList(dir.getAbsolutePath()));
                                }
                                for (File dir : mvn.getTestCompileSourceRoots()) {
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

        UnhiddenDetectorFactoryCollection detectorFactory = new UnhiddenDetectorFactoryCollection(plugins);
        DetectorFactoryCollection.resetInstance(detectorFactory);

        projects.values().parallelStream().forEach(new Consumer<>() {
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
                                for (FileAnalysis fileAnalysis : analysis) {
                                    if (fileAnalysis.getFile().toPath().normalize().endsWith(sourceLine.getSourcePath())) {
                                        for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                                            Range<Integer> symbolRange = Range.closed(symbol.getLocation().getStartLine(), symbol.getLocation().getEndLine());
                                            if (symbolRange.encloses(markRange)) {
                                                symbol.block().ifPresent(new Consumer<>() {
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
    public void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        List<File> outputDirectories = Lists.newArrayList();

        for (ProjectSpec project : summary.getProjects()) {
            Optional<File> coverage = project.coverage();
            if (coverage.isPresent()) {
                /**
                 * we have to ensure the coverage file is not older than the project's latest modified time, otherwise we have to abort
                 */
                File file = coverage.get();
                BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
                FileTime fileTime = attrs.lastModifiedTime();
                if (Objects.nonNull(fileTime)) {
                    Optional<Date> lm = project.latestModified();
                    if (lm.isPresent()) {
                        Date latestModified = lm.get();
                        log.info("checking coverage file %s modified time %s against project's latest modified time %s",
                                file.getAbsolutePath(),
                                fileTime.toInstant(),
                                latestModified.toInstant());
                        if (fileTime.toInstant().isBefore(latestModified.toInstant())) {
                            throw new IOException(String.format(
                                    "coverage file %s modified time %s is before project's latest modified time %s indicating the coverage data may be stale, please rebuild the project and rerun the tests",
                                    file.getAbsolutePath(),
                                    fileTime.toInstant(),
                                    latestModified.toInstant()));
                        }
                    }
                }
                loader.load(file);
                outputDirectories.add(project.getOutputDirectory());
            }
        }

        ExecutionDataStore data = loader.getExecutionDataStore();
        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(data, coverageBuilder);

        int totalAnalyzed = 0;
        for (File outputDir : outputDirectories) {
            int count = analyzer.analyzeAll(outputDir);
            totalAnalyzed += count;
            log.info("analyzed JaCoCo coverage for %d classes from %s", count, outputDir.getAbsolutePath());
        }

        /**
         * well this is crucial - if there are any class id mismatches between the execution data and the compiled classes,
         * we can not trust the coverage results at all, so we have to fail the analysis here.
         * otherwise risk assessment might be completely off which is unacceptable whatsoever.
         */
        Collection<IClassCoverage> noMatch = coverageBuilder.getNoMatchClasses();
        if (CollectionUtils.isNotEmpty(noMatch)) {
            log.error("class id mismatch: %d classes have execution data that doesn't match compiled classes", noMatch.size());
            noMatch.stream().forEach(cls -> log.error("  - %s", cls.getName()));
            throw new IOException("coverage analysis failed due to class id mismatches in total " + noMatch.size() + " classes");
        }

        int totalLines = 0;
        int coveredLines = 0;
        IBundleCoverage bundle = coverageBuilder.getBundle(language.getName());
        for (IPackageCoverage pkg : bundle.getPackages()) {
            for (IClassCoverage cls : pkg.getClasses()) {
                totalLines += cls.getLineCounter().getTotalCount();
                coveredLines += cls.getLineCounter().getCoveredCount();
            }
        }
        log.info("bundle '%s': %d/%d lines covered (%d packages, %d classes analyzed)",
                bundle.getName(),
                coveredLines,
                totalLines,
                bundle.getPackages().size(),
                totalAnalyzed);

        Map<File, ISourceFileCoverage> coverages = Maps.newConcurrentMap();

        summary.getProjects().forEach(project -> {
            if (project instanceof MavenProjectSpec) {
                MavenProjectSpec mvn = (MavenProjectSpec) project;
                for (File sourceRoot : mvn.getCompileSourceRoots()) {
                    Path normalized = sourceRoot.toPath().normalize().toAbsolutePath();
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

        AtomicInteger matched = new AtomicInteger();
        AtomicInteger unmatched = new AtomicInteger();
        AtomicInteger linesWithCoverage = new AtomicInteger();

        analysis.forEach(fileAnalysis -> {
            if (fileAnalysis.isExtension(lang())) {
                ISourceFileCoverage source = coverages.get(fileAnalysis.getFile());
                if (Objects.nonNull(source)) {
                    matched.incrementAndGet();
                    for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                        symbol.block().ifPresent(block -> {
                            int startLine = symbol.getLocation().getStartLine();
                            int endLine = symbol.getLocation().getEndLine();

                            for (int lineNum = startLine; lineNum <= endLine; lineNum++) {
                                ILine line = source.getLine(lineNum);
                                ((JavaCodeBlockInfo) block).lineCoverage(lineNum, line);
                                if (line.getStatus() == ICounter.FULLY_COVERED || line.getStatus() == ICounter.PARTLY_COVERED) {
                                    linesWithCoverage.incrementAndGet();
                                }
                            }
                        });
                    }
                } else {
                    unmatched.incrementAndGet();
                }
            }
        });

        log.info("coverage analysis: %d files affected matched, %d unmatched, %d lines with coverage", matched.get(), unmatched.get(), linesWithCoverage.get());
    }
    @Override
    public int hashCode() {
        return language.hashCode();
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

    private static class UnhiddenDetectorFactoryCollection extends DetectorFactoryCollection {
        public UnhiddenDetectorFactoryCollection(Collection<Plugin> enabled) {
            super(enabled);
        }
    }
}
