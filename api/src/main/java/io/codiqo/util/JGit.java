package io.codiqo.util;

import static java.util.function.Predicate.not;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectReader;
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

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import lombok.experimental.UtilityClass;

@UtilityClass
public class JGit {
    private static final Pattern REVERT_PATTERN = Pattern.compile("This reverts commit ([a-f0-9]{40})\\.");
    /** git writes the full hash, but a hand-edited revert message often carries an abbreviated one */
    private static final Pattern ABBREVIATED_REVERT_PATTERN = Pattern.compile("This reverts commit ([a-f0-9]{7,39})\\.");
    private static final Pattern AUTO_GENERATED_BRANCH_PATTERN = Pattern.compile("^[^/]+/[^/]+(?:-[^/]+){2,}-\\d{12,}$");
    private static final Pattern DETACHED_HEAD_SHA = Pattern.compile("^[a-f0-9]{40}$");
    private static final Set<String> NOISY_BRANCH_EXACT = Set.of(Constants.HEAD, "tmp", "tmp-skip");
    private static final Set<String> NOISY_BRANCH_PREFIXES = Set.of("bot/", "copilot/", "dependabot/", "renovate/");

    /** an octopus merge has no single side branch to read, so merge-side attribution only applies to two parents */
    private static final int MERGE_PARENT_COUNT = 2;

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
    /** the full SHA behind HEAD, a branch, a tag or an abbreviated SHA, so commit IDs compare as plain strings */
    public static String resolveCommit(Repository repo, String revision) throws IOException {
        ObjectId toReturn = repo.resolve(revision + "^{commit}");
        if (Objects.nonNull(toReturn)) {
            return toReturn.name();
        }
        throw new IllegalArgumentException("failed to resolve commit: " + revision);
    }
    /**
     * Copies the source repository's {@code shallow} file into a clone made by fetching from it. A fetch does not carry
     * the shallow boundary over, so without this file a history walk in the clone of a shallow checkout (a CI clone,
     * for one) runs past the boundary into parents whose objects were never fetched.
     */
    public static void copyShallowBoundary(Repository source, Repository clone) throws IOException {
        Set<ObjectId> shallow;
        try (ObjectReader reader = source.newObjectReader()) {
            shallow = reader.getShallowCommits();
        }
        if (CollectionUtils.isNotEmpty(shallow)) {
            FileUtils.writeLines(new File(clone.getDirectory(), Constants.SHALLOW), StandardCharsets.UTF_8.name(), shallow.stream().map(ObjectId::name).toList(), StringUtils.LF);
        }
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
    /**
     * {@link #detectRevertedSha(String)}, also accepting an abbreviated hash, which is expanded against the
     * repository. an abbreviation counts only when it names exactly one commit there: a squash-merge message can
     * quote "This reverts commit …" for a commit of a branch that no longer exists, and that commit is not a revert.
     */
    public static Optional<String> detectRevertedSha(Repository repo, String fullMessage) throws IOException {
        Optional<String> full = detectRevertedSha(fullMessage);
        if (full.isPresent()) {
            return full;
        }
        Matcher matcher = ABBREVIATED_REVERT_PATTERN.matcher(fullMessage);
        if (matcher.find()) {
            try (ObjectReader reader = repo.newObjectReader()) {
                Collection<ObjectId> candidates = reader.resolve(AbbreviatedObjectId.fromString(matcher.group(1)));
                if (candidates.size() == 1) {
                    ObjectId id = candidates.iterator().next();
                    if (reader.has(id, Constants.OBJ_COMMIT)) {
                        return Optional.of(id.getName());
                    }
                }
            }
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
        List<String> toReturn = Lists.newArrayListWithCapacity(commit.getParentCount());
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
        return commit.getParentCount() > 1;
    }
    /**
     * the merged-in branch's own commits: reachable from the merge's second parent but not from the
     * first (main line) parent. for a PR merge node this is exactly the set of commits the PR brought in
     */
    public static List<RevCommit> mergeSideCommits(Repository repo, RevCommit merge) throws IOException {
        List<RevCommit> toReturn = Lists.newArrayList();

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
        if (merge.getParentCount() != MERGE_PARENT_COUNT) {
            return Optional.empty();
        }
        return soleAuthorOf(mergeSideCommits(repo, merge));
    }
    private static Optional<PersonIdent> soleAuthorOf(List<RevCommit> side) {
        PersonIdent soleAuthor = null;
        for (RevCommit commit : side) {
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
     * who a commit is credited to. A plain commit, and an octopus merge (no single side branch to read), keep their own
     * author. A two-parent merge node's parent[0] delta is the side branch's net change, and its own author is an
     * integration identity — a merge queue, a release bot, whoever clicked the button — so it is credited to whoever
     * wrote most of the side branch instead.
     *
     * <p>The index, the author filters and the delta analyzer all read the credit from here, and the filters judge
     * exactly this identity and nothing else. When admission instead fell back to "any side author is admitted", a
     * bot-credited merge with one human commit on it was admitted and then scored under the bot, and the index (which
     * applies includeAuthorEmails) and the analysis (which re-checks the credited author) disagreed on the same commit.
     *
     * <p>Only the side branch's own non-merge commits count as authorship. A back-merge of the mainline into the branch
     * changes no line of its own, and when a bot performs it ("update branch"), counting it as a commit made the bot the
     * dominant author of a human pull request. Lines changed decide the credit, so a few one-line autofix commits do not
     * outweigh the change they tidy; commit counts break a tie in lines; and a genuine tie goes to the author of the
     * branch's earliest commit — whoever opened the pull request. The credit therefore never depends on the author
     * filter, which is what keeps every reader of it in agreement. A side branch with no commits of its own (everything
     * already merged) keeps the merge author.
     */
    public static PersonIdent creditedAuthor(Repository repo, RevCommit commit) throws IOException {
        if (commit.getParentCount() != MERGE_PARENT_COUNT) {
            return commit.getAuthorIdent();
        }

        /** the side walk runs newest first, so reversing it puts the branch's earliest commit first */
        List<RevCommit> authored = Lists.reverse(mergeSideCommits(repo, commit).stream().filter(not(JGit::isMerge)).toList());
        if (authored.isEmpty()) {
            return commit.getAuthorIdent();
        }

        Map<String, PersonIdent> byEmail = Maps.newLinkedHashMap();
        Map<String, Integer> lines = Maps.newHashMap();
        Map<String, Integer> commits = Maps.newHashMap();
        for (RevCommit sideCommit : authored) {
            PersonIdent author = sideCommit.getAuthorIdent();
            String key = StringUtils.lowerCase(author.getEmailAddress());
            byEmail.putIfAbsent(key, author);
            lines.merge(key, changedLines(repo, sideCommit), Integer::sum);
            commits.merge(key, 1, Integer::sum);
        }

        Set<String> leaders = leadersOf(commits, leadersOf(lines, byEmail.keySet()));
        return byEmail.entrySet().stream()
                .filter(entry -> leaders.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .iterator().next();
    }
    /** the candidates that share the largest value, so a tie is left for the next rule rather than resolved by map order */
    private static Set<String> leadersOf(Map<String, Integer> counts, Set<String> candidates) {
        int max = candidates.stream().mapToInt(counts::get).max().orElseThrow();
        return candidates.stream().filter(candidate -> counts.get(candidate) == max).collect(Collectors.toSet());
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
        Set<String> toReturn = Sets.newLinkedHashSet();

        try (Git git = Git.wrap(repo)) {
            for (Ref ref : git.branchList().setListMode(ListMode.ALL).setContains(commitSha).call()) {
                String branchName = logicalBranchName(ref);
                if (StringUtils.isNotBlank(branchName)) {
                    toReturn.add(branchName);
                }
            }
        }
        return Lists.newArrayList(toReturn);
    }
    public static Optional<String> detectDefaultBranch(Repository repo) throws IOException {
        Ref originHead = repo.exactRef(Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + Constants.HEAD);
        if (Objects.nonNull(originHead) && originHead.isSymbolic()) {
            return Optional.of(stripRefPrefix(originHead.getTarget().getName()))
                    .map(name -> Strings.CS.removeStart(name, Constants.DEFAULT_REMOTE_NAME + "/"));
        }

        String branch = repo.getBranch();
        if (isDetachedHead(branch)) {
            return Optional.empty();
        }
        return Optional.of(branch);
    }
    public static String currentBranchOrDefault(Repository repo) throws IOException {
        String branch = repo.getBranch();
        if (isDetachedHead(branch)) {
            return detectDefaultBranch(repo).orElse(branch);
        }
        return branch;
    }
    /**
     * whether {@code Repository.getBranch()} answered with a raw SHA rather than a branch name, which is what a
     * detached HEAD (a CI checkout of one commit, for one) reports.
     */
    public static boolean isDetachedHead(String branch) {
        return StringUtils.isBlank(branch) || DETACHED_HEAD_SHA.matcher(branch).matches();
    }
    public static Optional<String> detectRemoteUrl(Repository repo) {
        return detectRemoteUrls(repo).stream().findFirst();
    }
    public static Set<String> detectRemoteUrls(Repository repo) {
        StoredConfig config = repo.getConfig();
        Set<String> toReturn = Sets.newLinkedHashSet();
        for (String remote : config.getSubsections("remote")) {
            String url = StringUtils.trimToNull(config.getString("remote", remote, "url"));
            if (StringUtils.isNotBlank(url)) {
                toReturn.add(url);
            }
        }
        return toReturn;
    }
    public static Map<String, List<String>> buildBranchIndex(Repository repo) throws Exception {
        Map<String, List<String>> toReturn = Maps.newHashMap();

        try (Git git = Git.wrap(repo)) {
            for (Ref ref : git.branchList().setListMode(ListMode.ALL).call()) {
                String branchName = logicalBranchName(ref);
                if (StringUtils.isBlank(branchName)) {
                    continue;
                }
                try (RevWalk walk = new RevWalk(repo)) {
                    walk.markStart(walk.parseCommit(ref.getObjectId()));
                    for (RevCommit commit : walk) {
                        List<String> branches = toReturn.computeIfAbsent(commit.getName(), k -> Lists.newArrayList());
                        if (BooleanUtils.negate(branches.contains(branchName))) {
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
