package io.codiqo.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.event.Level;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.IndexingSummary.IndexingSummaryBuilder;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.LanguageSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.cpd.DuplicationMatch;
import io.codiqo.api.cpd.PMDCopyPasteDetectionSummary;
import io.codiqo.api.cpd.PmdDuplicationMark;
import io.codiqo.api.cpd.PmdDuplicationMatch;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.diff.GitDiffHunk;
import io.codiqo.core.diff.GitFileAnalysis;
import io.codiqo.core.java.JavaLanguageSpec;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.lang.spec.PmdAffectedSymbolInfo;
import io.codiqo.util.Fetch;
import io.codiqo.util.JGit;
import io.codiqo.util.MemoryReport;
import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Match;
import net.sourceforge.pmd.internal.util.IOUtil;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry.LanguageTerminationException;
import net.sourceforge.pmd.lang.LanguageRegistry;

public class DefaultLanguageProcessors implements LanguageProcessors {
    /**
     * the CPD retry batch, never the first attempt. Sized under the smallest set observed to poison PMD's shared
     * lexer (micronaut-core's 5033 files crashed; 2000 passed), with headroom: the threshold is a property of the
     * file sequence, not of a count.
     */
    private static final int CPD_RETRY_BATCH_SIZE = 1000;

    private final Log log;
    private final RunArgs args;
    private final List<LanguageSpec> processors;
    private final Set<String> extensions = new HashSet<>();

