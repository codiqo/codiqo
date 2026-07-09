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
 * by patch-id.
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
            walk.markStart(walk.parseCommit(startId));

            for (RevCommit commit : walk) {
                List<String> branches = branchIndex.getOrDefault(commit.getName(), Collections.emptyList());
                if (BooleanUtils.or(new boolean[] {
                        BooleanUtils.negate(branches.contains(branch)),
                        BooleanUtils.negate(filterArgs.isAuthorAllowed(commit.getAuthorIdent().getEmailAddress()))
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
                toReturn.add(toCommitModel(commit, branches));
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
    private static CommitModel toCommitModel(RevCommit commit, List<String> branches) {
        CommitModel toReturn = new CommitModel();

        toReturn.setSha(commit.getName());
        toReturn.setMessage(commit.getFullMessage());
        toReturn.setAuthor(commit.getAuthorIdent().getName());
        toReturn.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
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
