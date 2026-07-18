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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
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
import io.codiqo.api.JvmProjectSpec;
import io.codiqo.api.LanguageSpec;
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
import io.codiqo.util.Split;
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
        List<JInvocationBlock> builder = new ArrayList<>();
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
        return List.copyOf(builder);
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
        List<CodeBlockInfo> builder = new ArrayList<>();

        LanguagePropertyBundle bundle = language.newPropertyBundle();
        bundle.setProperty(JavaLanguageProperties.FIRST_CLASS_LOMBOK, true);

        if (owner instanceof JvmProjectSpec jvm) {
            Set<String> jars = new LinkedHashSet<>();
            jvm.getCompileClasspathElements().stream().forEach(element -> jars.add(element.getAbsolutePath()));
            jvm.getTestClasspathElements().stream().forEach(element -> jars.add(element.getAbsolutePath()));
            bundle.setProperty(JvmLanguagePropertyBundle.AUX_CLASSPATH, jars.stream().collect(Collectors.joining(File.pathSeparator)));
        }

        LanguageRegistry languageRegistry = LanguageRegistry.singleton(language);
        Map<Language, LanguagePropertyBundle> languageProperties = Map.of(language, bundle);
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
                            List<CodeBlockInfo> fileBlocks = new ArrayList<>();

                            /**
                             * PMD's type inference can crash on valid code it fails to disambiguate
                             * (e.g. diamond anonymous classes like "new TypeToken<>() {}", pmd/pmd#4436) —
                             * degrade to zero code units for the offending file instead of failing the run
                             */
                            try {
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
                                            fileBlocks.add(JavaPmdMethodInfo.builder()
                                                    .file(destination)
                                                    .location(location)
                                                    .type(type)
                                                    .enclosingType(enclosing)
                                                    .node(executable)
                                                    .invocations(toAdd)
                                                    .body(body)
                                                    .build());
                                        } else if (executable instanceof ASTConstructorDeclaration) {
                                            fileBlocks.add(JavaPmdConstructorInfo.builder()
                                                    .file(destination)
                                                    .location(location)
                                                    .type(type)
                                                    .enclosingType(enclosing)
                                                    .node(executable)
                                                    .invocations(toAdd)
                                                    .body(body)
                                                    .build());
                                        }

                                        if (owner instanceof JvmProjectSpec jvm) {
                                            toAdd.stream().forEach(signature -> signature.accept(jvm));
                                        }
                                    }
                                };

                                collectExecutables(tree, consumer);
                                builder.addAll(fileBlocks);
                            } catch (RuntimeException err) {
                                log.warn("PMD failed to analyze %s, skipping code unit indexing for this file: %s", destination, ExceptionUtils.getRootCauseMessage(err));
                            }
                        }
                    }
                }
            }
        }

        return List.copyOf(builder);
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

        List<File> outputDirectories = new CopyOnWriteArrayList<>();
        List<String> uninstrumentedModules = new CopyOnWriteArrayList<>();
        Set<File> loadedCoverageFiles = ConcurrentHashMap.newKeySet();

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
                /**
                 * aggregated destFile configurations point every module at the same file — load it once.
                 * the per-module staleness check above still runs for each module.
                 */
                if (loadedCoverageFiles.add(file)) {
                    loader.load(file);
                }
                if (project.getOutputDirectory().exists()) {
                    outputDirectories.add(project.getOutputDirectory());
                }
            } else if (args.isFailOnUninstrumentedModule() && expectsCoverage(project)) {
                uninstrumentedModules.add(project.getName());
            }
        }

        if (CollectionUtils.isNotEmpty(uninstrumentedModules)) {
            throw new IOException(String.format(
                    "coverage was required (codiqo.failOnUninstrumentedModule) but no coverage data was produced for module(s) with tests: %s — the JaCoCo agent did not attach or no tests executed",
                    String.join(", ", uninstrumentedModules)));
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
         * a class id mismatch means the execution data was recorded against different bytes than target/classes —
         * runtime-transforming agents (e.g. allure's aspectjweaver rewriting @Aspect classes) cause benign isolated
         * mismatches, so untrusted classes are excluded from coverage (conservatively uncovered) instead of failing
         * the analysis. the guard stays absolute for the code being SCORED: a mismatch on one of the commit's changed
         * files would corrupt its risk assessment, so that still fails hard.
         */
        Collection<IClassCoverage> noMatch = coverageBuilder.getNoMatchClasses();
        if (CollectionUtils.isNotEmpty(noMatch)) {
            List<IClassCoverage> changedFileMismatches = noMatch.stream()
                    .filter(cls -> touchesAnalyzedFile(cls, analysis))
                    .toList();
            if (CollectionUtils.isNotEmpty(changedFileMismatches)) {
                changedFileMismatches.forEach(cls -> log.error("  - %s", cls.getName()));
                throw new IOException(String.format(
                        "coverage analysis failed: %d of the commit's changed classes have execution data that doesn't match compiled classes — the compiled output appears stale, please rebuild the project and rerun the tests",
                        changedFileMismatches.size()));
            }
            log.warn("class id mismatch: %d classes excluded from coverage (none among the commit's changed files) — typically another -javaagent (e.g. aspectjweaver) transformed them at load time", noMatch.size());
            noMatch.stream().forEach(cls -> log.warn("  - %s", cls.getName()));
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

        Map<File, ISourceFileCoverage> coverages = new ConcurrentHashMap<>();

        summary.getProjects().forEach(project -> {
            if (project instanceof JvmProjectSpec jvm) {
                for (File sourceRoot : jvm.getCompileSourceRoots()) {
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
        Map<ProjectSpec, List<File>> filesByProject = new LinkedHashMap<>();
        List<File> orphans = new ArrayList<>();
        for (File sourceFile : summary.getBlocks().keySet()) {
            if (FilenameUtils.isExtension(sourceFile.getName(), language.getExtensions())) {
                Optional<ProjectSpec> owner = args.owner(sourceFile);
                if (owner.isPresent()) {
                    filesByProject.computeIfAbsent(owner.get(), k -> new ArrayList<>()).add(sourceFile);
                } else {
                    orphans.add(sourceFile);
                }
            }
        }

        List<Collection<File>> groups = new ArrayList<>();
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
                Map<String, File> filesByAbsolutePath = new HashMap<>();
                for (File sourceFile : filesForProject) {
                    filesByAbsolutePath.put(sourceFile.getAbsolutePath(), sourceFile);
                }

                Report report = pmd.performAnalysisAndCollectReport();
                report.getViolations().forEach(violation -> {
                    File sourceFile = filesByAbsolutePath.get(violation.getFileId().getAbsolutePath());
                    if (Objects.nonNull(sourceFile)) {
                        int markStart = violation.getLocation().getStartLine();
                        int markEnd = violation.getLocation().getEndLine();
                        Collection<CodeBlockInfo> blocks = summary.getBlocks().get(sourceFile);
                        for (CodeBlockInfo block : blocks) {
                            if (block.getLocation().getStartLine() <= markStart && markEnd <= block.getLocation().getEndLine()) {
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
        Map<ProjectSpec, edu.umd.cs.findbugs.Project> projects = new HashMap<>();
        for (ProjectSpec project : summary.getProjects()) {
            if (project instanceof JvmProjectSpec jvm) {
                File outputDir = project.getOutputDirectory();
                if (outputDir.exists()) {
                    if (hasClassFiles(outputDir)) {
                        verifyClassesNotStale(project, outputDir);
                        projects.computeIfAbsent(project, target -> {
                            edu.umd.cs.findbugs.Project spotbugs = new edu.umd.cs.findbugs.Project();
                            spotbugs.setProjectName(target.getName());
                            spotbugs.addFile(target.getOutputDirectory().getAbsolutePath());

                            jvm.getCompileClasspathElements().stream().forEach(element -> spotbugs.addAuxClasspathEntry(element.getAbsolutePath()));
                            jvm.getTestClasspathElements().stream().forEach(element -> spotbugs.addAuxClasspathEntry(element.getAbsolutePath()));

                            for (File dir : jvm.getCompileSourceRoots()) {
                                spotbugs.addSourceDirs(Collections.singletonList(dir.getAbsolutePath()));
                            }
                            for (File dir : jvm.getTestCompileSourceRoots()) {
                                spotbugs.addSourceDirs(Collections.singletonList(dir.getAbsolutePath()));
                            }

                            return spotbugs;
                        });
                    } else {
                        log.info("skipping spotbugs for project '%s': no .class files in %s (likely a pom-only aggregator, a test-only module, or an un-compiled module)",
                                project.getName(), outputDir.getAbsolutePath());
                    }
                }
            }
        }

        Set<Plugin> plugins = new HashSet<>();
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

        Set<Path> sourceRoots = new HashSet<>();
        for (ProjectSpec project : projects.keySet()) {
            if (project instanceof JvmProjectSpec jvm) {
                jvm.getCompileSourceRoots().forEach(dir -> sourceRoots.add(dir.toPath().normalize()));
                jvm.getTestCompileSourceRoots().forEach(dir -> sourceRoots.add(dir.toPath().normalize()));
            }
        }

        Map<String, File> filesBySourcePath = new HashMap<>();
        for (File sourceFile : summary.getBlocks().keySet()) {
            Path normalized = sourceFile.toPath().normalize();
            for (Path rootPath : sourceRoots) {
                if (normalized.startsWith(rootPath)) {
                    filesBySourcePath.put(rootPath.relativize(normalized).toString(), sourceFile);
                    break;
                }
            }
        }

        Set<String> omitVisitors = new HashSet<>(Split.on(args.getSpotbugsOmitVisitors(), ','));

        StopWatch spotbugsWatch = StopWatch.createStarted();
        projects.values().forEach(spotbugs -> {
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
                            int markStart = sourceLine.getStartLine();
                            int markEnd = sourceLine.getEndLine();

                            File sourceFile = filesBySourcePath.get(sourceLine.getSourcePath());
                            if (Objects.nonNull(sourceFile)) {
                                Collection<CodeBlockInfo> blocks = summary.getBlocks().get(sourceFile);
                                for (CodeBlockInfo block : blocks) {
                                    if (block.getLocation().getStartLine() <= markStart && markEnd <= block.getLocation().getEndLine()) {
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
    static boolean expectsCoverage(ProjectSpec project) throws IOException {
        /**
         * a module "expects" coverage only when its test fork actually ran: compiled main classes AND at least one
         * surefire/failsafe report. a pom aggregator, a code-less module, or a module whose only src/test sources are
         * main()-style helpers (dev-server starters, migration generators) with no @Test methods runs no tests, forks
         * no JVM, and legitimately produces no jacoco.exec — so it must not be flagged. keying on executed reports
         * still catches the real regression: tests ran (reports present) but the agent never attached (no jacoco.exec).
         */
        if (project instanceof JvmProjectSpec) {
            File outputDir = project.getOutputDirectory();
            if (outputDir.isDirectory() && hasFilesWithExtension(outputDir, "class")) {
                return ranTests(outputDir);
            }
        }
        return false;
    }
    private static boolean ranTests(File outputDir) {
        /**
         * maven layout: target/classes with junit xml in target/{surefire,failsafe}-reports;
         * gradle layout: build/classes/<lang>/<sourceSet> with junit xml in build/test-results/<task>
         */
        File mavenBuildDir = outputDir.getParentFile();
        for (String reportDir : new String[] { "surefire-reports", "failsafe-reports" }) {
            if (containsJunitReports(new File(mavenBuildDir, reportDir))) {
                return true;
            }
        }

        File gradleBuildDir = Optional.ofNullable(mavenBuildDir.getParentFile()).map(File::getParentFile).orElse(null);
        if (Objects.nonNull(gradleBuildDir)) {
            File[] taskDirs = new File(gradleBuildDir, "test-results").listFiles(File::isDirectory);
            for (File taskDir : ArrayUtils.nullToEmpty(taskDirs, File[].class)) {
                if (containsJunitReports(taskDir)) {
                    return true;
                }
            }
        }
        return false;
    }
    private static boolean containsJunitReports(File dir) {
        if (dir.isDirectory()) {
            return ArrayUtils.isNotEmpty(dir.listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml")));
        }
        return false;
    }
    private static boolean touchesAnalyzedFile(IClassCoverage cls, CommitAnalysis analysis) {
        /**
         * jacoco reports the source file when debug info is present; otherwise derive it from the outer class name so
         * a mismatched class can still be matched against the commit's changed file locations
         */
        String sourceFile = Optional.ofNullable(cls.getSourceFileName())
                .orElse(StringUtils.substringBefore(StringUtils.substringAfterLast(cls.getName(), "/"), "$") + ".java");
        String relative = cls.getPackageName() + "/" + sourceFile;

        for (File location : analysis.locations()) {
            if (FilenameUtils.separatorsToUnix(location.getPath()).endsWith(relative)) {
                return true;
            }
        }
        return false;
    }
    private static boolean hasClassFiles(File outputDir) throws IOException {
        return hasFilesWithExtension(outputDir, "class");
    }
    private static boolean hasFilesWithExtension(File dir, String extension) throws IOException {
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            return walk.anyMatch(p -> extension.equals(FilenameUtils.getExtension(p.toString())));
        }
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
