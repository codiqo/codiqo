package io.codiqo.submit.hotspots;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.MutableTriple;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import com.google.common.collect.Maps;

import lombok.experimental.UtilityClass;

/**
 * Walked newest first, so a rename is followed backwards: older commits that touched {@code A} count towards the {@code B}
 * it became. Merges are skipped; their changes are already counted on the side that made them.
 */
@UtilityClass
public class GitChurn {
    public static final Duration RECENT = Duration.ofDays(365);

    /** the start of the recent window that ends at {@code commitId}: fixes count only from here on */
    public Instant recentFrom(Repository repo, String commitId) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            return walk.parseCommit(ObjectId.fromString(commitId)).getCommitterIdent().getWhenAsInstant().minus(RECENT);
        }
    }
    public Map<String, FileChurn> collect(Repository repo, String commitId, FixCommits fixCommits) throws IOException {
        Map<String, MutableChurn> counts = Maps.newHashMap();
        Map<String, String> renamedTo = Maps.newHashMap();

        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit tip = walk.parseCommit(ObjectId.fromString(commitId));
            Instant recentFrom = tip.getCommitterIdent().getWhenAsInstant().minus(RECENT);
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            walk.markStart(tip);

            try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(repo);
                formatter.setDetectRenames(true);

                for (RevCommit commit : walk) {
                    if (commit.getParentCount() <= 1) {
                        boolean recent = commit.getCommitterIdent().getWhenAsInstant().compareTo(recentFrom) >= 0;
                        boolean fix = recent && fixCommits.isFix(commit);

                        /**
                         * A root commit has no parent; passing null makes JGit diff it against the empty tree, so its
                         * files are still counted.
                         */
                        RevCommit parent = null;
                        if (commit.getParentCount() == 1) {
                            parent = commit.getParent(0);
                        }
                        for (DiffEntry entry : formatter.scan(parent, commit)) {
                            String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
                            String current = renamedTo.getOrDefault(path, path);
                            counts.computeIfAbsent(current, k -> new MutableChurn()).add(recent, fix);

                            if (entry.getChangeType() == DiffEntry.ChangeType.RENAME) {
                                renamedTo.put(entry.getOldPath(), current);
                            }
                        }
                    }
                }
            }
        }

        Map<String, FileChurn> toReturn = Maps.newHashMap();
        counts.forEach((path, churn) -> toReturn.put(path, churn.freeze()));
        return toReturn;
    }

    public static class FileChurn extends ImmutableTriple<Integer, Integer, Integer> {
        public static final FileChurn NONE = new FileChurn(0, 0, 0);

        public FileChurn(int commits, int recentCommits, int recentFixCommits) {
            super(commits, recentCommits, recentFixCommits);
        }
        public int getCommits() {
            return getLeft();
        }
        public int getRecentCommits() {
            return getMiddle();
        }
        public int getRecentFixCommits() {
            return getRight();
        }
    }

    private static final class MutableChurn extends MutableTriple<Integer, Integer, Integer> {
        private MutableChurn() {
            super(0, 0, 0);
        }
        private void add(boolean recent, boolean fix) {
            setLeft(getLeft() + 1);
            if (recent) {
                setMiddle(getMiddle() + 1);
            }
            if (fix) {
                setRight(getRight() + 1);
            }
        }
        private FileChurn freeze() {
            return new FileChurn(getLeft(), getMiddle(), getRight());
        }
    }
}
