package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.Repository;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;


import io.codiqo.api.RunArgs;
import io.codiqo.client.model.CommitModel;
import io.codiqo.submit.CommitIndexer;
import io.codiqo.util.JGit;

/**
 * Walks git history over a time window and writes the analyzable commit SHAs to a file — the
 * Gradle counterpart of the Maven index-commits goal, without the backend round-trip. The output
 * file feeds a per-commit dump loop (see run-spring-kafka.sh).
 */
public class CodiqoIndexCommitsTask extends DefaultTask {
    private static final String DEFAULT_REF = "HEAD";

    @TaskAction
    public void index() throws Exception {
        Project root = getProject().getRootProject();
        CodiqoExtension ext = getProject().getExtensions().getByType(CodiqoExtension.class);

        String indexRef = prop("codiqo.indexRef", DEFAULT_REF);
        String commitWindow = prop("codiqo.commitWindow", ext.getCommitWindow());
        Date cutoff = Date.from(LocalDate.now(ZoneOffset.UTC).minus(Period.parse(commitWindow)).atStartOfDay(ZoneOffset.UTC).toInstant());

        RunArgs filter = new RunArgs();
        Optional.ofNullable(prop("codiqo.includeAuthorEmails", null)).ifPresent(filter::setIncludeAuthorEmails);
        Optional.ofNullable(prop("codiqo.excludeAuthorEmails", null)).ifPresent(filter::setExcludeAuthorEmails);

        try (Repository repo = JGit.openRepository(root.getProjectDir())) {
            String branch = resolveBranch(repo);
            List<CommitModel> commits = CommitIndexer.extractCommits(repo, filter, indexRef, cutoff, branch);

            File outputFile = resolveOutputFile(root, ext);
            FileUtils.forceMkdir(outputFile.getParentFile());
            FileUtils.writeLines(outputFile, StandardCharsets.UTF_8.name(),
                    commits.stream().map(CommitModel::getSha).toList());

            long merges = commits.stream().filter(commit -> Boolean.TRUE.equals(commit.getIsMerge())).count();
            long reverts = commits.stream().filter(commit -> Boolean.TRUE.equals(commit.getIsRevert())).count();
            getLogger().lifecycle(String.format(
                    "codiqo: indexed %d commits since %s (window=%s, branch=%s; %d merges, %d reverts) -> %s",
                    commits.size(), cutoff, commitWindow, branch, merges, reverts, outputFile.getAbsolutePath()));
        }
    }
    private String resolveBranch(Repository repo) throws IOException {
        String override = prop("codiqo.branch", null);
        if (StringUtils.isNotBlank(override)) {
            return override.trim();
        }
        String current = repo.getBranch();
        if (StringUtils.isNotBlank(current) && !current.matches("^[a-f0-9]{40}$")) {
            return current;
        }
        return JGit.detectDefaultBranch(repo).orElseThrow(() -> new IllegalStateException(
                "cannot resolve branch: HEAD is detached and no default branch is available; set -Pcodiqo.branch explicitly"));
    }
    private File resolveOutputFile(Project root, CodiqoExtension ext) {
        String override = prop("codiqo.indexOutputFile", ext.getIndexOutputFile());
        return StringUtils.isNotBlank(override)
                ? new File(override)
                : new File(root.getLayout().getBuildDirectory().getAsFile().get(), "codiqo/indexed-commits.txt");
    }
    private String prop(String name, String fallback) {
        Object value = getProject().findProperty(name);
        return value != null ? value.toString() : fallback;
    }
}
