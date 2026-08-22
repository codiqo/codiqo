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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
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
import org.jacoco.core.data.ExecutionData;
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
import edu.umd.cs.findbugs.PluginException;
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
import io.codiqo.api.coverage.CoverageExclusionReason;
import io.codiqo.api.coverage.ExcludedCoverageClass;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.jdtls.JdtLspProjectImporter;
import io.codiqo.lang.spec.JInvocationBlock;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.util.Fetch;
import io.codiqo.util.Split;
import lombok.Value;
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
import net.sourceforge.pmd.reporting.Report;

public class JavaLanguageSpec implements LanguageSpec {
    public static final EnumSet<SymbolKind> TYPES = EnumSet.of(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum);
    public static final EnumSet<SymbolKind> SYMBOLS = EnumSet.of(SymbolKind.Method, SymbolKind.Function, SymbolKind.Constructor);

    public static final String CLASS_EXTENSION = "class";

    private static final String XML_EXTENSION = "xml";
    private static final String JUNIT_REPORT_PREFIX = "TEST-";

    private static final String META_INF_DIR = "META-INF";
    private static final String FINDBUGS_DESCRIPTOR = "findbugs" + FilenameUtils.EXTENSION_SEPARATOR_STR + XML_EXTENSION;
    private static final String JAR_URL_PREFIX = "jar:";

    private static final IOFileFilter CLASS_FILE_FILTER = FileFilterUtils.suffixFileFilter(FilenameUtils.EXTENSION_SEPARATOR_STR + CLASS_EXTENSION);

    /** surefire, failsafe and Gradle's junitXml writer all name a JUnit XML report the same way */
    private static final IOFileFilter JUNIT_REPORT_FILTER = FileFilterUtils.and(
            FileFilterUtils.prefixFileFilter(JUNIT_REPORT_PREFIX),
            FileFilterUtils.suffixFileFilter(FilenameUtils.EXTENSION_SEPARATOR_STR + XML_EXTENSION));

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

    public JavaLanguageSpec(LogFactory logFactory, RunArgs args, Fetch fetch) {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        this.jdt = new JdtLspProjectImporter(logFactory, args, fetch);
        this.incomingCallsResolver = new JdtIncomingCallsResolver(log, args, jdt);
    }
    @Override
    public void load() {
        /**
         * construction stays cheap (no download, no fork) so source-only callers — degraded
         * build-failure scoring running index()/identifyAffectedSymbols() — never spawn the JDT
         * language server; only load() downloads and forks it
         */
        jdt.load();
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
            jvm.getCompileClasspathElements().forEach(element -> jars.add(element.getAbsolutePath()));
            jvm.getTestClasspathElements().forEach(element -> jars.add(element.getAbsolutePath()));
            bundle.setProperty(JvmLanguagePropertyBundle.AUX_CLASSPATH, String.join(File.pathSeparator, jars));
        }

