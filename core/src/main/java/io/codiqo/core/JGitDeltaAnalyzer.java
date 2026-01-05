package io.codiqo.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.eclipse.lsp4j.DocumentSymbol;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;

import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.LanguageSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.diff.FileRevisionInfo;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.diff.GitCommitAnalysis;
import io.codiqo.core.diff.GitDiffHunk;
import io.codiqo.core.diff.GitFileAnalysis;
import io.codiqo.core.diff.GitFileRevisionInfo;
import io.codiqo.core.diff.GitStructuredDiff;
import io.codiqo.core.java.JavaLanguageSpec;
import lombok.SneakyThrows;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageRegistry;

public class JGitDeltaAnalyzer implements DeltaAnalyzer {
    private final Log log;
    private final LanguageProcessors registry;
    private final Repository repo;
    private final RunArgs args;

    @SneakyThrows
    public JGitDeltaAnalyzer(LogFactory logFactory, LanguageProcessors registry, RunArgs args) {
        this.log = logFactory.getLogger(getClass());
        this.registry = Objects.requireNonNull(registry);
        this.args = Objects.requireNonNull(args);
        this.repo = new FileRepositoryBuilder()
                .setGitDir(new File(args.getRepo().toFile(), ".git"))
                .readEnvironment()
                .findGitDir()
                .build();
    }
    @Override
    @SneakyThrows
    public CommitAnalysis analyze(String commitId) {
        ObjectId commit = repo.resolve(Objects.requireNonNull(commitId));
        try (RevWalk revWalk = new RevWalk(repo)) {
            RevCommit revCommit = revWalk.parseCommit(commit);
            return analyzeCommit(revCommit);
        }
    }
    @Override
    @SneakyThrows
    public CommitAnalysis analyzeHead() {
        ObjectId head = repo.resolve("HEAD");
        try (RevWalk revWalk = new RevWalk(repo)) {
            RevCommit commit = revWalk.parseCommit(head);
            return analyzeCommit(commit);
        }
    }
    @Override
    @SneakyThrows
    public String getFileContentAtRevision(String filePath, String revisionId) {
        ObjectId revId = repo.resolve(revisionId);
        if (Objects.isNull(revId)) {
            return null;
        }

        try (RevWalk revWalk = new RevWalk(repo)) {
            RevCommit commit = revWalk.parseCommit(revId);
            return getFileContentFromCommit(commit, filePath);
        }
    }
    @Override
    @SneakyThrows
    public List<FileRevisionInfo> getFileHistory(String filePath, int maxRevisions) {
        List<FileRevisionInfo> toReturn = Lists.newArrayList();

        try (RevWalk revWalk = new RevWalk(repo)) {
            ObjectId head = repo.resolve("HEAD");
            revWalk.markStart(revWalk.parseCommit(head));
            revWalk.setTreeFilter(PathFilter.create(filePath));

            int count = 0;
            for (RevCommit commit : revWalk) {
                if (count >= maxRevisions) {
                    break;
                }

                String content = getFileContentFromCommit(commit, filePath);
                if (Objects.nonNull(content)) {
                    GitFileRevisionInfo info = new GitFileRevisionInfo();
                    info.setRevisionId(commit.getName());
                    info.setAuthor(commit.getAuthorIdent().getName());
                    info.setTimestamp(Instant.ofEpochSecond(commit.getCommitTime()));
                    info.setMessage(commit.getShortMessage());
                    info.setContent(content);
                    toReturn.add(info);
                    count++;
                }
            }
        }

        return toReturn;
    }
    @Override
    @SneakyThrows
    public String getFileContentFromCommit(RevCommit commit, String filePath) {
        try (TreeWalk treeWalk = TreeWalk.forPath(repo, filePath, commit.getTree())) {
            if (Objects.isNull(treeWalk)) {
                return null;
            }

            ObjectId blobId = treeWalk.getObjectId(BigInteger.ZERO.intValue());
            ObjectLoader loader = repo.open(blobId);
            byte[] bytes = loader.getBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    @Override
    @SneakyThrows
    public CommitAnalysis analyzeCommit(RevCommit commit) {
        GitCommitAnalysis toReturn = new GitCommitAnalysis();

        toReturn.setCommitId(commit.getName());
        toReturn.setCommitIdShort(commit.abbreviate(8).name());
        toReturn.setMessage(commit.getFullMessage());

        toReturn.setAuthor(commit.getAuthorIdent().getName());
        toReturn.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
        toReturn.setAuthorTimestamp(commit.getAuthorIdent().getWhen().toInstant());

        toReturn.setCommitter(commit.getCommitterIdent().getName());
        toReturn.setCommitterEmail(commit.getCommitterIdent().getEmailAddress());
        toReturn.setCommitTimestamp(commit.getCommitterIdent().getWhen().toInstant());

        toReturn.setMergeCommit(commit.getParentCount() > 1);
        for (int i = 0; i < commit.getParentCount(); i++) {
            toReturn.getParentIds().add(commit.getParent(i).getName());
        }

        if (Objects.nonNull(commit.getEncoding())) {
            toReturn.setEncoding(commit.getEncoding().name());
        }

        byte[] rawGpgSig = commit.getRawGpgSignature();
        if (Objects.nonNull(rawGpgSig) && rawGpgSig.length > 0) {
            toReturn.setGpgSignature("signed");
        }

        for (Ref ref : Git.wrap(repo).branchList().setContains(commit.getName()).call()) {
            String branchName = ref.getName();
            if (branchName.startsWith("refs/heads/")) {
                branchName = branchName.substring("refs/heads/".length());
            }
            toReturn.getBranches().add(branchName);
        }

        if (commit.getParentCount() == 0) {
            return toReturn;
        }

        RevCommit parent = commit.getParent(BigInteger.ZERO.intValue());
        try (ObjectReader reader = repo.newObjectReader()) {
            try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit fullCommit = walk.parseCommit(commit.getId());
                    RevCommit fullParent = walk.parseCommit(parent.getId());

                    formatter.setRepository(repo);
                    formatter.setDetectRenames(true);

                    CanonicalTreeParser oldTree = new CanonicalTreeParser();
                    oldTree.reset(reader, fullParent.getTree().getId());

                    CanonicalTreeParser newTree = new CanonicalTreeParser();
                    newTree.reset(reader, fullCommit.getTree().getId());

                    List<DiffEntry> diffs = formatter.scan(oldTree, newTree);
                    log.log(Level.DEBUG, "analyzing commit: " + commit.getName() + " with " + diffs);
                    for (DiffEntry diff : diffs) {
                        FileAnalysis fileAnalysis = analyzeFileDiff(diff, formatter, fullParent, fullCommit);
                        if (Objects.nonNull(fileAnalysis)) {
                            toReturn.getFiles().add(fileAnalysis);
                        }
                    }

                    toReturn.setFilesChanged(toReturn.getFiles().size());
                }
            }
        }

        return toReturn;
    }
    @Override
    @SneakyThrows
    public GitFileAnalysis analyzeFileDiff(DiffEntry diff, DiffFormatter formatter, RevCommit parent, RevCommit current) {
        String path = diff.getChangeType() == DiffEntry.ChangeType.DELETE ? diff.getOldPath() : diff.getNewPath();

        GitFileAnalysis toReturn = new GitFileAnalysis();
        toReturn.setPath(repo.getWorkTree().toPath().resolve(path));
        toReturn.setChangeType(diff.getChangeType());
        if (diff.getChangeType() != DiffEntry.ChangeType.ADD) {
            String contentBefore = getFileContentFromCommit(parent, diff.getOldPath());
            toReturn.setContentBefore(contentBefore);
        }
        if (diff.getChangeType() != DiffEntry.ChangeType.DELETE) {
            String contentAfter = getFileContentFromCommit(current, diff.getNewPath());
            toReturn.setContentAfter(contentAfter);
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (DiffFormatter printer = new DiffFormatter(output)) {
                printer.setRepository(repo);
                printer.format(diff);
                output.flush();
                toReturn.setDiffText(output.toString(StandardCharsets.UTF_8.name()));
            }
        }
        for (Language lang : LanguageRegistry.PMD) {
            if (FilenameUtils.isExtension(path, lang.getExtensions())) {
                toReturn.setLanguage(lang);
                break;
            }
        }

        FileHeader fileHeader = formatter.toFileHeader(diff);

        GitStructuredDiff structuredDiff = new GitStructuredDiff();
        structuredDiff.setOldPath(diff.getOldPath());
        structuredDiff.setNewPath(diff.getNewPath());
        structuredDiff.setChangeType(diff.getChangeType());
        toReturn.setStructuredDiff(structuredDiff);

        Collection<Integer> modifiedLines = Lists.newArrayList();
        for (Edit edit : fileHeader.toEditList()) {
            int beginB = edit.getBeginB();
            int endB = edit.getEndB();
            for (int line = beginB; line < endB; line++) {
                modifiedLines.add(line);
            }

            GitDiffHunk hunk = new GitDiffHunk();
            hunk.setOldStartLine(edit.getBeginA());
            hunk.setOldEndLine(edit.getEndA());
            hunk.setNewStartLine(edit.getBeginB());
            hunk.setNewEndLine(edit.getEndB());
            hunk.setType(edit.getType());
            structuredDiff.getHunks().add(hunk);
        }

        if (diff.getChangeType() != DiffEntry.ChangeType.DELETE) {
            Path resolve = repo.getWorkTree().toPath().resolve(diff.getNewPath());
            analyzeSymbols(toReturn, resolve, modifiedLines);
        }

        return toReturn;
    }
    @Override
    public void analyzeSymbols(FileAnalysis analysis, Path path, Collection<Integer> modifiedLines) {
        StopWatch stopWatch = StopWatch.createStarted();
        registry.forEach(new Consumer<LanguageSpec>() {
            @Override
            @SneakyThrows
            public void accept(LanguageSpec processor) {
                if (processor instanceof JavaLanguageSpec) {
                    JavaLanguageSpec importer = (JavaLanguageSpec) processor;
                    CompletableFuture<List<DocumentSymbol>> future = importer.documentSymbol(path.toUri().toString());
                    Collection<DocumentSymbol> symbols = future.get(args.getImportTimeout().getSeconds(), TimeUnit.SECONDS);
                    symbols.stream().filter(new Predicate<DocumentSymbol>() {
                        @Override
                        public boolean test(DocumentSymbol s) {
                            return JavaLanguageSpec.TYPES.contains(s.getKind());
                        }
                    }).forEach(new Consumer<DocumentSymbol>() {
                        @Override
                        public void accept(DocumentSymbol symbol) {
                            processor.identifyAffectedSymbols(analysis, symbol, path, modifiedLines);
                            stopWatch.stop();
                            log.log(Level.DEBUG, "analyzed file: %s(%s), modified lines: %d, affected symbols: %d, took: %s",
                                    analysis.getChangeType(),
                                    path,
                                    modifiedLines.size(),
                                    analysis.getPotentiallyAffectedSymbols().size(),
                                    stopWatch);
                        }
                    });
                }
            }
        });
    }
    @Override
    public void close() {
        repo.close();
    }
}
