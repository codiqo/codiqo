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
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.time.StopWatch;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;

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
import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Match;
import net.sourceforge.pmd.internal.util.IOUtil;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry.LanguageTerminationException;
import net.sourceforge.pmd.lang.LanguageRegistry;
import reactor.core.publisher.Mono;

public class DefaultLanguageProcessors implements LanguageProcessors {
    private final Log log;
    private final RunArgs args;
    private final List<LanguageSpec> processors;
    private final Set<String> extensions = Sets.newHashSet();

    public DefaultLanguageProcessors(LogFactory logFactory, RunArgs args, Fetch fetch) throws IOException {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        this.processors = Lists.newArrayList(new JavaLanguageSpec(logFactory, args, fetch));

        processors.forEach(processor -> extensions.addAll(processor.lang().getExtensions()));
    }
    @Override
    public void collectAndCapture(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        captureCopyPaste(summary, analysis);
        captureCoverage(summary, analysis);
        captureViolations(summary, analysis);
        captureIncomingCalls(summary, analysis);
        captureComplexity(summary, analysis);
        for (FileAnalysis fileAnalysis : analysis) {
            for (AffectedSymbolInfo affectedSymbolInfo : fileAnalysis.getPotentiallyAffectedSymbols()) {
                affectedSymbolInfo.block().ifPresent(block -> {
                    if (block instanceof JavaCodeBlockInfo java) {
                        java.getInvocations().forEach(outbound -> {

                        });
                    }
                });
            }
        }
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
    public Mono<?> load() {
        return Mono.zip(processors.stream().map(LanguageSpec::load).collect(ImmutableList.toImmutableList()), objects -> processors.size());
    }
    @Override
    public IndexingSummary index(CommitAnalysis analysis) throws IOException {
        IndexingSummaryBuilder toReturn = IndexingSummary.builder();
        Multimap<File, CodeBlockInfo> blocks = Multimaps.synchronizedMultimap(MultimapBuilder.hashKeys().linkedHashSetValues().build());
        List<Path> totalFiles = Lists.newArrayList();
        List<Path> ignoredFiles = Lists.newArrayList();
        List<Path> skippedFiles = Lists.newArrayList();
        AtomicInteger skippedTrivial = new AtomicInteger();
        AtomicInteger totalSymbols = new AtomicInteger();

        File projectRoot = args.getGit().getWorkTree();
        try (Repository repo = JGit.openRepository(projectRoot)) {
            toReturn.projectRoot(projectRoot);

            /**
             * gather all attached files in the repository
             */
            Set<Path> indexed = Sets.newLinkedHashSet();
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
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
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

                                    if (Boolean.FALSE.equals(prj.isTestResource(file))) {
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
            });

            Map<ProjectSpec, List<File>> filesByOwner = Maps.newLinkedHashMap();
            List<File> orphans = Lists.newArrayList();
            for (Path path : totalFiles) {
                File file = path.toFile();
                Optional<ProjectSpec> opt = args.owner(file);
                if (opt.isPresent()) {
                    filesByOwner.computeIfAbsent(opt.get(), k -> Lists.newArrayList()).add(file);
                } else {
                    orphans.add(file);
                }
            }

            filesByOwner.entrySet().parallelStream().forEach(group -> processors.forEach(processor -> {
                List<File> matching = group.getValue().stream()
                        .filter(file -> FilenameUtils.isExtension(file.getName(), processor.lang().getExtensions()))
                        .collect(ImmutableList.toImmutableList());
                if (CollectionUtils.isNotEmpty(matching)) {
                    for (;;) {
                        try {
                            processor.parse(group.getKey(), matching).forEach(block -> {
                                blocks.put(block.getFile(), block);

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
                        Set<Integer> lines = Sets.newHashSet();
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
    @Override
    public void captureCopyPaste(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        for (LanguageSpec processor : processors) {
            if (Boolean.FALSE.equals(args.isIgnoreCpd())) {
                SortedSet<DuplicationMatch> matches = Sets.newTreeSet();
                MutableBoolean toApply = new MutableBoolean();

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
                    /**
                     * add all relevant files to CPD by extension
                     */
                    summary.getTotalFiles().forEach(path -> {
                        if (FilenameUtils.isExtension(path.toFile().getName(), processor.lang().getExtensions())) {
                            if (cpd.files().addFile(path)) {
                                toApply.setTrue();
                            }
                        }
                    });

                    /**
                     * could be so that CPD is not supported for this language
                     */
                    if (toApply.isTrue()) {
                        toApply.setValue(processor.supportsCpd());
                    }

                    if (toApply.isTrue()) {
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
                                                .build()).collect(ImmutableList.toImmutableList()))
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
                            analysis.cpd().add(toAccept);
                            toAccept.copyPasteFrom().forEach((block, sources) -> log.info("CPD from existing code: %s copied from %s", block, sources));
                            toAccept.copyPasteNew().forEach(group -> log.info("CPD within same commit: %s", group));
                        });
                    }
                }
            }
        }
    }
    @Override
    public void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        if (Boolean.FALSE.equals(args.isIgnoreDiagnostics())) {
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
        if (Boolean.FALSE.equals(args.isIgnoreCoverage())) {
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
        if (Boolean.FALSE.equals(args.isIgnoreComplexity())) {
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
}