        LanguageRegistry languageRegistry = LanguageRegistry.singleton(language);
        Map<Language, LanguagePropertyBundle> languageProperties = Map.of(language, bundle);
        try (LanguageProcessorRegistry processingRegistry = LanguageProcessorRegistry.create(languageRegistry, languageProperties, log)) {
            Parser pmd = processingRegistry.getProcessor(language).services().getParser();
            SemanticErrorReporter errorReporter = SemanticErrorReporter.reportToLogger(log);

            for (File destination : files) {
                if (FilenameUtils.isExtension(destination.getName(), lang().getExtensions())) {
                    builder.addAll(parseFile(owner, destination, pmd, errorReporter, processingRegistry));
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
    public void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        incomingCallsResolver.resolve(summary, analysis);
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
        jdt.close();
    }
    private List<CodeBlockInfo> parseFile(
            ProjectSpec owner,
            File destination,
            Parser pmd,
            SemanticErrorReporter errorReporter,
            LanguageProcessorRegistry processingRegistry) throws IOException {
        List<CodeBlockInfo> toReturn = new ArrayList<>();

        try (TextFile file = TextFile.forPath(destination.toPath().normalize(), StandardCharsets.UTF_8, language.getDefaultVersion())) {
            try (TextDocument doc = TextDocument.create(file)) {
                /**
                 * PMD's type inference can crash on valid code it fails to disambiguate
                 * (e.g. diamond anonymous classes like "new TypeToken<>() {}", pmd/pmd#4436) —
                 * degrade to zero code units for the offending file instead of failing the run
                 */
                try {
                    ASTCompilationUnit tree = (ASTCompilationUnit) pmd.parse(new ParserTask(doc, errorReporter, processingRegistry));

                    /**
                     * crossFindBoundaries is required rather than cosmetic: every type declaration, anonymous class
                     * and lambda is a find boundary in PMD's Java AST, so the default traversal stops before it
                     * reaches a single method body
                     */
                    tree.descendants(ASTExecutableDeclaration.class)
                            .crossFindBoundaries()
                            .forEach(executable -> collectBlock(owner, destination, tree, executable, toReturn));
                } catch (RuntimeException err) {
                    log.warn("PMD failed to analyze %s, skipping code unit indexing for this file: %s", destination, ExceptionUtils.getRootCauseMessage(err));
                    return List.of();
                }
            }
        }

        return toReturn;
    }
    private void collectBlock(
            ProjectSpec owner,
            File destination,
            ASTCompilationUnit tree,
            ASTExecutableDeclaration executable,
            List<CodeBlockInfo> target) {
        ASTBlock block = executable.getBody();
        if (Objects.nonNull(block) && BooleanUtils.negate(block.isEmpty())) {
            String body = executable.getText().toString();
            ASTTypeDeclaration enclosing = executable.getEnclosingType();
            ASTTypeDeclaration type = executable
                    .ancestors(ASTTypeDeclaration.class)
                    .filter(t -> BooleanUtils.negate(t instanceof ASTAnonymousClassDeclaration))
                    .first();

            FileLocation reportLocation = tree.getTextDocument().toLocation(executable.getTextRegion());
            SourceLocation location = SourceLocation.builder()
                    .startLine(reportLocation.getStartLine())
                    .endLine(reportLocation.getEndLine())
                    .startColumn(reportLocation.getStartColumn())
                    .endColumn(reportLocation.getEndColumn())
                    .build();

            NodeStream<JavaNode> calls = executable.descendants(JavaNode.class).filter(node -> node instanceof MethodUsage).cached();
            Collection<JInvocationBlock> invocations = outboundASTconverter.apply(calls);

            if (executable instanceof ASTMethodDeclaration) {
                target.add(JavaPmdMethodInfo.builder()
                        .file(destination)
                        .location(location)
                        .type(type)
                        .enclosingType(enclosing)
                        .node(executable)
                        .invocations(invocations)
                        .body(body)
                        .build());
            } else if (executable instanceof ASTConstructorDeclaration) {
                target.add(JavaPmdConstructorInfo.builder()
                        .file(destination)
                        .location(location)
                        .type(type)
                        .enclosingType(enclosing)
                        .node(executable)
                        .invocations(invocations)
                        .body(body)
                        .build());
            }

            if (owner instanceof JvmProjectSpec jvm) {
                invocations.forEach(signature -> signature.accept(jvm));
            }
        }
    }
    private void captureJacocoCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        StopWatch stopWatch = StopWatch.createStarted();

        ExecFileLoader loader = new ExecFileLoader();
        Map<File, LoadedExec> loadedExecs = new LinkedHashMap<>();
        List<ProjectSpec> coveredProjects = loadOwnedCoverage(summary, loader, loadedExecs);

        if (MapUtils.isNotEmpty(loadedExecs)) {
            admitCrossModuleCoverage(summary, loadedExecs.values(), coveredProjects);
        }

        if (CollectionUtils.isEmpty(coveredProjects)) {
            return;
        }

        Map<File, ISourceFileCoverage> coverages = new LinkedHashMap<>();
        List<IClassCoverage> collisions = new ArrayList<>();
        CoverageTotals totals = analyzeModules(coveredProjects, loader.getExecutionDataStore(), coverages, collisions);

        if (CollectionUtils.isNotEmpty(collisions)) {
            reportCollisions(collisions, coverages, summary, analysis);
        }

        log.info("coverage: %d/%d lines covered across %d modules (%d packages, %d classes analyzed)",
                totals.getCoveredLines(),
                totals.getLines(),
                coveredProjects.size(),
                totals.getPackages(),
                totals.getClasses());

        attributeBlockCoverage(summary, coverages);
        attributeFileCoverage(analysis, coverages);

        stopWatch.stop();
        log.info("jacoco coverage analysis completed in %s", stopWatch);
    }
    /**
     * Loads the exec file each module produced for itself, and refuses to go on when a module whose tests demonstrably
     * ran produced none at all.
     */
    private List<ProjectSpec> loadOwnedCoverage(IndexingSummary summary, ExecFileLoader loader, Map<File, LoadedExec> loadedExecs) throws IOException {
        List<ProjectSpec> toReturn = new ArrayList<>();
        List<String> uninstrumentedModules = new ArrayList<>();

        for (ProjectSpec project : summary.getProjects()) {
            Optional<File> coverage = project.coverage();
            if (coverage.isPresent()) {
                File file = coverage.get();
                verifyCoverageNotStale(project, file);

                /**
                 * aggregated destFile configurations point every module at the same file — load it once.
                 * the per-module staleness check above still runs for each module.
                 */
                if (BooleanUtils.negate(loadedExecs.containsKey(file))) {
                    loader.load(file);
                    loadedExecs.put(file, readExecContents(file));
                }
                if (project.getOutputDirectory().exists()) {
                    toReturn.add(project);
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

        return toReturn;
    }
    private void verifyCoverageNotStale(ProjectSpec project, File coverage) throws IOException {
        /**
         * coverage recorded before the module's sources changed describes other code, and no amount of analysis can
         * tell which — abort rather than report it
         */
        FileTime fileTime = Files.readAttributes(coverage.toPath(), BasicFileAttributes.class).lastModifiedTime();
        Optional<Date> lm = project.latestModified();
        if (lm.isPresent()) {
            Date latestModified = lm.get();
            log.log(Level.TRACE,
                    "checking coverage file %s modified time %s against project's latest modified time %s",
                    coverage.getAbsolutePath(),
                    fileTime.toInstant(),
                    latestModified.toInstant());
            if (fileTime.toInstant().isBefore(latestModified.toInstant())) {
                throw new IOException(String.format(
                        "coverage file %s modified time %s is before project's latest modified time %s indicating the coverage data may be stale, please rebuild the project and rerun the tests",
                        coverage.getAbsolutePath(),
                        fileTime.toInstant(),
                        latestModified.toInstant()));
            }
        }
    }
    /**
     * A module whose own tests never ran still has its classes exercised by another module's tests — guava keeps its
     * production code in `guava` and every test in `guava-tests`, so `guava/target/jacoco.exec` is never written and the
     * classes that the commit actually changed would carry no coverage at all. JaCoCo records probes per class, not per
     * module, and every exec file loaded went into one store, so any module's classes can be resolved against it. Admit
     * them, but only once something was loaded: with no exec data at all this would analyze every class and report a
     * confident 0%.
     */
    private void admitCrossModuleCoverage(
            IndexingSummary summary,
            Collection<LoadedExec> loadedExecs,
            List<ProjectSpec> coveredProjects) throws IOException {
        for (ProjectSpec project : summary.getProjects()) {
            if (coveredProjects.contains(project)) {
                continue;
            }
            if (BooleanUtils.negate(project.getOutputDirectory().exists())) {
                continue;
            }
            /**
             * a code generator or a fixtures module no test ever touches would otherwise fold into the totals at
             * a confident 0% and dilute the figure for the code actually under test
             */
            Optional<Date> exercisedAt = newestExecContaining(loadedExecs, compiledClassNames(project.getOutputDirectory()));
            if (exercisedAt.isEmpty()) {
                log.info("skipping coverage for %s: no test anywhere loaded any of its classes", project.getName());
                continue;
            }
            /**
             * the staleness guard the owning-module branch applies to its own exec file, applied here against the
             * newest exec that actually carries this module's classes. the newest exec overall is the wrong
             * comparison: a fresh run of an unrelated module would vouch for probes that were recorded before this
             * module's sources changed, and those probes describe other code.
             */
            Optional<Date> latestModified = project.latestModified();
            if (latestModified.isPresent() && exercisedAt.get().toInstant().isBefore(latestModified.get().toInstant())) {
                log.warn("skipping coverage for %s: the newest coverage data covering its classes (%s) predates the module's latest source change (%s)",
                        project.getName(),
                        exercisedAt.get().toInstant(),
                        latestModified.get().toInstant());
                continue;
            }

            log.info("including %s in coverage analysis against another module's execution data", project.getName());
            coveredProjects.add(project);
        }
    }
    /**
     * Each module is analyzed with its own {@link CoverageBuilder}. A single shared builder throws "Can't add different
     * class with same name" when two modules legitimately declare the same fully-qualified class with different bodies
     * (e.g. a mapper class of the same name exists in both a gateway-client module and a worker module). Per-module
     * builders also let each module's source files resolve against that module's own source roots only, avoiding
     * cross-module mis-attribution of duplicate class names.
     */
    private CoverageTotals analyzeModules(
            Collection<ProjectSpec> coveredProjects,
            ExecutionDataStore data,
            Map<File, ISourceFileCoverage> coverages,
            List<IClassCoverage> collisions) throws IOException {
        File workTree = args.getGit().getWorkTree();

        int analyzedClasses = 0;
        int totalLines = 0;
        int coveredLines = 0;

        /**
         * line totals dedupe by class id and packages by name so identical copies of a class across modules are not
         * double-counted
         */
        Set<Long> countedClassIds = new HashSet<>();
        Set<String> countedPackages = new HashSet<>();

        for (ProjectSpec project : coveredProjects) {
            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(data, coverageBuilder);

            File outputDir = project.getOutputDirectory();
            int count = analyzeClassRoots(analyzer, outputDir);
            analyzedClasses += count;
            log.info("analyzed JaCoCo coverage for %d classes from %s", count, outputDir.getAbsolutePath());

            collisions.addAll(coverageBuilder.getNoMatchClasses());

            IBundleCoverage bundle = coverageBuilder.getBundle(language.getName());

            Set<CoverageSourceFile> excludedSources = Collections.emptySet();
            if (project instanceof JvmProjectSpec jvm) {
                excludedSources = collectSourceCoverage(args, workTree, jvm, bundle, coverages);
            }

            for (IPackageCoverage pkg : bundle.getPackages()) {
                countedPackages.add(pkg.getName());
                for (IClassCoverage cls : pkg.getClasses()) {
                    if (CoverageSourceFile.of(cls).filter(excludedSources::contains).isPresent()) {
                        continue;
                    }
                    if (countedClassIds.add(cls.getId())) {
                        ICounter lines = cls.getLineCounter();
                        totalLines += lines.getTotalCount();
                        coveredLines += lines.getCoveredCount();
                    }
                }
            }
        }

        return new CoverageTotals(analyzedClasses, countedPackages.size(), totalLines, coveredLines);
    }
    /**
     * A collision means the class name is present in the aggregated execution data under a different class id than the
     * compiled target/classes. The cause is a duplicate fully-qualified name on the classpath — the same FQN shipped by
     * more than one artifact (two reactor modules, or a reactor module shadowed by a dependency jar carrying an older
     * copy of the same class) — or another -javaagent transforming the class at load time. Codiqo cannot
     * tell which copy the recorded probes belong to, so the name's coverage is untrustworthy from EVERY copy: every
     * source file carrying a collided name is dropped from coverage entirely (safer than trusting one copy). This never
     * fails the analysis — a classpath collision cannot be fixed by rebuilding, and time-machined historical commits
     * predate any maven-enforcer duplicate-class ban.
     */
    private void reportCollisions(
            List<IClassCoverage> collisions,
            Map<File, ISourceFileCoverage> coverages,
            IndexingSummary summary,
            CommitAnalysis analysis) {
        collisions.forEach(cls -> analysis.excludedCoverageClasses().add(
                new ExcludedCoverageClass(JavaBinaryFormat.getBinaryName(cls.getName()), CoverageExclusionReason.DUPLICATE_FULLY_QUALIFIED_NAME)));

        Set<CoverageSourceFile> collided = collisions.stream()
                .map(CoverageSourceFile::of)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        coverages.values().removeIf(source -> collided.contains(CoverageSourceFile.of(source)));

        Map<String, File> analyzedBySourcePath = indexBySourceRootRelativePath(analysis.locations(), sourceRoots(summary.getProjects()));
        List<File> changed = new ArrayList<>();
        for (CoverageSourceFile source : collided) {
            Optional.ofNullable(analyzedBySourcePath.get(source.unixPath())).ifPresent(changed::add);
        }

        if (CollectionUtils.isNotEmpty(changed)) {
            log.warn("%d of the commit's changed files have a duplicate fully-qualified name on the classpath — coverage is unavailable for them and excluded (no copy is trusted)", changed.size());
            changed.forEach(file -> log.warn("  - %s", file));
        }

        log.warn("%d classes excluded from coverage — their fully-qualified name resolves to more than one class on the classpath (duplicate FQN) or was transformed at load time, so JaCoCo cannot attribute execution data to a single copy", collisions.size());
        collisions.forEach(cls -> log.warn("  - %s", cls.getName()));
    }
    /**
     * Captures coverage for ALL code blocks in the project, not just the commit-affected ones, so quality gates and
     * reporting can speak for the whole project.
     */
    private void attributeBlockCoverage(IndexingSummary summary, Map<File, ISourceFileCoverage> coverages) {
        int blocksWithCoverage = 0;
        int blocksProcessed = 0;

        for (Entry<File, Collection<CodeBlockInfo>> entry : summary.getBlocks().asMap().entrySet()) {
            if (FilenameUtils.isExtension(entry.getKey().getName(), lang().getExtensions())) {
                ISourceFileCoverage source = coverages.get(entry.getKey());
                if (Objects.nonNull(source)) {
                    for (CodeBlockInfo block : entry.getValue()) {
                        if (block instanceof JavaCodeBlockInfo javaBlock) {
                            blocksProcessed++;
                            if (attributeLines(javaBlock, source)) {
                                blocksWithCoverage++;
                            }
                        }
                    }
                }
            }
        }

        log.info("full project coverage: %d/%d code blocks have coverage data", blocksWithCoverage, blocksProcessed);
    }
    private void attributeFileCoverage(CommitAnalysis analysis, Map<File, ISourceFileCoverage> coverages) {
        int matched = 0;
        int unmatched = 0;
        int linesWithCoverage = 0;

        for (FileAnalysis fileAnalysis : analysis) {
            if (fileAnalysis.isExtension(lang())) {
                ISourceFileCoverage source = coverages.get(fileAnalysis.getFile());
                if (Objects.nonNull(source)) {
                    matched++;

                    int firstLine = source.getFirstLine();
                    int lastLine = source.getLastLine();
                    if (firstLine > 0) {
                        for (int lineNumber = firstLine; lineNumber <= lastLine; lineNumber++) {
                            ILine line = source.getLine(lineNumber);
                            fileAnalysis.lineCoverage(lineNumber, line);
                            if (isCovered(line)) {
                                linesWithCoverage++;
                            }
                        }
                    }
                } else {
                    unmatched++;
                }
            }
        }

        log.info("coverage analysis: %d files affected matched, %d unmatched, %d lines with coverage", matched, unmatched, linesWithCoverage);
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
        cfg.setMinimumPriority(args.pmdMinRulePriority());
        cfg.setThreads(BigDecimal.ONE.intValue());

        try (PmdAnalysis pmd = PmdAnalysis.create(cfg)) {
            pmd.addRuleSets(pmd.newRuleSetLoader().warnDeprecated(false).loadFromResources(args.getPmdRules()));

            Map<String, File> filesByAbsolutePath = new LinkedHashMap<>();
            for (File sourceFile : filesForProject) {
                if (pmd.files().addFile(sourceFile.toPath().normalize())) {
                    filesByAbsolutePath.put(sourceFile.getAbsolutePath(), sourceFile);
                }
            }

            if (MapUtils.isNotEmpty(filesByAbsolutePath)) {
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
        Map<ProjectSpec, edu.umd.cs.findbugs.Project> projects = new LinkedHashMap<>();
        for (ProjectSpec project : summary.getProjects()) {
            if (project instanceof JvmProjectSpec jvm) {
                File outputDir = project.getOutputDirectory();
                if (outputDir.exists()) {
                    if (hasFilesWithExtension(outputDir, CLASS_EXTENSION)) {
                        verifyClassesNotStale(project, outputDir);
                        projects.put(project, spotbugsProject(jvm));
                    } else {
                        log.info("skipping spotbugs for project '%s': no .class files in %s (likely a pom-only aggregator, a test-only module, or an un-compiled module)",
                                project.getName(),
                                outputDir.getAbsolutePath());
                    }
                }
            }
        }

        UnhiddenDetectorFactoryCollection detectorFactory = new UnhiddenDetectorFactoryCollection(loadCustomSpotbugsPlugins());
        DetectorFactoryCollection.resetInstance(detectorFactory);

        Map<String, File> filesBySourcePath = indexBySourceRootRelativePath(summary.getBlocks().keySet(), sourceRoots(projects.keySet()));
        Set<String> omitVisitors = new HashSet<>(Split.on(args.getSpotbugsOmitVisitors(), ','));

        StopWatch spotbugsWatch = StopWatch.createStarted();
        for (edu.umd.cs.findbugs.Project spotbugs : projects.values()) {
            try {
                runSpotbugs(spotbugs, detectorFactory, omitVisitors, filesBySourcePath, summary, analysis);
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
                throw new IOException("spotbugs analysis was interrupted", err);
            } finally {
                spotbugs.close();
            }
        }
        spotbugsWatch.stop();

        log.info("spotbugs analysis completed in %s", spotbugsWatch);
    }
    private void runSpotbugs(
            edu.umd.cs.findbugs.Project spotbugs,
            DetectorFactoryCollection detectorFactory,
            Set<String> omitVisitors,
            Map<String, File> filesBySourcePath,
            IndexingSummary summary,
            CommitAnalysis analysis) throws IOException, InterruptedException {
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

                    collectSpotbugs(bugReporter.getBugCollection(), filesBySourcePath, summary, analysis);
                }
            }
        }
    }
    private void collectSpotbugs(
            BugCollection bugCollection,
            Map<String, File> filesBySourcePath,
            IndexingSummary summary,
            CommitAnalysis analysis) {
        for (BugInstance bug : bugCollection) {
            SourceLineAnnotation sourceLine = bug.getPrimarySourceLineAnnotation();
            int markStart = sourceLine.getStartLine();
            int markEnd = sourceLine.getEndLine();

            File sourceFile = filesBySourcePath.get(sourceLine.getSourcePath());
            if (Objects.nonNull(sourceFile)) {
                Collection<CodeBlockInfo> blocks = summary.getBlocks().get(sourceFile);
                for (CodeBlockInfo block : blocks) {
                    if (block.getLocation().getStartLine() <= markStart && markEnd <= block.getLocation().getEndLine()) {
                        if (block instanceof JavaCodeBlockInfo javaBlock) {
                            javaBlock.spotbug(bug);
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
    /**
     * SpotBugs plugins reachable on codiqo's own classpath, minus the ones a previous run already registered globally.
     */
    private Set<Plugin> loadCustomSpotbugsPlugins() throws IOException {
        Set<String> registered = Plugin.getAllPlugins()
                .stream()
                .map(Plugin::getPluginLoader)
                .map(PluginLoader::getURL)
                .map(URL::toString)
                .collect(Collectors.toSet());

        Set<Plugin> toReturn = new HashSet<>();
        Enumeration<URL> resources = getClass().getClassLoader().getResources(FINDBUGS_DESCRIPTOR);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            if (resource.toString().startsWith(JAR_URL_PREFIX)) {
                resource = ((JarURLConnection) resource.openConnection()).getJarFileURL();
            }
            if (registered.contains(resource.toString())) {
                continue;
            }

            try {
                Plugin custom = Plugin.addCustomPlugin(resource);
                log.info("loaded custom spotbugs plugin from %s with id '%s'", resource, custom.getPluginId());
                toReturn.add(custom);
            } catch (DuplicatePluginIdException err) {
                log.log(Level.DEBUG, "spotbugs plugin from %s already registered: %s", resource, err.getMessage());
            } catch (PluginException err) {
                throw new IOException("failed to load the custom spotbugs plugin at " + resource, err);
            }
        }

        return toReturn;
    }
    static boolean expectsCoverage(ProjectSpec project) throws IOException {
        /**
         * a module "expects" coverage only when its test run actually happened: compiled main classes AND at least one
         * JUnit XML report in a directory the build tool named for it. a pom aggregator, a code-less module, or a module
         * whose only src/test sources are main()-style helpers (dev-server starters, migration generators) with no
         * @Test methods runs no tests, forks no JVM, and legitimately produces no exec file — so it must not be
         * flagged. keying on executed reports still catches the real regression: tests ran (reports present) but the
         * agent never attached (no exec file).
         */
        if (project instanceof JvmProjectSpec jvm) {
            File outputDir = jvm.getOutputDirectory();
            if (outputDir.isDirectory() && hasFilesWithExtension(outputDir, CLASS_EXTENSION)) {
                return jvm.getTestReportDirectories().stream().anyMatch(JavaLanguageSpec::containsJunitReports);
            }
        }
        return false;
    }
    /**
     * Resolves each coverage record back to the source file it describes, and reports the ones an exclusion pattern
     * drops. This is the only place the two can be connected: {@code codiqo.excludePaths} names paths in the work
     * tree, while JaCoCo walks compiled output and never sees the index walk the pattern was written against — so
     * without filtering here an excluded tree still lands in the coverage totals.
     */
    static Set<CoverageSourceFile> collectSourceCoverage(
            RunArgs args,
            File workTree,
            JvmProjectSpec jvm,
            IBundleCoverage bundle,
            Map<File, ISourceFileCoverage> coverages) {
        Set<CoverageSourceFile> toReturn = new HashSet<>();
        for (File sourceRoot : jvm.getCompileSourceRoots()) {
            Path normalized = sourceRoot.toPath().normalize().toAbsolutePath();
            for (IPackageCoverage pkg : bundle.getPackages()) {
                for (ISourceFileCoverage source : pkg.getSourceFiles()) {
                    CoverageSourceFile id = CoverageSourceFile.of(source);
                    File resolved = normalized.resolve(id.relativePath()).normalize().toFile();
                    if (resolved.exists()) {
                        if (args.isExcludedPath(workTree, resolved)) {
                            toReturn.add(id);
                        } else {
                            coverages.put(resolved, source);
                        }
                    }
                }
            }
        }
        return toReturn;
    }
    /**
     * The VM names of every class compiled into this module's output, in the form JaCoCo records execution data under.
     * A compiled directory is not evidence that anything ran against it — matching these against an exec file's
     * contents is.
     */
    static Set<String> compiledClassNames(File outputDir) throws IOException {
        Path root = outputDir.toPath();
        try (Stream<Path> compiled = Files.walk(root)) {
            return compiled.filter(path -> CLASS_EXTENSION.equals(FilenameUtils.getExtension(path.toString())))
                    .map(path -> FilenameUtils.separatorsToUnix(FilenameUtils.removeExtension(root.relativize(path).toString())))
                    .collect(Collectors.toSet());
        }
    }
    /**
     * When the module's classes were last exercised: the modification time of the newest exec file that holds a record
     * for any of them. Empty when no loaded exec mentions the module at all, which is how a module no test ever touched
     * is told apart from one whose coverage is merely old.
     */
    static Optional<Date> newestExecContaining(Collection<LoadedExec> loadedExecs, Set<String> compiledClassNames) {
        return loadedExecs.stream()
                .filter(exec -> CollectionUtils.containsAny(exec.getClassNames(), compiledClassNames))
                .map(exec -> exec.getFile().lastModified())
                .max(Long::compare)
                .map(Date::new);
    }
    /**
     * Read once per exec file so membership stays attributable to the file it came from. The shared store the analysis
     * runs against is a merge of all of them and cannot answer which file a class name arrived in.
     */
    private static LoadedExec readExecContents(File file) throws IOException {
        ExecFileLoader toRead = new ExecFileLoader();
        toRead.load(file);
        return new LoadedExec(file, executionDataClassNames(toRead));
    }
    /**
     * Every given file keyed by its path below the source root it lives under — the form both SpotBugs
     * ({@code SourceLineAnnotation.getSourcePath}) and JaCoCo (package name plus source file name) report locations in.
     * Matching on that key is exact, where comparing path suffixes conflates two same-named files in different modules.
     */
    private static Map<String, File> indexBySourceRootRelativePath(Collection<File> files, Collection<Path> sourceRoots) {
        Map<String, File> toReturn = new LinkedHashMap<>();
        for (File file : files) {
            Path normalized = file.toPath().normalize();
            for (Path root : sourceRoots) {
                if (normalized.startsWith(root)) {
                    toReturn.put(FilenameUtils.separatorsToUnix(root.relativize(normalized).toString()), file);
                    break;
                }
            }
        }
        return toReturn;
    }
    private static Set<Path> sourceRoots(Collection<ProjectSpec> projects) {
        Set<Path> toReturn = new LinkedHashSet<>();
        for (ProjectSpec project : projects) {
            if (project instanceof JvmProjectSpec jvm) {
                jvm.getCompileSourceRoots().forEach(dir -> toReturn.add(dir.toPath().normalize()));
                jvm.getTestCompileSourceRoots().forEach(dir -> toReturn.add(dir.toPath().normalize()));
            }
        }
        return toReturn;
    }
    private static edu.umd.cs.findbugs.Project spotbugsProject(JvmProjectSpec jvm) {
        edu.umd.cs.findbugs.Project toReturn = new edu.umd.cs.findbugs.Project();
        toReturn.setProjectName(jvm.getName());
        toReturn.addFile(jvm.getOutputDirectory().getAbsolutePath());

        jvm.getCompileClasspathElements().forEach(element -> toReturn.addAuxClasspathEntry(element.getAbsolutePath()));
        jvm.getTestClasspathElements().forEach(element -> toReturn.addAuxClasspathEntry(element.getAbsolutePath()));

        toReturn.addSourceDirs(absolutePaths(jvm.getCompileSourceRoots()));
        toReturn.addSourceDirs(absolutePaths(jvm.getTestCompileSourceRoots()));
        return toReturn;
    }
    private static boolean attributeLines(JavaCodeBlockInfo javaBlock, ISourceFileCoverage source) {
        SourceLocation location = javaBlock.getLocation();

        boolean toReturn = false;
        for (int lineNumber = location.getStartLine(); lineNumber <= location.getEndLine(); lineNumber++) {
            ILine line = source.getLine(lineNumber);
            javaBlock.lineCoverage(lineNumber, line);
            if (isCovered(line)) {
                toReturn = true;
            }
        }
        return toReturn;
    }
    /**
     * {@link ICounter#PARTLY_COVERED} is {@code NOT_COVERED | FULLY_COVERED}, so the FULLY_COVERED bit is set for both
     * the fully and the partly covered statuses — one bit test is the whole predicate.
     */
    private static boolean isCovered(ILine line) {
        return (line.getStatus() & ICounter.FULLY_COVERED) != 0;
    }
    private static boolean containsJunitReports(File dir) {
        return dir.isDirectory() && CollectionUtils.isNotEmpty(FileUtils.listFiles(dir, JUNIT_REPORT_FILTER, null));
    }
    /** Every class name the instrumented JVM loaded, hit or not — the record exists as soon as the class is loaded. */
    private static Set<String> executionDataClassNames(ExecFileLoader loader) {
        return loader.getExecutionDataStore().getContents().stream()
                .map(ExecutionData::getName)
                .collect(Collectors.toSet());
    }
    private static Optional<Date> newestModified(Collection<File> files) {
        return files.stream()
                .map(File::lastModified)
                .max(Long::compare)
                .map(Date::new);
    }
    /**
     * Analyzes a module's compiled output, skipping {@code META-INF}. A multi-release build puts a second copy of a
     * class under {@code META-INF/versions/<n>/}, and JaCoCo's directory walk reads both as one fully-qualified name
     * with two different bodies — which aborts the whole coverage capture, not just that class. Only the base copy is
     * analyzed; where the tests actually loaded a versioned copy its probes no longer match, and the existing
     * duplicate-name handling drops that name from coverage rather than trusting the wrong body.
     */
    private static int analyzeClassRoots(Analyzer analyzer, File outputDir) throws IOException {
        File[] roots = outputDir.listFiles(child -> BooleanUtils.negate(META_INF_DIR.equals(child.getName())));
        if (ArrayUtils.isEmpty(roots)) {
            return analyzer.analyzeAll(outputDir);
        }

        int toReturn = 0;
        for (File root : roots) {
            toReturn += analyzer.analyzeAll(root);
        }
        return toReturn;
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
            Optional<Date> latestClass = newestModified(FileUtils.listFiles(outputDir, CLASS_FILE_FILTER, TrueFileFilter.INSTANCE));

            if (latestClass.isPresent() && latestClass.get().toInstant().isBefore(latestModified.toInstant())) {
                throw new IOException(String.format(
                        "compiled classes in %s are older than sources (latest .class: %s, latest source: %s) — recompile before re-running spotbugs",
                        outputDir.getAbsolutePath(),
                        latestClass.get().toInstant(),
                        latestModified.toInstant()));
            }
        }
    }
    private static List<String> absolutePaths(Collection<File> files) {
        return files.stream().map(File::getAbsolutePath).toList();
    }

    /** one loaded exec file and the class names it holds records for */
    @Value
    static class LoadedExec {
        File file;
        Set<String> classNames;
    }

    @Value
    private static class CoverageTotals {
        int classes;
        int packages;
        int lines;
        int coveredLines;
    }

    private static class UnhiddenDetectorFactoryCollection extends DetectorFactoryCollection {
        public UnhiddenDetectorFactoryCollection(Collection<Plugin> enabled) {
            super(enabled);
        }
    }
}
