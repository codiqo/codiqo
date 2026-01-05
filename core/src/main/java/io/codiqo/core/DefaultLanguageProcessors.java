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
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.time.StopWatch;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.IndexingSummary.IndexingSummaryBuilder;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.LanguageSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.cpd.DuplicationMatch;
import io.codiqo.api.cpd.PmdDuplicationMark;
import io.codiqo.api.cpd.PmdDuplicationMatch;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.metrics.CodeBlockMetrics;
import io.codiqo.core.java.JavaLanguageSpec;
import lombok.SneakyThrows;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CPDReport;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
import net.sourceforge.pmd.cpd.Match;
import net.sourceforge.pmd.internal.util.IOUtil;
import net.sourceforge.pmd.lang.LanguageProcessorRegistry.LanguageTerminationException;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import reactor.core.publisher.Mono;

public class DefaultLanguageProcessors implements LanguageProcessors {
    private final Log log;
    private final RunArgs args;
    private final List<LanguageSpec> processors;

    public DefaultLanguageProcessors(LogFactory logFactory, RunArgs args, Fetch fetch) {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
        this.processors = Lists.newArrayList(new JavaLanguageSpec(logFactory, args, fetch));
    }
    @Override
    public Iterator<LanguageSpec> iterator() {
        return processors.iterator();
    }
    @Override
    public Mono<?> load() {
        return Mono.zip(processors.stream()
                .map(new Function<LanguageSpec, Mono<?>>() {
                    @Override
                    public Mono<?> apply(LanguageSpec processor) {
                        return processor.load();
                    }
                })
                .collect(ImmutableList.toImmutableList()), new Function<Object[], Object>() {
                    @Override
                    public Object apply(Object[] objects) {
                        return processors.size();
                    }
                });
    }
    @Override
    @SneakyThrows
    public IndexingSummary index(CommitAnalysis analysis) {
        IndexingSummaryBuilder builder = IndexingSummary.builder();
        Multimap<File, CodeBlockInfo> blocks = MultimapBuilder.hashKeys().linkedHashSetValues().build();
        List<Path> totalFiles = Lists.newArrayList();
        List<Path> ignoredFiles = Lists.newArrayList();
        List<Path> skippedFiles = Lists.newArrayList();
        MutableInt skippedTrivial = new MutableInt();
        MutableInt totalSymbols = new MutableInt();

        Set<String> extensions = Sets.newHashSet();
        forEach(new Consumer<LanguageSpec>() {
            @Override
            public void accept(LanguageSpec processor) {
                extensions.addAll(processor.lang().getExtensions());
            }
        });

        Path projectRoot = args.getRepo();
        try (Repository repo = new FileRepositoryBuilder().setGitDir(new File(projectRoot.toFile(), ".git")).readEnvironment().findGitDir().build()) {
            DirCache dirCache = repo.readDirCache();
            Set<Path> indexed = Sets.newLinkedHashSet();
            int entryCount = dirCache.getEntryCount();
            for (int i = 0; i < entryCount; i++) {
                indexed.add(projectRoot.resolve(dirCache.getEntry(i).getPathString()));
            }

            StopWatch stopWatch = StopWatch.createStarted();
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
                @Override
                @SneakyThrows
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (indexed.contains(path)) {
                        File file = path.toFile();
                        if (FilenameUtils.isExtension(file.getName(), extensions)) {
                            try (FileInputStream input = new FileInputStream(file)) {
                                String source = IOUtils.toString(input, StandardCharsets.UTF_8);
                                forEach(new Consumer<LanguageSpec>() {
                                    @Override
                                    public void accept(LanguageSpec processor) {
                                        if (FilenameUtils.isExtension(file.getName(), processor.lang().getExtensions())) {
                                            processor.parse(file, source).forEach(new Consumer<CodeBlockInfo>() {
                                                @Override
                                                public void accept(CodeBlockInfo block) {
                                                    blocks.put(file, block);

                                                    for (FileAnalysis fileAnalysis : analysis.getFiles()) {
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
                                                }
                                            });
                                        }
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

            return builder
                    .projects(args.getProjects())
                    .blocks(blocks)
                    .totalSymbols(totalSymbols.toInteger())
                    .totalFiles(totalFiles)
                    .skippedFiles(skippedFiles)
                    .ignoredFiles(ignoredFiles)
                    .skippedTrivial(skippedTrivial.toInteger())
                    .took(stopWatch)
                    .build();
        }
    }
    @Override
    public CopyPasteDetectionSummary detectCopyPaste(IndexingSummary summary, CommitAnalysis analysis) {
        SortedSet<DuplicationMatch> matches = Sets.newTreeSet();
        MutableInt totalDuplications = new MutableInt();

        forEach(new Consumer<LanguageSpec>() {
            @Override
            @SneakyThrows
            public void accept(LanguageSpec processor) {
                if (processor.supportsCpd()) {
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
                        summary.getTotalFiles().forEach(new Consumer<Path>() {
                            @Override
                            public void accept(Path path) {
                                if (FilenameUtils.isExtension(path.toFile().getName(), processor.lang().getExtensions())) {
                                    cpd.files().addFile(path);
                                }
                            }
                        });

                        cpd.performAnalysis(new Consumer<CPDReport>() {
                            @Override
                            public void accept(CPDReport report) {
                                totalDuplications.setValue(report.getMatches().size());
                                for (Match match : report.getMatches()) {
                                    matches.add(PmdDuplicationMatch.builder()
                                            .tokenCount(match.getTokenCount())
                                            .lineCount(match.getLineCount())
                                            .locations(match.getMarkSet().stream().map(new Function<Mark, PmdDuplicationMark>() {
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
                            }
                        });
                    }
                }
            }
        });

        CopyPasteDetectionSummary toReturn = new CopyPasteDetectionSummary(totalDuplications.intValue(), matches, summary, analysis);
        toReturn.getExisting().forEach(new java.util.function.BiConsumer<CodeBlockInfo, Set<CodeBlockInfo>>() {
            @Override
            public void accept(CodeBlockInfo affected, Set<CodeBlockInfo> sources) {
                log.info("copy paste from existing code: %s copied from %s", affected, sources);
            }
        });
        toReturn.getSameCommit().forEach(new Consumer<Set<CodeBlockInfo>>() {
            @Override
            public void accept(Set<CodeBlockInfo> group) {
                log.info("copy paste within same commit: duplicated blocks %s", group);
            }
        });

        return toReturn;
    }
    @Override
    public void captureViolations(IndexingSummary summary, CommitAnalysis analysis) {
        forEach(new Consumer<LanguageSpec>() {
            @Override
            public void accept(LanguageSpec processor) {
                if (CollectionUtils.isNotEmpty(processor.pmdRules())) {
                    PMDConfiguration cfg = new PMDConfiguration(LanguageRegistry.singleton(processor.lang()));
                    cfg.setReporter(log);
                    cfg.setDefaultLanguageVersion(processor.lang().getDefaultVersion());
                    cfg.setIgnoreIncrementalAnalysis(true);
                    cfg.setFailOnViolation(false);
                    cfg.setFailOnError(true);
                    cfg.setSourceEncoding(StandardCharsets.UTF_8);
                    cfg.setMinimumPriority(RulePriority.MEDIUM);
                    processor.pmdRules().forEach(cfg::addRuleSet);

                    try (PmdAnalysis pmd = PmdAnalysis.create(cfg)) {
                        summary.getTotalFiles().forEach(new Consumer<Path>() {
                            @Override
                            public void accept(Path path) {
                                if (FilenameUtils.isExtension(path.toFile().getName(), processor.lang().getExtensions())) {
                                    pmd.files().addFile(path);
                                }
                            }
                        });

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
                                                        log.info("detected violation for %s : line(%d:%d-%d:%d)  %s",
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

                        for (Report.ProcessingError error : report.getProcessingErrors()) {
                            log.warn("PMD processing error occured in %s: %s", error.getFileId().getAbsolutePath(), error.getMsg());
                        }
                    }
                }
            }
        });
    }
    @Override
    public void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) {
        forEach(new Consumer<LanguageSpec>() {
            @Override
            public void accept(LanguageSpec processor) {
                processor.captureCoverage(summary, analysis);

                for (FileAnalysis fileAnalysis : analysis.getFiles()) {
                    for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                        symbol.block().ifPresent(new Consumer<CodeBlockInfo>() {
                            @Override
                            public void accept(CodeBlockInfo block) {
                                block.coverage().subscribe(new Consumer<CodeBlockCoverage>() {
                                    @Override
                                    public void accept(CodeBlockCoverage info) {
                                        if (fileAnalysis.isTestFile()) {
                                            log.info("ignoring coverage for method %s from %s", block, fileAnalysis.getFile());
                                        } else {
                                            log.info("analyzed coverage for %s : %s", block, info);
                                        }
                                    }
                                });
                            }
                        });
                    }
                }
            }
        });
    }
    @Override
    public void captureComplexity(IndexingSummary summary, CommitAnalysis analysis) {
        forEach(new Consumer<LanguageSpec>() {
            @Override
            @SneakyThrows
            public void accept(LanguageSpec processor) {
                for (FileAnalysis fileAnalysis : analysis.getFiles()) {
                    for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                        symbol.block().ifPresent(new Consumer<CodeBlockInfo>() {
                            @Override
                            public void accept(CodeBlockInfo block) {
                                block.metrics().subscribe(new Consumer<CodeBlockMetrics>() {
                                    @Override
                                    public void accept(CodeBlockMetrics info) {
                                        log.info("analyzed complexity for %s : %s", block, info);
                                    }
                                });
                            }
                        });
                    }
                }
            }
        });
    }
    @Override
    public void close() throws IOException {
        Exception err = IOUtil.closeAll(processors);
        if (Objects.nonNull(err)) {
            throw new LanguageTerminationException(err);
        }
    }
}
