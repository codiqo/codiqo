package io.codiqo.core.java;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.time.StopWatch;
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
import org.slf4j.event.Level;

import com.google.common.base.Splitter;
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
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.config.UserPreferences;
import edu.umd.cs.findbugs.plugins.DuplicatePluginIdException;
import io.codiqo.api.IncomingCallsResolver;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageSpec;
import io.codiqo.api.MavenProjectSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.jdtls.JdtLspProjectImporter;
import io.codiqo.lang.spec.JInvocationBlock;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.util.Fetch;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.JvmLanguagePropertyBundle;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry;
import net.sourceforge.pmd.lang.LanguagePropertyBundle;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.ast.Node;
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
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.JavaNode;
import net.sourceforge.pmd.lang.java.ast.MethodUsage;
import net.sourceforge.pmd.lang.java.internal.JavaLanguageProperties;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.OverloadSelectionResult;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import reactor.core.publisher.Mono;

public class JavaLanguageSpec implements LanguageSpec {
    public static final EnumSet<SymbolKind> TYPES = EnumSet.of(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum);
    public static final EnumSet<SymbolKind> SYMBOLS = EnumSet.of(SymbolKind.Method, SymbolKind.Function, SymbolKind.Constructor);

    private final Log log;
    private final RunArgs args;
    private final JavaLanguageModule language = new JavaLanguageModule();
    private final Function<NodeStream<? extends JavaNode>, Collection<JInvocationBlock>> outboundASTconverter = stream -> {
        ImmutableList.Builder<JInvocationBlock> builder = ImmutableList.builder();
        stream.toStream().forEach(node -> {
            if (node instanceof MethodUsage usage) {
                OverloadSelectionResult overload = usage.getOverloadSelectionInfo();

                if (Objects.nonNull(overload) && BooleanUtils.negate(overload.isFailed())) {
                    JMethodSig signature = overload.getMethodType();
                    JTypeMirror declaringType = signature.getDeclaringType();

                    if (declaringType instanceof JClassType) {
                        JTypeDeclSymbol symbol = declaringType.getSymbol();
                        if (symbol instanceof JClassSymbol) {
                            if (BooleanUtils.negate(symbol.isUnresolved())) {
                                builder.add(new PmdJInvocationBlock(usage));
                            }
                        }
                    }
                }
            }
        });
        return builder.build();
    };
    private final IncomingCallsResolver incomingCallsResolver;
    private final JdtLspProjectImporter jdt;

