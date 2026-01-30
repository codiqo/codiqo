package io.codiqo.core;

import java.io.File;
import java.io.FileInputStream;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.time.StopWatch;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.IndexingSummary.IndexingSummaryBuilder;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.LanguageSpec;
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
import io.codiqo.core.java.JavaLanguageSpec;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.util.Fetch;
import lombok.SneakyThrows;
import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
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

        forEach(processor -> extensions.addAll(processor.lang().getExtensions()));
    }
    @Override
    public void collectAndCapture(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        captureCopyPaste(summary, analysis);
        captureCoverage(summary, analysis);
        captureViolations(summary, analysis);
        captureComplexity(summary, analysis);
        for (FileAnalysis fileAnalysis : analysis) {
            for (AffectedSymbolInfo affectedSymbolInfo : fileAnalysis.getPotentiallyAffectedSymbols()) {
                affectedSymbolInfo.block().ifPresent(block -> {
                    if (block instanceof JavaCodeBlockInfo) {
                        JavaCodeBlockInfo java = (JavaCodeBlockInfo) block;
                        java.getMethodCalls().forEach(outbound -> {

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
        Multimap<File, CodeBlockInfo> blocks = MultimapBuilder.hashKeys().linkedHashSetValues().build();
        List<Path> totalFiles = Lists.newArrayList();
        List<Path> ignoredFiles = Lists.newArrayList();
        List<Path> skippedFiles = Lists.newArrayList();
        MutableInt skippedTrivial = new MutableInt();
        MutableInt totalSymbols = new MutableInt();

        File projectRoot = args.getGit().getWorkTree();
        try (Repository repo = new FileRepositoryBuilder().setGitDir(new File(projectRoot, ".git")).readEnvironment().findGitDir().build()) {
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

            StopWatch stopWatch = StopWatch.createStarted();
            Files.walkFileTree(projectRoot.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (indexed.contains(path)) {
                        File file = path.toFile();
                        /**
                         * quick opt in / out by extensions
                         */
                        if (FilenameUtils.isExtension(file.getName(), extensions)) {
                            try {
                                args.owner(file).ifPresent(prj -> {
                                    Optional<Date> opt = prj.latestModified();
                                    FileTime fileTime = attrs.lastModifiedTime();
                                    if (Objects.nonNull(fileTime)) {
                                        Date date = Date.from(fileTime.toInstant());
                                        if (opt.isEmpty()) {
                                            prj.setLatestModified(date);
                                        } else if (opt.get().before(date)) {
                                            prj.setLatestModified(date);
                                        }
                                    }
                                });
                                forEach(processor -> {
                                    /**
                                     * parse all interested code blocks from the all files (supposed to be quick i.e. AST tree walk)
                                     */
                                    try {
                                        if (FilenameUtils.isExtension(file.getName(), processor.lang().getExtensions())) {
                                            try (FileInputStream input = new FileInputStream(file)) {
                                                String source = IOUtils.toString(input, StandardCharsets.UTF_8);
                                                processor.parse(file, source).forEach(block -> {
                                                    blocks.put(file, block);
                                                    /**
                                                     * link code blocks to affected blocks for later analysis (i.e. violations, coverage, complexity, etc.)
                                                     */
                                                    for (FileAnalysis fileAnalysis : analysis) {
                                                        if (fileAnalysis.getFile().equals(file)) {
                                                            for (AffectedSymbolInfo symbolInfo : fileAnalysis.getPotentiallyAffectedSymbols()) {
                                                                if (symbolInfo.getLocation().equals(block.getLocation())) {
                                                                    block.accept(symbolInfo);
                                                                    symbolInfo.accept(block);
                                                                    break;
                                                                }
                                                            }
                                                        }
                                                    }

                                                    if (block.isTrivial()) {
                                                        skippedTrivial.incrementAndGet();
                                                    } else {
                                                        totalSymbols.incrementAndGet();
                                                    }
                                                });
                                            }
                                        }
                                    } catch (IOException err) {
                                        ExceptionUtils.wrapAndThrow(err);
                                    }
                                });
                            } finally {
                                totalFiles.add(path);
                            }
                        } else {
                            skippedFiles.add(path);
                        }
                    } else {
                        ignoredFiles.add(path);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            stopWatch.stop();

            log.info("indexed %d symbols from %d files (skipped: %d, ignored: %d, trivial: %d) in %s",
                    totalSymbols.toInteger(),
                    totalFiles.size(),
                    skippedFiles.size(),
                    ignoredFiles.size(),
                    skippedTrivial.toInteger(),
                    stopWatch.toString());

            return toReturn
                    .projects(args.getProjects())
                    .blocks(blocks)
                    .totalFiles(totalFiles)
                    .skippedFiles(skippedFiles)
                    .ignoredFiles(ignoredFiles)
                    .skippedTrivial(skippedTrivial.toInteger())
                    .totalNonTrivial(totalSymbols.toInteger())
                    .took(stopWatch)
                    .build();
        }
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
                                        .marks(match.getMarkSet().stream().map(new Function<Mark, PmdDuplicationMark>() {
                                            @Override
                                            @SneakyThrows
                                            public PmdDuplicationMark apply(Mark mark) {
                                                return PmdDuplicationMark.builder()
                                                        .mark(mark)
                                                        .file(Paths.get(mark.getLocation().getFileId().getAbsolutePath()).toFile())
                                                        .sourceCodeSlice(report.getSourceCodeSlice(mark).toString())
                                                        .location(SourceLocation.builder()
                                                                .startLine(mark.getLocation().getStartLine())
                                                                .endLine(mark.getLocation().getEndLine())
                                                                .startColumn(mark.getLocation().getStartColumn())
                                                                .endColumn(mark.getLocation().getEndColumn())
                                                                .build())
                                                        .build();
                                            }
                                        }).collect(ImmutableList.toImmutableList())).build());
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
    public void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        if (Boolean.FALSE.equals(args.isIgnoreCoverage())) {
            for (LanguageSpec processor : processors) {
                processor.captureCoverage(summary, analysis);
                for (FileAnalysis fileAnalysis : analysis) {
                    for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                        symbol.block().ifPresent(block -> block.coverage().subscribe(info -> {
                            if (fileAnalysis.isTestFile()) {
                                log.info("ignoring coverage of test method %s from %s", block, fileAnalysis.getFile());
                            } else {
                                log.info("capturing coverage of %s : %s", block, info);
                            }
                        }));
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
                    symbol.block().ifPresent(block -> block.metrics().subscribe(info -> log.info("capturing complexity of %s : %s", block, info)));
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
