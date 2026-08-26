package io.codiqo.util;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import lombok.experimental.UtilityClass;

@UtilityClass
public class JGit {
    private static final Pattern REVERT_PATTERN = Pattern.compile("This reverts commit ([a-f0-9]{40})\\.");
    private static final Pattern AUTO_GENERATED_BRANCH_PATTERN = Pattern.compile("^[^/]+/[^/]+(?:-[^/]+){2,}-\\d{12,}$");
    private static final Pattern DETACHED_HEAD_SHA = Pattern.compile("^[a-f0-9]{40}$");
    private static final Set<String> NOISY_BRANCH_EXACT = Set.of(Constants.HEAD, "tmp", "tmp-skip");
    private static final Set<String> NOISY_BRANCH_PREFIXES = Set.of("bot/", "copilot/", "dependabot/", "renovate/");

    public static String shortSha(String commitSha) {
        return ObjectId.fromString(commitSha).abbreviate(Constants.OBJECT_ID_ABBREV_STRING_LENGTH).name();
    }
    public static Repository openRepository(File baseDirectory) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(new File(baseDirectory, ".git"))
                .readEnvironment()
                .findGitDir()
                .build();
    }
    public static String effectivePath(DiffEntry diff) {
        return effectivePath(diff.getChangeType(), diff.getOldPath(), diff.getNewPath());
    }
    public static String effectivePath(DiffEntry.ChangeType changeType, String oldPath, String newPath) {
        return switch (changeType) {
            case DELETE -> oldPath;
            case ADD, MODIFY, RENAME, COPY -> newPath;
        };
    }
    public static boolean hasContentBefore(DiffEntry.ChangeType changeType) {
        return switch (changeType) {
            case ADD -> false;
            case MODIFY, DELETE, RENAME, COPY -> true;
        };
    }
    public static boolean hasContentAfter(DiffEntry.ChangeType changeType) {
        return switch (changeType) {
            case DELETE -> false;
            case ADD, MODIFY, RENAME, COPY -> true;
        };
    }
    public static Optional<String> detectRevertedSha(String fullMessage) {
        Matcher matcher = REVERT_PATTERN.matcher(fullMessage);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }
    public static String stripRefPrefix(String refName) {
        if (refName.startsWith(Constants.R_HEADS)) {
            return refName.substring(Constants.R_HEADS.length());
        }
        if (refName.startsWith(Constants.R_REMOTES)) {
            return refName.substring(Constants.R_REMOTES.length());
        }
        return refName;
    }
    public static List<String> parentShas(RevCommit commit) {
        List<String> toReturn = new ArrayList<>(commit.getParentCount());
        for (int i = 0; i < commit.getParentCount(); i++) {
            toReturn.add(commit.getParent(i).getName());
        }
        return toReturn;
    }
    public static boolean isMerge(Repository repo, String rev) throws IOException {
        ObjectId objectId = repo.resolve(rev);
        try (RevWalk walk = new RevWalk(repo)) {
            return isMerge(walk.parseCommit(objectId));
        }
    }
    public static boolean isMerge(RevCommit commit) {
        return commit.getParentCount() > BigDecimal.ONE.intValue();
    }
    /**
     * the merged-in branch's own commits: reachable from the merge's second parent but not from the
     * first (main line) parent. for a PR merge node this is exactly the set of commits the PR brought in
     */
    public static List<RevCommit> mergeSideCommits(Repository repo, RevCommit merge) throws IOException {
        List<RevCommit> toReturn = new ArrayList<>();

        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(merge.getParent(1)));
            walk.markUninteresting(walk.parseCommit(merge.getParent(0)));
            for (RevCommit commit : walk) {
                toReturn.add(commit);
            }
        }
        return toReturn;
    }
    /**
     * derives who to credit for a merge node's parent[0] delta: the single author of every side-branch
     * commit. empty for octopus merges (ambiguous main line delta), empty side sets (nothing new merged
     * in) and mixed-author side branches (no sole owner of the net change)
     */
    public static Optional<PersonIdent> mergeSideSoleAuthor(Repository repo, RevCommit merge) throws IOException {
        if (merge.getParentCount() != 2) {
            return Optional.empty();
        }

        PersonIdent soleAuthor = null;
        for (RevCommit commit : mergeSideCommits(repo, merge)) {
            PersonIdent author = commit.getAuthorIdent();
            if (Objects.isNull(soleAuthor)) {
                soleAuthor = author;
            } else if (BooleanUtils.negate(Strings.CI.equals(soleAuthor.getEmailAddress(), author.getEmailAddress()))) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(soleAuthor);
    }
    /**
     * whether the author filter admits a commit, given the author it was already credited to. a merge node's own
     * author is an integration identity — a merge queue, a release bot, whoever clicked the button — so rejecting it
     * would drop the side branch's human work with it. such a node is admitted when ANY side-branch author is
     * admitted, which is the only case {@link #mergeSideSoleAuthor} cannot already express: it derives an author only
     * when the side branch has exactly one. a bot merging its own bot-authored branch has no admitted side author and
     * stays rejected, which is the point of excluding bots in the first place.
     *
     * <p>Restricted to two-parent merges, matching {@link #mergeSideSoleAuthor}: an octopus merge has no single side
     * branch to read, so it is judged on its own author alone.
     */
    public static boolean isAuthorAdmitted(Repository repo, RevCommit commit, PersonIdent creditedAuthor, Predicate<String> admits)
            throws IOException {
        if (admits.test(creditedAuthor.getEmailAddress())) {
            return true;
        }
        if (commit.getParentCount() != 2) {
            return false;
        }
        return mergeSideCommits(repo, commit).stream()
                .anyMatch(side -> admits.test(side.getAuthorIdent().getEmailAddress()));
    }
    /**
     * who to credit for a merge node's parent[0] delta when the side branch has more than one author: the one who
     * wrote most of it. Commits decide it, lines break a tie, and a genuine tie resolves to empty so the caller
     * keeps the merge author rather than picking arbitrarily.
     *
     * <p>Exists because the alternative was worse. A multi-author PR has no sole author, and treating that as
     * unattributable dropped the whole merge — terminally, so the work appeared nowhere, not even in org totals.
     * Crediting the dominant author is imperfect and says so; losing the commit is not recoverable.
     */
    public static Optional<PersonIdent> mergeSideDominantAuthor(Repository repo, RevCommit merge) throws IOException {
        if (merge.getParentCount() != 2) {
            return Optional.empty();
        }

        Map<String, PersonIdent> byEmail = new HashMap<>();
        Map<String, Integer> commits = new HashMap<>();
        List<RevCommit> side = mergeSideCommits(repo, merge);
        for (RevCommit commit : side) {
            PersonIdent author = commit.getAuthorIdent();
            String key = StringUtils.lowerCase(author.getEmailAddress());
            byEmail.putIfAbsent(key, author);
            commits.merge(key, BigDecimal.ONE.intValue(), Integer::sum);
        }

        Optional<String> byCommits = soleMaximum(commits);
        if (byCommits.isPresent()) {
            return Optional.of(byEmail.get(byCommits.get()));
        }

        Map<String, Integer> lines = new HashMap<>();
        for (RevCommit commit : side) {
            String key = StringUtils.lowerCase(commit.getAuthorIdent().getEmailAddress());
            lines.merge(key, changedLines(repo, commit), Integer::sum);
        }
        return soleMaximum(lines).map(byEmail::get);
    }
    /** the credited author of a merge: its sole side author when there is one, otherwise whoever dominates it. */
    public static Optional<PersonIdent> mergeSideCreditedAuthor(Repository repo, RevCommit merge) throws IOException {
        Optional<PersonIdent> sole = mergeSideSoleAuthor(repo, merge);
        return sole.isPresent() ? sole : mergeSideDominantAuthor(repo, merge);
    }
    /** the single largest value, or empty when nothing leads outright — a tie must not be resolved by map order. */
    private static Optional<String> soleMaximum(Map<String, Integer> counts) {
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max == 0) {
            return Optional.empty();
        }
        List<String> leaders = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
        return leaders.size() == 1 ? Optional.of(leaders.iterator().next()) : Optional.empty();
    }
    /** added plus deleted lines a single-parent commit contributed; a merge or a root commit counts as nothing. */
    private static int changedLines(Repository repo, RevCommit commit) throws IOException {
        if (commit.getParentCount() != 1) {
            return 0;
        }
        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repo);
            int toReturn = 0;
            for (DiffEntry entry : formatter.scan(commit.getParent(0), commit)) {
                for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
                    toReturn += edit.getEndA() - edit.getBeginA() + edit.getEndB() - edit.getBeginB();
                }
            }
            return toReturn;
        }
    }
    public static List<String> branchesContaining(Repository repo, String commitSha) throws Exception {
        Set<String> toReturn = new LinkedHashSet<>();

        try (Git git = Git.wrap(repo)) {
            for (Ref ref : git.branchList().setListMode(ListMode.ALL).setContains(commitSha).call()) {
                String branchName = logicalBranchName(ref);
                if (StringUtils.isNotBlank(branchName)) {
                    toReturn.add(branchName);
                }
            }
        }
        return new ArrayList<>(toReturn);
    }
    public static Optional<String> detectDefaultBranch(Repository repo) throws IOException {
        Ref originHead = repo.exactRef(Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + Constants.HEAD);
        if (Objects.nonNull(originHead) && originHead.isSymbolic()) {
            return Optional.of(stripRefPrefix(originHead.getTarget().getName()))
                    .map(name -> Strings.CS.removeStart(name, Constants.DEFAULT_REMOTE_NAME + "/"));
        }

        String branch = repo.getBranch();
        if (StringUtils.isBlank(branch) || DETACHED_HEAD_SHA.matcher(branch).matches()) {
            return Optional.empty();
        }
        return Optional.of(branch);
    }
    public static String currentBranchOrDefault(Repository repo) throws IOException {
        String branch = repo.getBranch();
        if (StringUtils.isBlank(branch) || DETACHED_HEAD_SHA.matcher(branch).matches()) {
            return detectDefaultBranch(repo).orElse(branch);
        }
        return branch;
    }
    public static Optional<String> detectRemoteUrl(Repository repo) {
        return detectRemoteUrls(repo).stream().findFirst();
    }
    public static Set<String> detectRemoteUrls(Repository repo) {
        StoredConfig config = repo.getConfig();
        Set<String> toReturn = new LinkedHashSet<>();
        for (String remote : config.getSubsections("remote")) {
            String url = StringUtils.trimToNull(config.getString("remote", remote, "url"));
            if (StringUtils.isNotBlank(url)) {
                toReturn.add(url);
            }
        }
        return toReturn;
    }
    public static Map<String, List<String>> buildBranchIndex(Repository repo) throws Exception {
        Map<String, List<String>> toReturn = new HashMap<>();

        try (Git git = Git.wrap(repo)) {
            for (Ref ref : git.branchList().setListMode(ListMode.ALL).call()) {
                String branchName = logicalBranchName(ref);
                if (StringUtils.isBlank(branchName)) {
                    continue;
                }
                try (RevWalk walk = new RevWalk(repo)) {
                    walk.markStart(walk.parseCommit(ref.getObjectId()));
                    for (RevCommit commit : walk) {
                        List<String> branches = toReturn.computeIfAbsent(commit.getName(), k -> new ArrayList<>());
                        if (!branches.contains(branchName)) {
                            branches.add(branchName);
                        }
                    }
                }
            }
        }
        return toReturn;
    }
    private static String logicalBranchName(Ref ref) {
        String name = ref.getName();
        if (name.startsWith(Constants.R_HEADS)) {
            return filterNoisyBranchName(name.substring(Constants.R_HEADS.length()));
        }
        if (name.startsWith(Constants.R_REMOTES)) {
            String remoteTracking = name.substring(Constants.R_REMOTES.length());
            int slash = remoteTracking.indexOf('/');
            String shortName = slash > 0 ? remoteTracking.substring(slash + 1) : remoteTracking;
            return filterNoisyBranchName(shortName);
        }
        return filterNoisyBranchName(name);
    }
    private static String filterNoisyBranchName(String shortName) {
        return isNoisyBranchName(shortName) ? null : shortName;
    }
    private static boolean isNoisyBranchName(String shortName) {
        return BooleanUtils.or(new boolean[]{
                NOISY_BRANCH_EXACT.contains(shortName),
                AUTO_GENERATED_BRANCH_PATTERN.matcher(shortName).matches(),
                NOISY_BRANCH_PREFIXES.stream().anyMatch(shortName::startsWith)});
    }
}
