package io.codiqo.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.event.Level;

import com.google.common.collect.Lists;

import io.codiqo.api.DeltaAnalyzer;
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
import io.codiqo.util.JGit;

public class JGitDeltaAnalyzer implements DeltaAnalyzer {
    private final Log log;
    private final RunArgs args;

    public JGitDeltaAnalyzer(LogFactory logFactory, RunArgs args) {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
    }
    @Override
    public CommitAnalysis analyze() throws Exception {
        if (StringUtils.isNotEmpty(args.getCommitId())) {
            ObjectId commit = args.getGit().resolve(args.getCommitId());
            try (RevWalk revWalk = new RevWalk(args.getGit())) {
                RevCommit revCommit = revWalk.parseCommit(commit);
                return analyzeCommit(revCommit);
            }
        }

        try (Git git = Git.wrap(args.getGit())) {
            Status status = git.status().call();
            return analyzeUncommitted(status);
        }
    }
    @Override
    public Optional<String> fileContentAtRevision(String filePath, String revisionId) throws Exception {
        ObjectId revId = args.getGit().resolve(revisionId);
        if (Objects.isNull(revId)) {
            return Optional.empty();
        }

        try (RevWalk revWalk = new RevWalk(args.getGit())) {
            RevCommit commit = revWalk.parseCommit(revId);
            return fileContentFromCommit(commit, filePath);
        }
    }
    @Override
    public List<FileRevisionInfo> getFileHistory(String filePath, int maxRevisions) throws Exception {
        List<FileRevisionInfo> toReturn = Lists.newArrayList();

        try (RevWalk revWalk = new RevWalk(args.getGit())) {
            ObjectId head = args.getGit().resolve("HEAD");
            revWalk.markStart(revWalk.parseCommit(head));
            revWalk.setTreeFilter(PathFilter.create(filePath));

            MutableInt count = new MutableInt();
            for (RevCommit commit : revWalk) {
                if (count.intValue() >= maxRevisions) {
                    break;
                }

                fileContentFromCommit(commit, filePath).ifPresent(content -> {
                    GitFileRevisionInfo info = new GitFileRevisionInfo();
                    info.setRevisionId(commit.getName());
                    info.setAuthor(commit.getAuthorIdent().getName());
                    info.setTimestamp(Instant.ofEpochSecond(commit.getCommitTime()));
                    info.setMessage(commit.getShortMessage());
                    info.setContent(content);
                    toReturn.add(info);
                    count.increment();
                });
            }
        }

        return toReturn;
    }
    @Override
    public Optional<String> fileContentFromCommit(RevCommit commit, String filePath) throws Exception {
        try (TreeWalk treeWalk = TreeWalk.forPath(args.getGit(), filePath, commit.getTree())) {
            if (Objects.nonNull(treeWalk)) {
                ObjectId blobId = treeWalk.getObjectId(BigInteger.ZERO.intValue());
                ObjectLoader loader = args.getGit().open(blobId);
                byte[] bytes = loader.getBytes();
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
        }

        return Optional.empty();
    }
    @Override
    public Optional<String> fileContentFromWorkingTree(String filePath) throws Exception {
        Path path = args.getGit().getWorkTree().toPath().resolve(filePath);
        if (Files.exists(path) && Files.isRegularFile(path)) {
            return Optional.of(FileUtils.readFileToString(path.toFile(), StandardCharsets.UTF_8));
        }

        return Optional.empty();
    }
    @Override
    public CommitAnalysis analyzeCommit(RevCommit commit) throws Exception {
        GitCommitAnalysis toReturn = new GitCommitAnalysis();

        toReturn.setCommitId(commit.getName());
        toReturn.setMessage(commit.getFullMessage());
        JGit.detectRevertedSha(commit.getFullMessage()).ifPresent(sha -> {
            toReturn.setRevertCommit(true);
            toReturn.setRevertedCommitId(sha);
        });

        toReturn.setAuthor(commit.getAuthorIdent().getName());
        toReturn.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
        toReturn.setAuthorTimestamp(Date.from(commit.getAuthorIdent().getWhenAsInstant()));

        toReturn.setCommitter(commit.getCommitterIdent().getName());
        toReturn.setCommitterEmail(commit.getCommitterIdent().getEmailAddress());
        toReturn.setCommitTimestamp(Date.from(commit.getCommitterIdent().getWhenAsInstant()));

        toReturn.setMergeCommit(commit.getParentCount() > 1);
        toReturn.getParentIds().addAll(JGit.parentShas(commit));
        toReturn.getBranches().addAll(JGit.branchesContaining(args.getGit(), commit.getName()));

        if (commit.getParentCount() == 0) {
            return toReturn;
        }

        RevCommit parent = commit.getParent(BigInteger.ZERO.intValue());
        try (RevWalk walk = new RevWalk(args.getGit())) {
            try (ObjectReader reader = args.getGit().newObjectReader()) {
                try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    RevCommit fullCommit = walk.parseCommit(commit.getId());
                    RevCommit fullParent = walk.parseCommit(parent.getId());

                    formatter.setRepository(args.getGit());
                    formatter.setDetectRenames(true);

                    CanonicalTreeParser oldTree = new CanonicalTreeParser();
                    oldTree.reset(reader, fullParent.getTree().getId());

                    CanonicalTreeParser newTree = new CanonicalTreeParser();
                    newTree.reset(reader, fullCommit.getTree().getId());

                    List<DiffEntry> diffs = formatter.scan(oldTree, newTree);
                    log.log(Level.DEBUG, "analyzing commit: " + commit.getName() + " with " + diffs);
                    for (DiffEntry diff : diffs) {
                        analyzeFileDiff(diff, formatter, fullParent, fullCommit).ifPresent(toReturn.getFiles()::add);
                    }

                    toReturn.setFilesChanged(toReturn.getFiles().size());
                }
            }
        }

        return toReturn;
    }
    @Override
    public CommitAnalysis analyzeUncommitted(Status status) throws Exception {
        GitCommitAnalysis toReturn = new GitCommitAnalysis();

        toReturn.setCommitId(UUID.randomUUID().toString());
        toReturn.setMessage("Uncommitted changes analysis at " + LocalDateTime.now().toString() + " by " + System.getProperty("user.name"));

        toReturn.setAuthor(System.getProperty("user.name"));
        toReturn.setAuthorTimestamp(new Date());

        toReturn.setCommitter(toReturn.getAuthor());
        toReturn.setCommitTimestamp(toReturn.getAuthorTimestamp());

        toReturn.setMergeCommit(false);
        toReturn.getBranches().add(args.getGit().getBranch());

        ObjectId headId = args.getGit().resolve(Constants.HEAD);
        if (Objects.isNull(headId)) {
            return analyzeUncommittedNoHead(status);
        }

        toReturn.getParentIds().add(headId.getName());

        try (RevWalk walk = new RevWalk(args.getGit())) {
            try (ObjectReader reader = args.getGit().newObjectReader()) {
                RevCommit headCommit = walk.parseCommit(headId);

                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, headCommit.getTree().getId());

                FileTreeIterator workingTree = new FileTreeIterator(args.getGit());

                try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    formatter.setRepository(args.getGit());
                    formatter.setDetectRenames(true);

                    List<DiffEntry> diffs = formatter.scan(oldTree, workingTree);

                    log.log(Level.DEBUG, "analyzing uncommitted changes with " + diffs.size() + " diffs");

                    for (DiffEntry diff : diffs) {
                        analyzeUncommittedFileDiff(diff, formatter, headCommit, oldTree).ifPresent(toReturn.getFiles()::add);
                    }
                }
            }
        }

        if (args.isIncludeUntracked()) {
            for (String untrackedFile : status.getUntracked()) {
                analyzeUntrackedFile(untrackedFile).ifPresent(fileAnalysis -> toReturn.getFiles().add(fileAnalysis));
            }
        }

        toReturn.setFilesChanged(toReturn.getFiles().size());
        return toReturn;
    }
    @Override
    public CommitAnalysis analyzeUncommittedNoHead(Status status) throws Exception {
        GitCommitAnalysis toReturn = new GitCommitAnalysis();

        toReturn.setCommitId(UUID.randomUUID().toString());
        toReturn.setMessage("Initial uncommitted changes (no commits yet)");
        toReturn.setAuthor(System.getProperty("user.name"));
        toReturn.setAuthorTimestamp(new Date());
        toReturn.setCommitter(toReturn.getAuthor());
        toReturn.setCommitTimestamp(toReturn.getAuthorTimestamp());
        toReturn.setMergeCommit(false);

        for (String addedFile : status.getAdded()) {
            analyzeUntrackedFile(addedFile).ifPresent(fileAnalysis -> toReturn.getFiles().add(fileAnalysis));
        }

        if (args.isIncludeUntracked()) {
            for (String untrackedFile : status.getUntracked()) {
                analyzeUntrackedFile(untrackedFile).ifPresent(fileAnalysis -> toReturn.getFiles().add(fileAnalysis));
            }
        }

        toReturn.setFilesChanged(toReturn.getFiles().size());
        return toReturn;
    }
    @Override
    public Optional<FileAnalysis> analyzeUncommittedFileDiff(DiffEntry diff, DiffFormatter formatter, RevCommit headCommit) throws Exception {
        try (ObjectReader reader = args.getGit().newObjectReader()) {
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, headCommit.getTree().getId());
            return analyzeUncommittedFileDiff(diff, formatter, headCommit, oldTree);
        }
    }
    @Override
    public Optional<FileAnalysis> analyzeUncommittedFileDiff(DiffEntry diff, DiffFormatter formatter, RevCommit head, CanonicalTreeParser old) throws Exception {
        FileHeader fileHeader = formatter.toFileHeader(diff);
        if (fileHeader.getPatchType() == FileHeader.PatchType.UNIFIED) {
            String path = diff.getChangeType() == DiffEntry.ChangeType.DELETE ? diff.getOldPath() : diff.getNewPath();
            File destination = args.getGit().getWorkTree().toPath().resolve(path).toFile();
            GitFileAnalysis toReturn = new GitFileAnalysis();

            args.owner(destination).ifPresent(project -> {
                toReturn.accept(project);
                toReturn.setTestFile(project.isTestResource(destination));
            });

            toReturn.setOldPath(diff.getOldPath());
            toReturn.setNewPath(diff.getNewPath());
            toReturn.setFile(destination);
            toReturn.setChangeType(diff.getChangeType());

            if (diff.getChangeType() != DiffEntry.ChangeType.ADD) {
                fileContentFromCommit(head, diff.getOldPath()).ifPresent(toReturn::setContentBefore);
            }

            if (diff.getChangeType() != DiffEntry.ChangeType.DELETE) {
                fileContentFromWorkingTree(diff.getNewPath()).ifPresent(toReturn::setContentAfter);
            }

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                try (Git git = Git.wrap(args.getGit())) {
                    try (ObjectReader reader = args.getGit().newObjectReader()) {
                        CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                        oldTreeIter.reset(reader, head.getTree().getId());

                        git.diff()
                                .setOldTree(oldTreeIter)
                                .setNewTree(new FileTreeIterator(args.getGit()))
                                .setPathFilter(PathFilter.create(path))
                                .setContextLines(args.getDiffContextLines())
                                .setOutputStream(output)
                                .call();
                    }
                }
                toReturn.setDiffText(output.toString(StandardCharsets.UTF_8));
            }

            GitStructuredDiff structuredDiff = new GitStructuredDiff();
            structuredDiff.setOldPath(diff.getOldPath());
            structuredDiff.setNewPath(diff.getNewPath());
            structuredDiff.setChangeType(diff.getChangeType());
            toReturn.setStructuredDiff(structuredDiff);

            for (Edit edit : fileHeader.toEditList()) {
                GitDiffHunk hunk = new GitDiffHunk();
                hunk.setOldStartLine(edit.getBeginA());
                hunk.setOldEndLine(edit.getEndA());
                hunk.setNewStartLine(edit.getBeginB());
                hunk.setNewEndLine(edit.getEndB());
                hunk.setType(edit.getType());
                structuredDiff.getHunks().add(hunk);
            }

            return Optional.of(toReturn);
        }
        return Optional.empty();
    }
    @Override
    public Optional<FileAnalysis> analyzeUntrackedFile(String filePath) throws Exception {
        File destination = args.getGit().getWorkTree().toPath().resolve(filePath).toFile();
        if (destination.exists() && destination.isFile()) {
            GitFileAnalysis toReturn = new GitFileAnalysis();

            args.owner(destination).ifPresent(project -> {
                toReturn.accept(project);
                toReturn.setTestFile(project.isTestResource(destination));
            });

            toReturn.setNewPath(args.getGit().getWorkTree().toPath().relativize(destination.toPath()).normalize().toString());
            toReturn.setFile(destination);
            toReturn.setChangeType(DiffEntry.ChangeType.ADD);

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                try (DiffFormatter formatter = new DiffFormatter(output)) {
                    formatter.setRepository(args.getGit());
                    formatter.setContext(args.getDiffContextLines());
                    formatter.setPathFilter(PathFilter.create(filePath));

                    FileTreeIterator workingTreeIter = new FileTreeIterator(args.getGit());
                    List<DiffEntry> entries = formatter.scan(new EmptyTreeIterator(), workingTreeIter);

                    if (CollectionUtils.isNotEmpty(entries)) {
                        DiffEntry diff = entries.get(0);
                        FileHeader fileHeader = formatter.toFileHeader(diff);
                        if (fileHeader.getPatchType() == FileHeader.PatchType.UNIFIED) {
                            formatter.format(diff);
                            output.flush();
                            toReturn.setDiffText(output.toString(StandardCharsets.UTF_8.name()));

                            GitStructuredDiff structuredDiff = new GitStructuredDiff();
                            structuredDiff.setOldPath(diff.getOldPath());
                            structuredDiff.setNewPath(diff.getNewPath());
                            structuredDiff.setChangeType(diff.getChangeType());
                            toReturn.setStructuredDiff(structuredDiff);

                            for (Edit edit : fileHeader.toEditList()) {
                                GitDiffHunk hunk = new GitDiffHunk();
                                hunk.setOldStartLine(edit.getBeginA());
                                hunk.setOldEndLine(edit.getEndA());
                                hunk.setNewStartLine(edit.getBeginB());
                                hunk.setNewEndLine(edit.getEndB());
                                hunk.setType(edit.getType());
                                structuredDiff.getHunks().add(hunk);
                            }
                        } else {
                            return Optional.empty();
                        }
                    }
                }
            }

            toReturn.setContentAfter(FileUtils.readFileToString(destination, StandardCharsets.UTF_8));
            toReturn.setContentBefore(null);

            return Optional.of(toReturn);
        }
        return Optional.empty();
    }
    @Override
    public Optional<FileAnalysis> analyzeFileDiff(DiffEntry diff, DiffFormatter formatter, RevCommit parent, RevCommit current) throws Exception {
        FileHeader fileHeader = formatter.toFileHeader(diff);
        if (fileHeader.getPatchType() == FileHeader.PatchType.UNIFIED) {
            String path = diff.getChangeType() == DiffEntry.ChangeType.DELETE ? diff.getOldPath() : diff.getNewPath();
            File destination = args.getGit().getWorkTree().toPath().resolve(path).toFile();
            GitFileAnalysis toReturn = new GitFileAnalysis();

            args.owner(destination).ifPresent(project -> {
                toReturn.accept(project);
                toReturn.setTestFile(project.isTestResource(destination));
            });

            toReturn.setOldPath(diff.getOldPath());
            toReturn.setNewPath(diff.getNewPath());
            toReturn.setFile(destination);
            toReturn.setChangeType(diff.getChangeType());
            if (diff.getChangeType() != DiffEntry.ChangeType.ADD) {
                fileContentFromCommit(parent, diff.getOldPath()).ifPresent(toReturn::setContentBefore);
            }
            if (diff.getChangeType() != DiffEntry.ChangeType.DELETE) {
                fileContentFromCommit(current, diff.getNewPath()).ifPresent(toReturn::setContentAfter);
            }

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                try (DiffFormatter printer = new DiffFormatter(output)) {
                    printer.setRepository(args.getGit());
                    printer.setContext(args.getDiffContextLines());
                    printer.format(diff);
                    output.flush();
                    toReturn.setDiffText(output.toString(StandardCharsets.UTF_8.name()));
                }
            }

            GitStructuredDiff structuredDiff = new GitStructuredDiff();
            structuredDiff.setOldPath(diff.getOldPath());
            structuredDiff.setNewPath(diff.getNewPath());
            structuredDiff.setChangeType(diff.getChangeType());
            toReturn.setStructuredDiff(structuredDiff);

            for (Edit edit : fileHeader.toEditList()) {
                GitDiffHunk hunk = new GitDiffHunk();
                hunk.setOldStartLine(edit.getBeginA());
                hunk.setOldEndLine(edit.getEndA());
                hunk.setNewStartLine(edit.getBeginB());
                hunk.setNewEndLine(edit.getEndB());
                hunk.setType(edit.getType());
                structuredDiff.getHunks().add(hunk);
            }

            return Optional.of(toReturn);
        }
        return Optional.empty();
    }
}
