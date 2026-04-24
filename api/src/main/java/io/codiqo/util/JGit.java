package io.codiqo.util;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.experimental.UtilityClass;

@UtilityClass
public class JGit {
    private static final Pattern REVERT_PATTERN = Pattern.compile("This reverts commit ([a-f0-9]{40})\\.");

    public static Repository openRepository(File baseDirectory) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(new File(baseDirectory, ".git"))
                .readEnvironment()
                .findGitDir()
                .build();
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
        List<String> toReturn = Lists.newArrayListWithCapacity(commit.getParentCount());
        for (int i = 0; i < commit.getParentCount(); i++) {
            toReturn.add(commit.getParent(i).getName());
        }
        return toReturn;
    }
    public static List<String> branchesContaining(Repository repo, String commitSha) throws Exception {
        List<String> toReturn = Lists.newArrayList();

        try (Git git = Git.wrap(repo)) {
            for (Ref ref : git.branchList().setContains(commitSha).call()) {
                toReturn.add(stripRefPrefix(ref.getName()));
            }
        }
        return toReturn;
    }
    public static Optional<String> detectDefaultBranch(Repository repo) throws IOException {
        Ref originHead = repo.exactRef(Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + Constants.HEAD);
        if (Objects.nonNull(originHead) && originHead.isSymbolic()) {
            return Optional.of(stripRefPrefix(originHead.getTarget().getName()))
                    .map(name -> Strings.CS.removeStart(name, Constants.DEFAULT_REMOTE_NAME + "/"));
        }

        String branch = repo.getBranch();
        if (StringUtils.isBlank(branch) || branch.matches("^[a-f0-9]{40}$")) {
            return Optional.empty();
        }
        return Optional.of(branch);
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
        Map<String, List<String>> toReturn = Maps.newHashMap();

        try (Git git = Git.wrap(repo)) {
            for (Ref ref : git.branchList().setListMode(ListMode.ALL).call()) {
                String branchName = stripRefPrefix(ref.getName());
                try (RevWalk walk = new RevWalk(repo)) {
                    walk.markStart(walk.parseCommit(ref.getObjectId()));
                    for (RevCommit commit : walk) {
                        toReturn.computeIfAbsent(commit.getName(), k -> Lists.newArrayList()).add(branchName);
                    }
                }
            }
        }
        return toReturn;
    }
}
