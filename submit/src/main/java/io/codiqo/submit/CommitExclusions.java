package io.codiqo.submit;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.util.JGit;
import lombok.experimental.UtilityClass;

/**
 * The rules that decide a commit is reported as excluded rather than scored. Build-tool neutral and shared: a commit
 * one build tool scores and the other excludes would give two project histories for one repository.
 *
 * <p>{@link CommitIndexer} applies the matching rule at index time, and the two must agree — a commit the index keeps
 * but the analysis excludes is re-reported as missing on every run and rebuilt each time.
 */
@UtilityClass
public class CommitExclusions {
    /**
     * Gates that need only the commit and the run configuration, so they run before the clone/build/analysis pipeline.
     */
    public static Optional<Exclusion> beforeAnalysis(RunArgs args, IndexedCommits indexed) throws Exception {
        if (JGit.isMerge(args.getGit(), args.getCommitId())) {
            Optional<Exclusion> merge = mergeSkipReason(args, indexed).map(reason -> new Exclusion(reason, AnalysisExcludeCategory.MERGE_COMMIT));
            if (merge.isPresent()) {
                return merge;
            }
        }
        if (args.isExcludedAuthor(creditedAuthorEmail(args))) {
            return Optional.of(new Exclusion("author excluded by codiqo.excludeAuthorEmails", AnalysisExcludeCategory.FILTERED_BY_RULES));
        }
        return Optional.empty();
    }
    /**
     * Gates that need the computed delta: whether the delta could be computed at all, whether anything in the diff is
     * analysable, and whether the commit survives the include-rules. The caller still submits diff-only file models
     * for an excluded commit, so the backend records what changed even though nothing was scored.
     */
    public static Optional<Exclusion> afterDelta(RunArgs args, CommitAnalysis analysis, Collection<String> extensions, Collection<String> changedFiles) {
        /**
         * Checked first: when the delta could not be computed at all, the changed-file list is empty, and the
         * analysable-file gate below would misreport the commit as having no files of a registered language.
         */
        if (analysis.isHistoryIncomplete()) {
            return Optional.of(new Exclusion(
                    String.format("commit %s sits on a shallow-clone boundary, so its parent is not present locally and its delta cannot be computed"
                            + " — re-run with full history (fetch-depth: 0)", analysis.getCommitId()),
                    AnalysisExcludeCategory.INCOMPLETE_HISTORY));
        }

        boolean branchMatches = args.matchesByBranch(analysis.getBranches());
        boolean authorMatches = args.matchesByAuthor(analysis.getAuthorEmail());
        if (BooleanUtils.or(new boolean[] { BooleanUtils.negate(branchMatches), BooleanUtils.negate(authorMatches) })) {
            return Optional.of(new Exclusion(
                    String.format("filtered by include-rules — branch match: %s (branches=%s), author match: %s (author=%s)",
                            branchMatches, analysis.getBranches(), authorMatches, analysis.getAuthorEmail()),
                    AnalysisExcludeCategory.FILTERED_BY_RULES));
        }
        if (BooleanUtils.negate(hasAnalyzableFile(changedFiles, extensions))) {
            return Optional.of(noAnalyzableDiff(extensions, changedFiles));
        }
        return Optional.empty();
    }
    /**
     * The analysable-file gate of {@link #afterDelta}, decided from git alone so the commit is never built: a commit
     * that changes no file of a registered language and no supported config file used to be built and tested in full
     * only to be excluded afterwards, and one whose build could not even start (a repository's first commit, holding
     * only a {@code .gitignore} and no {@code pom.xml}) was excluded as a build failure instead. No changed file at all
     * is what a shallow-clone boundary reports, which only the delta can tell, so that commit is left to
     * {@link #afterDelta}. Decided ahead of the include-rules, so a commit that fails both is reported as having no
     * analysable diff.
     */
    public static Optional<Exclusion> beforeBuild(Repository repo, String commitId, Collection<String> extensions) throws IOException {
        List<String> changedFiles = JGit.changedFileNames(repo, commitId);
        if (BooleanUtils.or(new boolean[] { changedFiles.isEmpty(), hasAnalyzableFile(changedFiles, extensions) })) {
            return Optional.empty();
        }
        return Optional.of(noAnalyzableDiff(extensions, changedFiles));
    }
    private static Exclusion noAnalyzableDiff(Collection<String> extensions, Collection<String> changedFiles) {
        return new Exclusion(
                String.format("no diff files match registered languages %s or supported config files (%s) — changed files: %s",
                        extensions, ConfigFiles.describeSupported(), changedFiles),
                AnalysisExcludeCategory.NO_ANALYZABLE_DIFF);
    }
    public static boolean hasAnalyzableFile(Collection<String> changedFiles, Collection<String> extensions) {
        return changedFiles.stream().anyMatch(name -> BooleanUtils.or(new boolean[] {
                FilenameUtils.isExtension(name, extensions),
                ConfigFiles.isConfigFile(name) }));
    }
    /**
     * in first-parent mode the merge node is the only mainline record of a merge-commit PR, so its parent[0] delta is
     * analyzable. In all-commits mode the side-branch commits are indexed individually and analyzing the merge would
     * double-count.
     */
    public static Optional<String> mergeSkipReason(RunArgs args, IndexedCommits indexed) throws Exception {
        return mergeExclusion(args.getGit(), args.getCommitId(), args.isFirstParentOnly(), indexed).map(MergeExclusion::getReason);
    }
    /**
     * Why a merge is excluded before analysis. It only reads: the commits of a merged line that no index run reached
     * travel with the exclusion, and the index step indexes them ({@link CommitIndexPublisher#indexUnseenLines}).
     */
    public static Optional<MergeExclusion> mergeExclusion(
            Repository repo,
            String commitId,
            boolean firstParentOnly,
            IndexedCommits indexed) throws Exception {
        if (firstParentOnly) {
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit merge = walk.parseCommit(repo.resolve(commitId));

                /**
                 * identical trees mean the merge landed nothing on the mainline (already integrated, or an
                 * ours-strategy merge) — a guaranteed zero score, so skip before the expensive pipeline
                 */
                RevCommit parent0 = walk.parseCommit(merge.getParent(0));
                if (merge.getTree().getId().equals(parent0.getTree().getId())) {
                    return Optional.of(new MergeExclusion("merge introduces no mainline changes"));
                }

                if (merge.getParentCount() > 2) {
                    return Optional.of(new MergeExclusion(String.format(Locale.ROOT, "octopus merge (%d parents)", merge.getParentCount())));
                }
                List<RevCommit> line = JGit.mergeSideLine(repo, merge);
                if (line.isEmpty()) {
                    return Optional.of(new MergeExclusion("merge introduces no side-branch commits"));
                }
                /**
                 * A multi-author side branch is not skipped: it is analysed and credited to whoever dominates it
                 * ({@link JGit#creditedAuthor}).
                 */
                return lineMergeExclusion(line, indexed);
            }
        }
        return Optional.of(new MergeExclusion("merge commit (multiple parents)"));
    }
    public static String creditedAuthorEmail(RunArgs args) throws IOException {
        ObjectId objectId = args.getGit().resolve(args.getCommitId());
        try (RevWalk walk = new RevWalk(args.getGit())) {
            return JGit.creditedAuthor(args.getGit(), walk.parseCommit(objectId)).getEmailAddress();
        }
    }
    /**
     * A merge whose merged-in line holds a commit the project indexed brings in a line codiqo already analyses commit by
     * commit, so its parent[0] delta would score that work a second time. The analysed branch takes such a merge when it
     * is moved onto another branch's history, as when a release job fast-forwards {@code dev} onto each
     * {@code dev -> main} merge after it lands: the first of those merges, measured against a {@code main} that held only
     * its initial commits, carries the whole history of {@code dev}, most of which is already scored commit by commit
     * (the commits that are not are handled below), and a review of a diff that size can fail on the provider on every
     * attempt, halting the project each time. A line with no indexed commit is a pull request's branch, analysed
     * through the merge as before.
     *
     * <p>The line is read newest first. The commits ahead of the newest indexed one are returned as unseen: an index run
     * walks only first parents, so once the analysed branch moved onto the merge, no later run reaches them, and without
     * indexing them their work would be scored nowhere. That happens when the branch moves before a run indexed its
     * newest commits, which a run queued behind a long review makes likely. Older commits of the line were walked by the
     * run that indexed the newer one, so one the index lacks was left out by its rules (window, author, branch, a
     * duplicate patch).
     *
     * <p>At most {@link RunArgs#MERGE_LINE_LOOKUP_LIMIT} commits are looked up, one request each, since a pull request's
     * line, which has no indexed commit, is read to its end. A line whose first commits are all unknown is taken for a
     * pull request's branch.
     *
     * <p>In a shallow clone the merged-in line can come out too long or too short, since a boundary hides how far the
     * first parent reaches. A pull request is then analysed commit by commit, or a merged line through the merge as
     * before; codiqo-action fails a shallow clone by default ({@code require-full-history}).
     */
    private static Optional<MergeExclusion> lineMergeExclusion(List<RevCommit> line, IndexedCommits indexed) throws Exception {
        List<RevCommit> unseen = Lists.newArrayList();
        for (RevCommit commit : Iterables.limit(line, RunArgs.MERGE_LINE_LOOKUP_LIMIT)) {
            if (indexed.contains(commit.getName())) {
                return Optional.of(new MergeExclusion(
                        String.format(Locale.ROOT, "merges a line this project analyses commit by commit (its commit %s is indexed)", JGit.shortSha(commit.getName())),
                        unseen));
            }
            unseen.add(commit);
        }
        return Optional.empty();
    }
    public static final class Exclusion extends ImmutablePair<String, AnalysisExcludeCategory> {
        public Exclusion(String reason, AnalysisExcludeCategory category) {
            super(reason, category);
        }
        public String getReason() {
            return getLeft();
        }
        public AnalysisExcludeCategory getCategory() {
            return getRight();
        }
    }
    /** a merge's exclusion reason, with the commits of its merged line that no index run reached, newest first */
    public static final class MergeExclusion extends ImmutablePair<String, List<RevCommit>> {
        public MergeExclusion(String reason) {
            this(reason, List.of());
        }
        public MergeExclusion(String reason, List<RevCommit> unseen) {
            super(reason, unseen);
        }
        public String getReason() {
            return getLeft();
        }
        public List<RevCommit> getUnseen() {
            return getRight();
        }
    }
}
