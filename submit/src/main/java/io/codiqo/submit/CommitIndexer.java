package io.codiqo.submit;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

import org.apache.commons.lang3.BooleanUtils;
import org.eclipse.jgit.diff.PatchIdDiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;


import io.codiqo.api.RunArgs;
import io.codiqo.client.model.CommitModel;
import io.codiqo.util.JGit;
import lombok.experimental.UtilityClass;

/**
 * Build-tool-neutral git history indexing shared by the Maven and Gradle plugins: walks history
 * from a ref over a time window, applies branch/author filters, and drops squash/rebase duplicates
 * by patch-id. When {@code firstParentOnly} is set, the walk follows only first parents, so
 * merged-in feature-branch commits are never indexed — only the mainline integration history.
 */
@UtilityClass
public class CommitIndexer {
    public List<CommitModel> extractCommits(Repository repo, RunArgs filterArgs, String indexRef, Date cutoff, String branch) throws Exception {
        List<CommitModel> toReturn = new ArrayList<>();

        ObjectId startId = repo.resolve(indexRef);
        if (Objects.isNull(startId)) {
            throw new IllegalArgumentException("cannot resolve indexRef: " + indexRef);
        }

        Map<String, List<String>> branchIndex = JGit.buildBranchIndex(repo);
        Set<ObjectId> seenPatchIds = new HashSet<>();

        try (RevWalk walk = new RevWalk(repo)) {
            walk.sort(RevSort.TOPO);
            walk.setRevFilter(CommitTimeRevFilter.after(cutoff.toInstant()));
            walk.setFirstParent(filterArgs.isFirstParentOnly());
            walk.markStart(walk.parseCommit(startId));

            for (RevCommit commit : walk) {
                List<String> branches = branchIndex.getOrDefault(commit.getName(), Collections.emptyList());

                /**
                 * merge nodes are credited to the side-branch sole author (the developer whose PR
                 * landed), so the author filter must not drop a PR just because a bot or a teammate
                 * clicked merge
                 */
                PersonIdent author = commit.getAuthorIdent();
                if (JGit.isMerge(commit)) {
                    author = JGit.mergeSideSoleAuthor(repo, commit).orElse(author);
                }

                /**
                 * includeBranches is applied here, alongside the author filters, rather than only at analysis time:
                 * a commit rejected by it is worth no build at all, and reaching that verdict after the fork build
                 * and the language-server import costs the same as analysing a commit that counts.
                 */
                if (BooleanUtils.or(new boolean[] {
                        BooleanUtils.negate(branches.contains(branch)),
                        BooleanUtils.negate(filterArgs.matchesByBranch(branches)),
                        BooleanUtils.negate(filterArgs.isAuthorAllowed(author.getEmailAddress()))
                })) {
                    continue;
                }

                /**
                 * drop duplicate squash-merges: one change re-applied into the branch under two
                 * SHAs (rebase / re-merge) shares a patch-id keep the first by order. only
                 * single-parent commits have a stable parent difference to hash; keep merge commits.
                 */
                if (commit.getParentCount() == 1 && BooleanUtils.isFalse(seenPatchIds.add(patchId(repo, commit)))) {
                    continue;
                }
                toReturn.add(toCommitModel(commit, branches, author));
            }
        }
        return toReturn;
    }
    private static ObjectId patchId(Repository repo, RevCommit commit) throws IOException {
        try (RevWalk walk = new RevWalk(repo); PatchIdDiffFormatter formatter = new PatchIdDiffFormatter()) {
            RevCommit parsedCommit = walk.parseCommit(commit);
            RevCommit parent = walk.parseCommit(parsedCommit.getParent(0));

            formatter.setRepository(repo);
            formatter.format(parent.getTree(), parsedCommit.getTree());
            formatter.flush();
            return formatter.getCalulatedPatchId();
        }
    }
    private static CommitModel toCommitModel(RevCommit commit, List<String> branches, PersonIdent author) {
        CommitModel toReturn = new CommitModel();

        toReturn.setSha(commit.getName());
        toReturn.setMessage(commit.getFullMessage());
        toReturn.setAuthor(author.getName());
        toReturn.setAuthorEmail(author.getEmailAddress());
        toReturn.setTimestamp(commit.getAuthorIdent().getWhenAsInstant().atOffset(ZoneOffset.UTC));

        toReturn.setParents(JGit.parentShas(commit));
        toReturn.setBranches(branches);
        toReturn.setIsMerge(JGit.isMerge(commit));

        JGit.detectRevertedSha(commit.getFullMessage()).ifPresent(sha -> {
            toReturn.setIsRevert(true);
            toReturn.setRevertedCommitId(sha);
        });
        return toReturn;
    }
}