    public DefaultLanguageProcessors(LogFactory logFactory, RunArgs args, Fetch fetch) {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        this.processors = new ArrayList<>(List.of(new JavaLanguageSpec(logFactory, args, fetch)));

        processors.forEach(processor -> extensions.addAll(processor.lang().getExtensions()));
    }
    @Override
    public void collectAndCapture(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        measure("cpd", this::captureCopyPaste, summary, analysis);
        measure("coverage", this::captureCoverage, summary, analysis);
        measure("diagnostics", this::captureViolations, summary, analysis);
        measure("incoming calls", this::captureIncomingCalls, summary, analysis);
        measure("complexity", this::captureComplexity, summary, analysis);
    }
    @Override
    public Collection<String> extensions() {
        return extensions;
    }
    @Override
    public Iterator<LanguageSpec> iterator() {
        return processors.iterator();
    }
    @Override
    public void load() {
        processors.forEach(LanguageSpec::load);
    }
    @Override
    public IndexingSummary index(CommitAnalysis analysis) throws IOException {
        IndexingSummaryBuilder toReturn = IndexingSummary.builder();

        /**
         * LinkedHashSet-backed values preserve deterministic parse-order iteration of a file's blocks.
         * CodeBlockInfo.hashCode() is the PMD AST node identity hashCode (varies per JVM run), so a plain
         * HashSet would make downstream code-unit ordering — and thus the dumped YAML — non-reproducible.
         */
        MultiValuedMap<File, CodeBlockInfo> blocks = new HashSetValuedHashMap<>() {
            @Override
            protected HashSet<CodeBlockInfo> createCollection() {
                return new LinkedHashSet<>();
            }
        };
        List<Path> totalFiles = new ArrayList<>();
        List<Path> ignoredFiles = new ArrayList<>();
        List<Path> excludedFiles = new ArrayList<>();
        List<Path> excludedTrees = new ArrayList<>();
        List<Path> skippedFiles = new ArrayList<>();
        AtomicInteger skippedTrivial = new AtomicInteger();
        AtomicInteger totalSymbols = new AtomicInteger();

        File projectRoot = args.getGit().getWorkTree();
        try (Repository repo = JGit.openRepository(projectRoot)) {
            toReturn.projectRoot(projectRoot);

            /**
             * gather all attached files in the repository
             */
            Set<Path> indexed = new LinkedHashSet<>();
            DirCache dirCache = repo.readDirCache();
            int entryCount = dirCache.getEntryCount();
            for (int i = 0; i < entryCount; i++) {
                indexed.add(projectRoot.toPath().normalize().resolve(dirCache.getEntry(i).getPathString()));
            }

            if (args.isIncludeUntracked()) {
                try (Git git = Git.wrap(repo)) {
                    for (String untracked : git.status().call().getUntracked()) {
                        indexed.add(projectRoot.toPath().normalize().resolve(untracked));
                    }
                } catch (GitAPIException err) {
                    ExceptionUtils.wrapAndThrow(err);
                }
            }

            StopWatch stopWatch = StopWatch.createStarted();
            Files.walkFileTree(projectRoot.toPath(), new SimpleFileVisitor<>() {
                /**
                 * a module dropped by codiqo.excludeProjects is excluded from the indexed file set too, not merely
                 * from the project list. CPD and the symbol index read this walk rather than the project list, so
                 * leaving the tree in would keep feeding them the very sources the exclusion was asked for.
                 */
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isExcluded(dir)) {
                        excludedTrees.add(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (isExcluded(path)) {
                        ignoredFiles.add(path);
                        excludedFiles.add(path);
                        return FileVisitResult.CONTINUE;
                    }
                    if (indexed.contains(path)) {
                        File file = path.toFile();
                        if (FilenameUtils.isExtension(file.getName(), extensions)) {
                            args.owner(file).ifPresent(prj -> {
                                FileTime fileTime = attrs.lastModifiedTime();
                                if (Objects.nonNull(fileTime)) {
                                    Date date = Date.from(fileTime.toInstant());
                                    Optional<Date> opt = prj.latestModified();
                                    if (opt.isEmpty() || opt.get().before(date)) {
                                        prj.setLatestModified(date);
                                    }

                                    if (BooleanUtils.negate(prj.isTestResource(file))) {
                                        Optional<Date> srcOpt = prj.latestSourceModified();
                                        if (srcOpt.isEmpty() || srcOpt.get().before(date)) {
                                            prj.setLatestSourceModified(date);
                                        }
                                    }
                                }
                            });
                            totalFiles.add(path);
                        } else {
                            skippedFiles.add(path);
                        }
                    } else {
                        ignoredFiles.add(path);
                    }
                    return FileVisitResult.CONTINUE;
                }
                private boolean isExcluded(Path path) {
                    File file = path.toFile();
                    return BooleanUtils.or(new boolean[] {
                            args.isExcludedProjectPath(file),
                            args.isExcludedPath(projectRoot, file)
                    });
                }
            });

            Map<ProjectSpec, List<File>> filesByOwner = new LinkedHashMap<>();
            List<File> orphans = new ArrayList<>();
            for (Path path : totalFiles) {
                File file = path.toFile();
                Optional<ProjectSpec> opt = args.owner(file);
                if (opt.isPresent()) {
                    filesByOwner.computeIfAbsent(opt.get(), k -> new ArrayList<>()).add(file);
                } else {
                    orphans.add(file);
                }
            }

            filesByOwner.entrySet().parallelStream().forEach(group -> processors.forEach(processor -> {
                List<File> matching = group.getValue().stream()
                        .filter(file -> FilenameUtils.isExtension(file.getName(), processor.lang().getExtensions()))
                        .toList();
                if (CollectionUtils.isNotEmpty(matching)) {
                    for (;;) {
                        try {
                            processor.parse(group.getKey(), matching).forEach(block -> {
                                synchronized (blocks) {
                                    blocks.put(block.getFile(), block);
                                }

                                if (block.isTrivial()) {
                                    skippedTrivial.incrementAndGet();
                                } else {
                                    totalSymbols.incrementAndGet();
                                }
                            });
                            return;
                        } catch (IOException err) {
                            ExceptionUtils.wrapAndThrow(err);
                        }
                    }
                }
            }));
            if (CollectionUtils.isNotEmpty(orphans)) {
                log.error("could not determine owner for %d orphan files: %s", orphans.size(), orphans);
            }

            stopWatch.stop();

            /**
             * a pruned subtree never reaches visitFile, so the exclusion options have no signal in the counts above —
             * "ignored" also carries every path that is simply untracked, which dwarfs them. report what the patterns
             * actually dropped, and name the trees: that is the one figure worth checking a new pattern against.
             */
            if (BooleanUtils.or(new boolean[] {CollectionUtils.isNotEmpty(excludedFiles), CollectionUtils.isNotEmpty(excludedTrees) })) {
                log.info("exclusion patterns dropped %d files and %d whole trees from the index walk: %s", excludedFiles.size(), excludedTrees.size(), excludedTrees);
            }

            log.info("indexed %d symbols from %d files (skipped: %d, ignored: %d, trivial: %d) in %s",
                    totalSymbols.get(),
                    totalFiles.size(),
                    skippedFiles.size(),
                    ignoredFiles.size(),
                    skippedTrivial.get(),
                    stopWatch.toString());

            return toReturn
                    .projects(args.getProjects())
                    .blocks(blocks)
                    .totalFiles(totalFiles)
                    .skippedFiles(skippedFiles)
                    .ignoredFiles(ignoredFiles)
                    .skippedTrivial(skippedTrivial.get())
                    .totalNonTrivial(totalSymbols.get())
                    .took(stopWatch)
                    .build();
        }
    }
    @Override
    public void identifyAffectedSymbols(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        AtomicInteger identified = new AtomicInteger();

        for (LanguageSpec processor : processors) {
            for (FileAnalysis it : analysis) {
                if (it.isExtension(processor.lang())) {
                    if (it instanceof GitFileAnalysis gitAnalysis) {
                        gitAnalysis.setLanguage(processor.lang());

                        /**
                         * identify affected blocks by checking if any of the changed lines from the GIT difference fall within the symbol's location
                         */
                        Set<Integer> lines = new HashSet<>();
                        if (Objects.nonNull(gitAnalysis.getStructuredDiff())) {
                            for (GitDiffHunk hunk : gitAnalysis.getStructuredDiff().getHunks()) {
                                for (int line = hunk.getNewStartLine(); line < hunk.getNewEndLine(); line++) {
                                    lines.add(line + SourceLocation.GIT_OFFSET);
                                }
                            }
                        }

                        if (CollectionUtils.isNotEmpty(lines)) {
                            Collection<CodeBlockInfo> fileBlocks = summary.getBlocks().get(gitAnalysis.getFile());
                            for (CodeBlockInfo next : fileBlocks) {
                                if (next instanceof JavaCodeBlockInfo block) {
                                    int startLine = block.getLocation().getStartLine();
                                    int endLine = block.getLocation().getEndLine();
                                    boolean isAffected = lines.stream().anyMatch(line -> line >= startLine && line <= endLine);
                                    if (isAffected) {
                                        PmdAffectedSymbolInfo symbol = new PmdAffectedSymbolInfo(block, processor.lang());
                                        gitAnalysis.getPotentiallyAffectedSymbols().add(symbol);
                                        block.accept(symbol);
                                        identified.incrementAndGet();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        log.info("identified %d potentially affected symbols", identified.get());
    }
    /**
     * Runs one capture stage and reports what it cost. Peak heap rather than a before/after difference, because a
     * stage that allocates its way to the heap ceiling and then releases everything shows up as free in a
     * difference — CPD on a reactor holding two copies of the same tree is exactly that shape.
     */
    private void measure(String stage, CaptureStage capture, IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        long entryHeap = MemoryReport.heapUsed();
        MemoryReport.resetHeapPeak();
        StopWatch stopWatch = StopWatch.createStarted();

        try {
            capture.run(summary, analysis);
        } finally {
            /**
             * reported in a finally block because the stage that runs out of heap is the one worth measuring, and
             * a report emitted only on success says nothing about it. retained size is skipped on the failure path:
             * walking an object graph needs the head room the stage has just exhausted.
             */
            stopWatch.stop();
            long peak = MemoryReport.peakHeapUsed();
            log.info("memory (%s) peak-heap=%s/%s (+%s over stage entry) in %s",
                    stage,
                    MemoryReport.human(peak),
                    MemoryReport.human(Runtime.getRuntime().maxMemory()),
                    MemoryReport.human(Math.max(0, peak - entryHeap)),
                    stopWatch);
        }

        MemoryReport.retained(summary, analysis).ifPresent(size -> log.info("memory (%s) retained(index+analysis)=%s", stage, size));
    }
    /**
     * the index a build-failed commit is scored from: a pure source (PMD) pass with no {@code load()}, so the JDT
     * language server is never started for a commit whose build produced no classes. Shared by both plugins so a
     * copy cannot quietly reintroduce the {@code load()} this path exists to avoid.
     */
    public static IndexingSummary sourceOnlyIndex(RunArgs args, CommitAnalysis analysis, LogFactory logFactory) throws IOException {
        try (Fetch fetch = new Fetch(args); LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
            IndexingSummary index = registry.index(analysis);
            registry.identifyAffectedSymbols(index, analysis);
            return index;
        }
    }
    @Override
    public void captureCopyPaste(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        if (BooleanUtils.negate(args.isIgnoreCpd())) {
            for (LanguageSpec processor : processors) {
                if (processor.supportsCpd()) {
                    List<Path> files = summary.getTotalFiles().stream()
                            .filter(path -> FilenameUtils.isExtension(path.toFile().getName(), processor.lang().getExtensions()))
                            .toList();
                    if (CollectionUtils.isNotEmpty(files)) {
                        detectCopyPaste(processor, files, summary, analysis);
                    }
                }
            }
        }
    }
    /**
     * PMD reuses one {@code CpdLexer} per analysis and {@code JavaCpdLexer}'s {@code ConstructorDetector} accumulates
     * state across file boundaries, so a large enough set dies with an NPE that names no culprit file and that
     * {@code setFailOnError(false)} cannot contain. Observed on micronaut-core (5033 files) and spring-framework.
     *
     * <p>Batching is only the retry: CPD finds clones within one analysis, so batching unconditionally would silently
     * drop every cross-batch duplicate on every project.
     */
    void detectCopyPaste(LanguageSpec processor, List<Path> files, IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        try {
            tokenizeAndCollect(processor, files, summary, analysis);
            return;
        } catch (RuntimeException err) {
            log.logEx(Level.WARN, "CPD failed over all %d %s file(s); retrying in batches of %d, which loses clones spanning two batches",
                    new Object[] { files.size(), processor.lang().getName(), CPD_RETRY_BATCH_SIZE }, err);
        }

        for (List<Path> batch : ListUtils.partition(files, CPD_RETRY_BATCH_SIZE)) {
            try {
                tokenizeAndCollect(processor, batch, summary, analysis);
            } catch (RuntimeException err) {
                log.logEx(Level.WARN, "CPD batch of %d %s file(s) failed; its duplication data is omitted",
                        new Object[] { batch.size(), processor.lang().getName() }, err);
            }
        }
    }
    /**
     * package-private so CpdBatchRetryTest can substitute the PMD call: the real crash needs thousands of files and
     * cannot be committed as a fixture.
     */
    void tokenizeAndCollect(LanguageSpec processor, List<Path> files, IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        SortedSet<DuplicationMatch> matches = new TreeSet<>();
        boolean anyFileAdded = false;

        CPDConfiguration cfg = new CPDConfiguration(LanguageRegistry.singleton(processor.lang()));
        cfg.setReporter(log);
        cfg.setDefaultLanguageVersion(processor.lang().getDefaultVersion());
        cfg.setFailOnViolation(false);
        cfg.setFailOnError(true);
        cfg.setIgnoreLiterals(true);
        cfg.setIgnoreIdentifiers(true);
        cfg.setMinimumTileSize(args.getCpdMinimumTileSize());
        cfg.setSourceEncoding(StandardCharsets.UTF_8);

        try (CpdAnalysis cpd = CpdAnalysis.create(cfg)) {
            for (Path path : files) {
                anyFileAdded |= cpd.files().addFile(path);
            }

            if (anyFileAdded) {
                cpd.performAnalysis(report -> {
                    for (Match match : report.getMatches()) {
                        matches.add(PmdDuplicationMatch.builder()
                                .match(match)
                                .tokenCount(match.getTokenCount())
                                .lineCount(match.getLineCount())
                                .marks(match.getMarkSet().stream().map(mark -> PmdDuplicationMark.builder()
                                        .mark(mark)
                                        .file(Paths.get(mark.getLocation().getFileId().getAbsolutePath()).toFile())
                                        .sourceCodeSlice(report.getSourceCodeSlice(mark).toString())
                                        .location(SourceLocation.builder()
                                                .startLine(mark.getLocation().getStartLine())
                                                .endLine(mark.getLocation().getEndLine())
                                                .startColumn(mark.getLocation().getStartColumn())
                                                .endColumn(mark.getLocation().getEndColumn())
                                                .build())
                                        .build()).collect(Collectors.toUnmodifiableList()))
                                .build());
                    }

                    PMDCopyPasteDetectionSummary toAccept = new PMDCopyPasteDetectionSummary(
                            report.getNumberOfTokensPerFile()
                                    .entrySet()
                                    .stream()
                                    .collect(Collectors.toMap(it -> Paths.get(it.getKey().getAbsolutePath()).toFile(), it -> it.getValue())),
                            matches,
                            summary,
                            analysis);
                    /**
                     * registered last, after everything that could still throw: a failure here falls into the batched
                     * retry, and a summary already published would be counted again by its own batches
                     */
                    toAccept.copyPasteFrom().forEach((block, sources) -> log.info("CPD from existing code: %s copied from %s", block, sources));
                    toAccept.copyPasteNew().forEach(group -> log.info("CPD within same commit: %s", group));
                    analysis.cpd().add(toAccept);
                });
            }
        }
    }
    @Override
    public void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        if (BooleanUtils.negate(args.isIgnoreDiagnostics())) {
            for (LanguageSpec processor : processors) {
                processor.captureViolations(summary, analysis);
                for (FileAnalysis fileAnalysis : analysis) {
                    for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                        symbol.block().ifPresent(block -> {

                        });
                    }
                }
            }
        }
    }
    @Override
    public void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        for (LanguageSpec processor : processors) {
            processor.captureIncomingCalls(summary, analysis);
        }
    }
    @Override
    public void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        if (BooleanUtils.negate(args.isIgnoreCoverage())) {
            for (LanguageSpec processor : processors) {
                processor.captureCoverage(summary, analysis);
                for (FileAnalysis fileAnalysis : analysis) {
                    for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                        symbol.block().ifPresent(block -> {
                            if (fileAnalysis.isTestFile()) {
                                log.info("ignoring coverage of test method %s from %s", block, fileAnalysis.getFile());
                            } else {
                                log.info("capturing coverage of %s : %s", block, block.coverage());
                            }
                        });
                    }
                }
            }
        }
    }
    @Override
    public void captureComplexity(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        if (BooleanUtils.negate(args.isIgnoreComplexity())) {
            for (FileAnalysis fileAnalysis : analysis) {
                for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                    symbol.block().ifPresent(block -> log.info("capturing complexity of %s : %s", block, block.metrics()));
                }
            }
        }
    }
    @Override
    public void close() throws IOException {
        Exception err = IOUtil.closeAll(processors);
        if (Objects.nonNull(err)) {
            throw new LanguageTerminationException(err);
        }
    }
    @FunctionalInterface
    private interface CaptureStage {
        void run(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    }
}
