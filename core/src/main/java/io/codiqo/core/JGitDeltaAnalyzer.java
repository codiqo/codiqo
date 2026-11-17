package io.codiqo.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
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
import io.codiqo.lang.config.FileKind;
import io.codiqo.lang.config.FileKinds;
import io.codiqo.util.JGit;

public class JGitDeltaAnalyzer implements DeltaAnalyzer {
    /** the only tree a single-tree TreeWalk holds */
    private static final int FIRST_TREE = 0;
    /** the main line: a merge node's delta against parent[0] is what the merged-in branch contributed */
    private static final int FIRST_PARENT = 0;

    private final Log log;
    private final RunArgs args;

    public JGitDeltaAnalyzer(LogFactory logFactory, RunArgs args) {
        this.log = logFactory.getLogger(getClass());
        this.args = Objects.requireNonNull(args);
    }
    /**
     * The run's commit, {@link RunArgs#getCommitId()}. Uncommitted changes are analysed as a commit of the working tree
     * too (the Maven plugin's {@code WorkingTreeCommit}), so every rule that reads a commit's diff reads theirs.
     */
    @Override
    public CommitAnalysis analyze() throws Exception {
        Validate.notBlank(args.getCommitId(), "the run names no commit to analyse; uncommitted changes are analysed as a commit of the working tree");
        try (RevWalk revWalk = new RevWalk(args.getGit())) {
            return analyzeCommit(revWalk.parseCommit(args.getGit().resolve(args.getCommitId())));
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
                ObjectId blobId = treeWalk.getObjectId(FIRST_TREE);
                ObjectLoader loader = args.getGit().open(blobId);
                byte[] bytes = loader.getBytes();
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
        }

        return Optional.empty();
    }
    private static void applyWhitespacePolicy(DiffFormatter formatter, String path) {
        formatter.setDiffComparator(FileKinds.of(path).filter(FileKind::isReindentCosmetic).isPresent()
                ? RawTextComparator.WS_IGNORE_LEADING
                : RawTextComparator.DEFAULT);
    }
    @Override
    public CommitAnalysis analyzeCommit(RevCommit commit) throws Exception {
        GitCommitAnalysis toReturn = new GitCommitAnalysis();

        toReturn.setCommitId(commit.getName());
        toReturn.setMessage(commit.getFullMessage());
        JGit.detectRevertedSha(args.getGit(), commit.getFullMessage()).ifPresent(sha -> {
            toReturn.setRevertCommit(true);
            toReturn.setRevertedCommitId(sha);
        });

        /**
         * a merge node's parent[0] delta is the merged-in branch's net change, so credit goes to the developer
         * who wrote most of the side branch ({@link JGit#creditedAuthor}), not whoever clicked merge. the merge's
         * own timestamp is kept: attribution changes who, not when
         */
        PersonIdent effectiveAuthor = JGit.creditedAuthor(args.getGit(), commit);
        toReturn.setAuthor(effectiveAuthor.getName());
        toReturn.setAuthorEmail(effectiveAuthor.getEmailAddress());
        toReturn.setAuthorTimestamp(Date.from(commit.getAuthorIdent().getWhenAsInstant()));

        toReturn.setCommitter(commit.getCommitterIdent().getName());
        toReturn.setCommitterEmail(commit.getCommitterIdent().getEmailAddress());
        toReturn.setCommitTimestamp(Date.from(commit.getCommitterIdent().getWhenAsInstant()));

        toReturn.setMergeCommit(JGit.isMerge(commit));
        toReturn.getParentIds().addAll(JGit.parentShas(commit));
        toReturn.getBranches().addAll(JGit.branchesContaining(args.getGit(), commit.getName()));

        try (RevWalk walk = new RevWalk(args.getGit())) {
            try (ObjectReader reader = args.getGit().newObjectReader()) {
                try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    RevCommit fullCommit = walk.parseCommit(commit.getId());

                    formatter.setRepository(args.getGit());
                    formatter.setDetectRenames(true);

                    /**
                     * a root commit has no parent tree, so the empty tree is its baseline. getParentCount()
                     * cannot tell one from a shallow graft — JGit reports zero parents for every commit in
                     * .git/shallow — and giving a graft that baseline would bill the whole repository to one
                     * commit, so only a genuinely parent-less commit gets it and the boundary is left file-less
                     */
                    Optional<RevCommit> parent = Optional.empty();
                    AbstractTreeIterator oldTree = new EmptyTreeIterator();
                    if (commit.getParentCount() > 0) {
                        RevCommit fullParent = walk.parseCommit(commit.getParent(FIRST_PARENT).getId());
                        parent = Optional.of(fullParent);

                        CanonicalTreeParser parentTree = new CanonicalTreeParser();
                        parentTree.reset(reader, fullParent.getTree().getId());
                        oldTree = parentTree;
                    } else if (reader.getShallowCommits().contains(commit.getId())) {
                        log.log(Level.WARN, "commit " + commit.getName() + " sits on the shallow-clone boundary, so its parent is not available locally and its delta cannot be computed — re-run with full history (fetch-depth: 0) to analyze it");
                        toReturn.setHistoryIncomplete(true);
                        return toReturn;
                    }

                    CanonicalTreeParser newTree = new CanonicalTreeParser();
                    newTree.reset(reader, fullCommit.getTree().getId());

                    List<DiffEntry> diffs = formatter.scan(oldTree, newTree);
                    log.log(Level.DEBUG, "analyzing commit: " + commit.getName() + " with " + diffs);
                    /**
                     * A submodule's entry is a pointer to a commit of another repository, which has no lines here: read
                     * as a file, its id is no object of this repository and the read throws MissingObjectException.
                     * A commit that moves a submodule, or a working tree with a nested clone, otherwise fails its run.
                     */
                    for (DiffEntry diff : diffs) {
                        if (BooleanUtils.and(new boolean[] { FileMode.GITLINK != diff.getOldMode(), FileMode.GITLINK != diff.getNewMode() })) {
                            analyzeFileDiff(diff, formatter, parent, fullCommit).ifPresent(toReturn.getFiles()::add);
                        }
                    }

                    toReturn.setFilesChanged(toReturn.getFiles().size());
                }
            }
        }

        return toReturn;
    }
    @Override
    public Optional<FileAnalysis> analyzeFileDiff(DiffEntry diff, DiffFormatter formatter, Optional<RevCommit> parent, RevCommit current) throws Exception {
        String path = JGit.effectivePath(diff);
        applyWhitespacePolicy(formatter, path);

        FileHeader fileHeader = formatter.toFileHeader(diff);
        if (fileHeader.getPatchType() == FileHeader.PatchType.UNIFIED) {
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
            if (JGit.hasContentBefore(diff.getChangeType())) {
                fileContentFromCommit(parent.orElseThrow(() -> new IllegalStateException(String.format(
                        "%s of %s reports content before the change but the commit has no parent to read it from",
                        diff.getChangeType(), path))), diff.getOldPath()).ifPresent(toReturn::setContentBefore);
            }
            if (JGit.hasContentAfter(diff.getChangeType())) {
                fileContentFromCommit(current, diff.getNewPath()).ifPresent(toReturn::setContentAfter);
            }

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                try (DiffFormatter printer = new DiffFormatter(output)) {
                    printer.setRepository(args.getGit());
                    printer.setContext(args.getDiffContextLines());
                    applyWhitespacePolicy(printer, path);
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
