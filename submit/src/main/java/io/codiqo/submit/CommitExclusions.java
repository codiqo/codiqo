package io.codiqo.submit;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.util.JGit;
import lombok.Value;
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
     * gates that need only the commit and the run configuration, so they run before the clone/build/analysis pipeline.
     */
    public static Optional<Exclusion> beforeAnalysis(RunArgs args) throws IOException {
        if (JGit.isMerge(args.getGit(), args.getCommitId())) {
            Optional<Exclusion> merge = mergeSkipReason(args).map(reason -> new Exclusion(reason, AnalysisExcludeCategory.MERGE_COMMIT));
            if (merge.isPresent()) {
                return merge;
            }
        }
        if (BooleanUtils.negate(isAuthorAdmitted(args))) {
            return Optional.of(new Exclusion("author excluded by codiqo.excludeAuthorEmails", AnalysisExcludeCategory.FILTERED_BY_RULES));
        }
        return Optional.empty();
    }
    /**
     * gates that need the computed delta: whether the delta could be computed at all, whether anything in the diff is
     * analysable, and whether the commit survives the include-rules. The caller still submits diff-only file models
     * for an excluded commit, so the backend records what changed even though nothing was scored.
     */
    public static Optional<Exclusion> afterDelta(RunArgs args, CommitAnalysis analysis, Collection<String> extensions, Collection<String> changedFiles) {
        // first: the delta could not be computed at all, so a later gate would blame the language registry for the empty file list
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
            return Optional.of(new Exclusion(
                    String.format("no diff files match registered languages %s or supported config files — changed files: %s",
                            extensions, changedFiles),
                    AnalysisExcludeCategory.NO_ANALYZABLE_DIFF));
        }
        return Optional.empty();
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
    public static Optional<String> mergeSkipReason(RunArgs args) throws IOException {
        if (args.isFirstParentOnly()) {
            ObjectId objectId = args.getGit().resolve(args.getCommitId());
            try (RevWalk walk = new RevWalk(args.getGit())) {
                RevCommit merge = walk.parseCommit(objectId);

                /**
                 * identical trees mean the merge landed nothing on the mainline (already integrated, or an
                 * ours-strategy merge) — a guaranteed zero score, so skip before the expensive pipeline
                 */
                RevCommit parent0 = walk.parseCommit(merge.getParent(0));
                if (merge.getTree().getId().equals(parent0.getTree().getId())) {
                    return Optional.of("merge introduces no mainline changes");
                }

                if (merge.getParentCount() > 2) {
                    return Optional.of(String.format("octopus merge (%d parents)", merge.getParentCount()));
                }
                if (JGit.mergeSideCommits(args.getGit(), merge).isEmpty()) {
                    return Optional.of("merge introduces no side-branch commits");
                }
                /**
                 * a multi-author side branch is analysed and credited to whoever dominates it. Excluding it
                 * terminally, as this once did, lost the work outright — it appeared nowhere, not even in org totals.
                 */
                return Optional.empty();
            }
        }
        return Optional.of("merge commit (multiple parents)");
    }
    public static boolean isAuthorAdmitted(RunArgs args) throws IOException {
        ObjectId objectId = args.getGit().resolve(args.getCommitId());
        try (RevWalk walk = new RevWalk(args.getGit())) {
            RevCommit commit = walk.parseCommit(objectId);
            PersonIdent credited = commit.getAuthorIdent();
            if (JGit.isMerge(commit)) {
                credited = JGit.mergeSideCreditedAuthor(args.getGit(), commit).orElse(credited);
            }
            return JGit.isAuthorAdmitted(args.getGit(), commit, credited,
                    email -> BooleanUtils.negate(args.isExcludedAuthor(email)));
        }
    }
    public static String creditedAuthorEmail(RunArgs args) throws IOException {
        ObjectId objectId = args.getGit().resolve(args.getCommitId());
        try (RevWalk walk = new RevWalk(args.getGit())) {
            RevCommit commit = walk.parseCommit(objectId);
            if (JGit.isMerge(commit)) {
                return JGit.mergeSideCreditedAuthor(args.getGit(), commit)
                        .map(PersonIdent::getEmailAddress)
                        .orElseGet(() -> commit.getAuthorIdent().getEmailAddress());
            }
            return commit.getAuthorIdent().getEmailAddress();
        }
    }
    @Value
    public static class Exclusion {
        String reason;
        AnalysisExcludeCategory category;
    }
}