    public JavaLanguageSpec(LogFactory logFactory, RunArgs args, Fetch fetch) throws IOException {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        this.jdt = new JdtLspProjectImporter(logFactory, args, fetch);
        this.incomingCallsResolver = new JdtIncomingCallsResolver(log, args, jdt);
    }
    @Override
    public Mono<?> load() {
        return jdt.load();
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
    public List<CodeBlockInfo> parse(ProjectSpec owner, Collection<File> files) throws IOException {
        ImmutableList.Builder<CodeBlockInfo> builder = ImmutableList.builder();

        LanguagePropertyBundle bundle = language.newPropertyBundle();
        bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);

        if (owner instanceof MavenProjectSpec mvn) {
            Set<String> jars = Sets.newLinkedHashSet();
            mvn.getCompileClasspathElements().stream().forEach(element -> jars.add(element.getAbsolutePath()));
            mvn.getTestClasspathElements().stream().forEach(element -> jars.add(element.getAbsolutePath()));
            bundle.setProperty(JvmLanguagePropertyBundle.AUX_CLASSPATH, jars.stream().collect(Collectors.joining(File.pathSeparator)));
        }

        LanguageRegistry languageRegistry = LanguageRegistry.singleton(language);
        ImmutableMap<Language, LanguagePropertyBundle> languageProperties = ImmutableMap.of(language, bundle);
        try (LanguageProcessorRegistry processingRegistry = LanguageProcessorRegistry.create(languageRegistry, languageProperties, log)) {
            Parser pmd = processingRegistry.getProcessor(language).services().getParser();
            SemanticErrorReporter errorReporter = SemanticErrorReporter.reportToLogger(log);

            for (File destination : files) {
                if (BooleanUtils.negate(FilenameUtils.isExtension(destination.getName(), lang().getExtensions()))) {
                    continue;
                }

                try (InputStream io = Files.newInputStream(destination.toPath())) {
                    String source = IOUtils.toString(io, StandardCharsets.UTF_8);
                    FileId fileId = FileId.fromPath(destination.toPath().normalize());
                    try (TextFile file = TextFile.forCharSeq(source, fileId, language.getDefaultVersion())) {
                        try (TextDocument doc = TextDocument.create(file)) {
                            ParserTask task = new ParserTask(doc, errorReporter, processingRegistry);
                            ASTCompilationUnit tree = (ASTCompilationUnit) pmd.parse(task);

                            Consumer<ASTExecutableDeclaration> consumer = executable -> {
                                ASTBlock block = executable.getBody();
                                ASTTypeDeclaration enclosing = executable.getEnclosingType();
                                ASTTypeDeclaration type = executable
                                        .ancestors(ASTTypeDeclaration.class)
                                        .filter(t -> BooleanUtils.negate(t instanceof ASTAnonymousClassDeclaration))
                                        .first();

                                if (Objects.nonNull(block) && BooleanUtils.negate(block.isEmpty())) {
                                    String body = executable.getText().toString();
                                    FileLocation reportLocation = tree.getTextDocument().toLocation(executable.getTextRegion());

                                    SourceLocation location = SourceLocation.builder()
                                            .startLine(reportLocation.getStartLine())
                                            .endLine(reportLocation.getEndLine())
                                            .startColumn(reportLocation.getStartColumn())
                                            .endColumn(reportLocation.getEndColumn())
                                            .build();

                                    NodeStream<JavaNode> calls = executable.descendants(JavaNode.class).filter(node -> node instanceof MethodUsage).cached();
                                    Collection<JInvocationBlock> toAdd = outboundASTconverter.apply(calls);

                                    if (executable instanceof ASTMethodDeclaration) {
                                        builder.add(JavaPmdMethodInfo.builder()
                                                .file(destination)
                                                .location(location)
                                                .type(type)
                                                .enclosingType(enclosing)
                                                .node(executable)
                                                .invocations(toAdd)
                                                .body(body)
                                                .build());
                                    } else if (executable instanceof ASTConstructorDeclaration) {
                                        builder.add(JavaPmdConstructorInfo.builder()
                                                .file(destination)
                                                .location(location)
                                                .type(type)
                                                .enclosingType(enclosing)
                                                .node(executable)
                                                .invocations(toAdd)
                                                .body(body)
                                                .build());
                                    }

                                    if (owner instanceof MavenProjectSpec mvn) {
                                        toAdd.stream().forEach(signature -> signature.accept(mvn));
                                    }
                                }
                            };

                            collectExecutables(tree, consumer);
                        }
                    }
                }
            }
        }

        return builder.build();
    }
    @Override
    public void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        capturePmdViolations(summary, analysis);
        captureSpotbugsViolations(summary, analysis);
    }
    @Override
    public void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        captureJacocoCoverage(summary, analysis);
    }
    @Override
    public int hashCode() {
        return language.hashCode();
    }
    @Override
    public boolean equals(Object other) {
        if (other instanceof JavaLanguageSpec spec) {
            return Objects.equals(language, spec.language);
        }
        return false;
    }
    @Override
    public String toString() {
        return language.toString();
    }
    @Override
    public void close() throws IOException {
        if (Objects.nonNull(jdt)) {
            jdt.close();
        }
    }
    private void captureJacocoCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        StopWatch stopWatch = StopWatch.createStarted();

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
                        log.log(Level.TRACE,
                                "checking coverage file %s modified time %s against project's latest modified time %s",
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
                if (project.getOutputDirectory().exists()) {
                    outputDirectories.add(project.getOutputDirectory());
                }
            }
        }

        if (CollectionUtils.isEmpty(outputDirectories)) {
            return;
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
            if (project instanceof MavenProjectSpec mvn) {
                for (File sourceRoot : mvn.getCompileSourceRoots()) {
                    Path normalized = sourceRoot.toPath().normalize().toAbsolutePath();
                    for (IPackageCoverage pkg : bundle.getPackages()) {
                        for (ISourceFileCoverage source : pkg.getSourceFiles()) {
                            Path sourcePath = Paths.get(source.getPackageName(), source.getName());
                            File resolved = normalized.resolve(sourcePath).normalize().toFile();
                            if (resolved.exists()) {
                                coverages.put(resolved, source);
                            }
                        }
                    }
                }
            }
        });

        /**
         * capture coverage for ALL code blocks in the project (not just commit-affected).
         * this enables full project coverage metrics for quality gates and reporting.
         */
        AtomicInteger totalBlocksWithCoverage = new AtomicInteger();
        AtomicInteger totalBlocksProcessed = new AtomicInteger();

        summary.getBlocks().asMap().forEach((file, blocksCollection) -> {
            if (FilenameUtils.isExtension(file.getName(), lang().getExtensions())) {
                ISourceFileCoverage source = coverages.get(file);
                if (Objects.nonNull(source)) {
                    for (CodeBlockInfo block : blocksCollection) {
                        if (block instanceof JavaCodeBlockInfo javaBlock) {
                            int startLine = block.getLocation().getStartLine();
                            int endLine = block.getLocation().getEndLine();
                            boolean hasCoverage = false;

                            for (int lineNum = startLine; lineNum <= endLine; lineNum++) {
                                ILine l = source.getLine(lineNum);
                                javaBlock.lineCoverage(lineNum, l);
                                if (BooleanUtils.or(new boolean[] { l.getStatus() == ICounter.FULLY_COVERED, l.getStatus() == ICounter.PARTLY_COVERED })) {
                                    hasCoverage = true;
                                }
                            }

                            totalBlocksProcessed.incrementAndGet();
                            if (hasCoverage) {
                                totalBlocksWithCoverage.incrementAndGet();
                            }
                        }
                    }
                }
            }
        });

        log.info("full project coverage: %d/%d code blocks have coverage data", totalBlocksWithCoverage.get(), totalBlocksProcessed.get());

        AtomicInteger matched = new AtomicInteger();
        AtomicInteger unmatched = new AtomicInteger();
        AtomicInteger linesWithCoverage = new AtomicInteger();

        analysis.forEach(fileAnalysis -> {
            if (fileAnalysis.isExtension(lang())) {
                ISourceFileCoverage source = coverages.get(fileAnalysis.getFile());
                if (Objects.nonNull(source)) {
                    matched.incrementAndGet();

                    if (source.getFirstLine() > 0) {
                        for (int lineNum = source.getFirstLine(); lineNum <= source.getLastLine(); lineNum++) {
                            ILine line = source.getLine(lineNum);
                            fileAnalysis.lineCoverage(lineNum, line);
                            if (BooleanUtils.or(new boolean[] { line.getStatus() == ICounter.FULLY_COVERED, line.getStatus() == ICounter.PARTLY_COVERED })) {
                                linesWithCoverage.incrementAndGet();
                            }
                        }
                    }
                } else {
                    unmatched.incrementAndGet();
                }
            }
        });
        stopWatch.stop();

        log.info("jacoco coverage analysis completed in %s", stopWatch);
        log.info("coverage analysis: %d files affected matched, %d unmatched, %d lines with coverage", matched.get(), unmatched.get(), linesWithCoverage.get());
    }
    private void capturePmdViolations(IndexingSummary summary, CommitAnalysis analysis) {
        Map<ProjectSpec, List<File>> filesByProject = Maps.newLinkedHashMap();
        List<File> orphans = Lists.newArrayList();
        for (File sourceFile : summary.getBlocks().keySet()) {
            if (FilenameUtils.isExtension(sourceFile.getName(), language.getExtensions())) {
                Optional<ProjectSpec> owner = args.owner(sourceFile);
                if (owner.isPresent()) {
                    filesByProject.computeIfAbsent(owner.get(), k -> Lists.newArrayList()).add(sourceFile);
                } else {
                    orphans.add(sourceFile);
                }
            }
        }

        List<Collection<File>> groups = Lists.newArrayList();
        groups.addAll(filesByProject.values());
        if (CollectionUtils.isNotEmpty(orphans)) {
            groups.add(orphans);
        }

        StopWatch pmdWatch = StopWatch.createStarted();
        groups.parallelStream().forEach(filesForProject -> runPmdForGroup(filesForProject, summary, analysis));
        pmdWatch.stop();

        log.info("PMD analysis completed in %s", pmdWatch);
    }
    private void runPmdForGroup(Collection<File> filesForProject, IndexingSummary summary, CommitAnalysis analysis) {
        PMDConfiguration cfg = new PMDConfiguration(LanguageRegistry.singleton(lang()));
        cfg.setReporter(log);
        cfg.setDefaultLanguageVersion(lang().getDefaultVersion());
        cfg.setIgnoreIncrementalAnalysis(true);
        cfg.setFailOnViolation(false);
        cfg.setFailOnError(true);
        cfg.setSourceEncoding(StandardCharsets.UTF_8);
        cfg.setMinimumPriority(RulePriority.valueOf(args.getPmdMinPriority().toUpperCase()));
        cfg.setThreads(BigDecimal.ONE.intValue());

        try (PmdAnalysis pmd = PmdAnalysis.create(cfg)) {
            pmd.addRuleSets(pmd.newRuleSetLoader().warnDeprecated(false).loadFromResources(args.getPmdRules()));
            MutableBoolean toApply = new MutableBoolean();

            for (File sourceFile : filesForProject) {
                if (pmd.files().addFile(sourceFile.toPath().normalize())) {
                    toApply.setTrue();
                }
            }

            if (toApply.isTrue()) {
                Map<String, File> filesByAbsolutePath = Maps.newHashMap();
                for (File sourceFile : filesForProject) {
                    filesByAbsolutePath.put(sourceFile.getAbsolutePath(), sourceFile);
                }

                Report report = pmd.performAnalysisAndCollectReport();
                report.getViolations().forEach(violation -> {
                    File sourceFile = filesByAbsolutePath.get(violation.getFileId().getAbsolutePath());
                    if (Objects.nonNull(sourceFile)) {
                        Range<Integer> markRange = Range.closed(violation.getLocation().getStartLine(), violation.getLocation().getEndLine());
                        Collection<CodeBlockInfo> blocks = summary.getBlocks().get(sourceFile);
                        for (CodeBlockInfo block : blocks) {
                            Range<Integer> blockRange = Range.closed(block.getLocation().getStartLine(), block.getLocation().getEndLine());
                            if (blockRange.encloses(markRange)) {
                                block.pmdViolation(violation);
                                if (analysis.isPresent(sourceFile, block)) {
                                    log.info("detected PMD violation for %s : line(%d:%d-%d:%d)  %s",
                                            block,
                                            violation.getBeginLine(),
                                            violation.getBeginColumn(),
                                            violation.getEndLine(),
                                            violation.getEndColumn(),
                                            violation.getDescription());
                                }
                            }
                        }
                    }
                });
            }
        }
    }
    private void captureSpotbugsViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        Map<ProjectSpec, edu.umd.cs.findbugs.Project> projects = Maps.newHashMap();
        for (ProjectSpec project : summary.getProjects()) {
            if (project instanceof MavenProjectSpec mvn) {
                File outputDir = project.getOutputDirectory();
                if (outputDir.exists()) {
                    verifyClassesNotStale(project, outputDir);
                    projects.computeIfAbsent(project, target -> {
                        edu.umd.cs.findbugs.Project spotbugs = new edu.umd.cs.findbugs.Project();
                        spotbugs.setProjectName(target.getName());
                        spotbugs.addFile(target.getOutputDirectory().getAbsolutePath());

                        mvn.getCompileClasspathElements().stream().forEach(element -> spotbugs.addAuxClasspathEntry(element.getAbsolutePath()));
                        mvn.getTestClasspathElements().stream().forEach(element -> spotbugs.addAuxClasspathEntry(element.getAbsolutePath()));

                        for (File dir : mvn.getCompileSourceRoots()) {
                            spotbugs.addSourceDirs(Collections.singletonList(dir.getAbsolutePath()));
                        }
                        for (File dir : mvn.getTestCompileSourceRoots()) {
                            spotbugs.addSourceDirs(Collections.singletonList(dir.getAbsolutePath()));
                        }

                        return spotbugs;
                    });
                }
            }
        }

        Set<Plugin> plugins = Sets.newHashSet();
        Set<String> current = Plugin.getAllPlugins()
                .stream()
                .map(Plugin::getPluginLoader)
                .map(PluginLoader::getURL)
                .map(URL::toString)
                .collect(Collectors.toSet());

        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources("findbugs.xml");
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String path = resource.toString();
                if (path.startsWith("jar:")) {
                    JarURLConnection jarConnection = (JarURLConnection) resource.openConnection();
                    resource = jarConnection.getJarFileURL();
                }

                if (current.contains(resource.toString())) {
                    continue;
                }

                try {
                    Plugin customPlugin = Plugin.addCustomPlugin(resource);
                    log.info("loaded custom spotbugs plugin from %s with id '%s'", resource, customPlugin.getPluginId());
                    plugins.add(customPlugin);
                } catch (DuplicatePluginIdException err) {
                    log.log(Level.DEBUG, "spotbugs plugin from %s already registered: %s", resource, err.getMessage());
                }
            }
        } catch (Exception err) {
            ExceptionUtils.wrapAndThrow(err);
        }

        UnhiddenDetectorFactoryCollection detectorFactory = new UnhiddenDetectorFactoryCollection(plugins);
        DetectorFactoryCollection.resetInstance(detectorFactory);

        Set<Path> sourceRoots = Sets.newHashSet();
        for (ProjectSpec project : projects.keySet()) {
            if (project instanceof MavenProjectSpec mvn) {
                mvn.getCompileSourceRoots().forEach(dir -> sourceRoots.add(dir.toPath().normalize()));
                mvn.getTestCompileSourceRoots().forEach(dir -> sourceRoots.add(dir.toPath().normalize()));
            }
        }

        Map<String, File> filesBySourcePath = Maps.newHashMap();
        for (File sourceFile : summary.getBlocks().keySet()) {
            Path normalized = sourceFile.toPath().normalize();
            for (Path rootPath : sourceRoots) {
                if (normalized.startsWith(rootPath)) {
                    filesBySourcePath.put(rootPath.relativize(normalized).toString(), sourceFile);
                    break;
                }
            }
        }

        Set<String> omitVisitors = Sets.newHashSet(
                Splitter.on(',')
                        .trimResults()
                        .omitEmptyStrings()
                        .splitToList(Optional.ofNullable(args.getSpotbugsOmitVisitors()).orElse(StringUtils.EMPTY)));

        StopWatch spotbugsWatch = StopWatch.createStarted();
        projects.values().parallelStream().forEach(spotbugs -> {
            try (StringWriter writer = new StringWriter()) {
                try (PrintWriter printer = new PrintWriter(writer)) {
                    BugCollectionBugReporter bugReporter = new BugCollectionBugReporter(spotbugs, printer);
                    bugReporter.setPriorityThreshold(args.getSpotbugsPriorityThreshold());

                    try (FindBugs2 findBugs = new FindBugs2()) {
                        findBugs.setProject(spotbugs);
                        findBugs.setBugReporter(bugReporter);
                        findBugs.setDetectorFactoryCollection(detectorFactory);
                        findBugs.setNoClassOk(false);
                        findBugs.setScanNestedArchives(false);

                        UserPreferences prefs = UserPreferences.createDefaultUserPreferences();

                        detectorFactory.factoryIterator().forEachRemaining(factory -> {
                            if (omitVisitors.contains(factory.getShortName())) {
                                prefs.enableDetector(factory, false);
                            }
                        });

                        findBugs.setUserPreferences(prefs);
                        findBugs.setAnalysisFeatureSettings(FindBugs.DEFAULT_EFFORT);
                        findBugs.execute();

                        BugCollection bugCollection = bugReporter.getBugCollection();
                        for (BugInstance bug : bugCollection) {
                            SourceLineAnnotation sourceLine = bug.getPrimarySourceLineAnnotation();
                            Range<Integer> markRange = Range.closed(sourceLine.getStartLine(), sourceLine.getEndLine());

                            File sourceFile = filesBySourcePath.get(sourceLine.getSourcePath());
                            if (Objects.nonNull(sourceFile)) {
                                Collection<CodeBlockInfo> blocks = summary.getBlocks().get(sourceFile);
                                for (CodeBlockInfo block : blocks) {
                                    Range<Integer> blockRange = Range.closed(block.getLocation().getStartLine(), block.getLocation().getEndLine());
                                    if (blockRange.encloses(markRange)) {
                                        if (block instanceof JavaCodeBlockInfo) {
                                            ((JavaCodeBlockInfo) block).spotbug(bug);
                                        }
                                        if (analysis.isPresent(sourceFile, block)) {
                                            log.info("detected spotbug violation for %s : line(%d-%d)  %s ( %s )",
                                                    block,
                                                    sourceLine.getStartLine(),
                                                    sourceLine.getEndLine(),
                                                    bug.getType(),
                                                    bug.getMessage());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception err) {
                ExceptionUtils.wrapAndThrow(err);
            } finally {
                spotbugs.close();
            }
        });

        spotbugsWatch.stop();
        log.info("spotbugs analysis completed in %s", spotbugsWatch);
    }
    private static void verifyClassesNotStale(ProjectSpec project, File outputDir) throws IOException {
        Optional<Date> lm = project.latestSourceModified();
        if (lm.isPresent()) {
            Date latestModified = lm.get();

            Optional<FileTime> latestClass;
            try (Stream<Path> walk = Files.walk(outputDir.toPath())) {
                latestClass = walk.filter(p -> p.toString().endsWith(".class"))
                        .map(p -> {
                            for (;;) {
                                try {
                                    return Files.getLastModifiedTime(p);
                                } catch (IOException err) {
                                    ExceptionUtils.wrapAndThrow(err);
                                }
                            }
                        })
                        .max(Comparator.naturalOrder());
            }

            if (latestClass.isPresent() && latestClass.get().toInstant().isBefore(latestModified.toInstant())) {
                throw new IOException(String.format(
                        "compiled classes in %s are older than sources (latest .class: %s, latest source: %s) — recompile before re-running spotbugs",
                        outputDir.getAbsolutePath(),
                        latestClass.get().toInstant(),
                        latestModified.toInstant()));
            }
        }
    }
    @Override
    public void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        incomingCallsResolver.resolve(summary, analysis);
    }
    private static void collectExecutables(Node node, Consumer<ASTExecutableDeclaration> consumer) {
        if (node instanceof ASTExecutableDeclaration) {
            consumer.accept((ASTExecutableDeclaration) node);
        }
        for (int i = 0; i < node.getNumChildren(); i++) {
            collectExecutables(node.getChild(i), consumer);
        }
    }

    private static class UnhiddenDetectorFactoryCollection extends DetectorFactoryCollection {
        public UnhiddenDetectorFactoryCollection(Collection<Plugin> enabled) {
            super(enabled);
        }
    }
}
