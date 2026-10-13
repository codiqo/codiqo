package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.experimental.UtilityClass;

/**
 * A commit object of the working tree as it stands, so the local review can read uncommitted changes the way it reads a
 * commit ({@code git show <sha>}). Made through a throwaway index: the developer's index, files, branches and stash stay
 * as they were, and the object is referenced by nothing, so git collects it in time. Untracked files are included when
 * the analysis reads them, and {@code .gitignore} is respected either way.
 */
@UtilityClass
public class WorkingTreeCommit {
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(1);
    /** the object needs an author; who wrote the changes is the analysis's business, not this commit's */
    private static final Map<String, String> IDENTITY = Map.of(
            "GIT_AUTHOR_NAME", "codiqo",
            "GIT_AUTHOR_EMAIL", "codiqo@localhost",
            "GIT_COMMITTER_NAME", "codiqo",
            "GIT_COMMITTER_EMAIL", "codiqo@localhost");

    public String create(Path workTree, boolean includeUntracked) throws IOException, InterruptedException {
        Path scratch = Files.createTempDirectory("codiqo-working-tree");
        try {
            Map<String, String> environment = Map.of("GIT_INDEX_FILE", scratch.resolve("index").toString());
            git(workTree, environment, "read-tree", "HEAD");
            git(workTree, environment, "add", includeUntracked ? "--all" : "--update");
            String tree = git(workTree, environment, "write-tree");

            Map<String, String> committing = Maps.newHashMap(environment);
            committing.putAll(IDENTITY);
            return git(workTree, committing, "commit-tree", tree, "-p", "HEAD", "-m", "codiqo: uncommitted changes");
        } finally {
            FileUtils.deleteQuietly(scratch.toFile());
        }
    }
    private String git(Path workTree, Map<String, String> environment, String... arguments) throws IOException, InterruptedException {
        List<String> command = Lists.newArrayList("git");
        command.addAll(List.of(arguments));
        File output = Files.createTempFile("codiqo-git", ".out").toFile();
        File errors = Files.createTempFile("codiqo-git", ".err").toFile();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workTree.toFile()).redirectOutput(output).redirectError(errors);
            builder.environment().putAll(environment);
            Process process = builder.start();
            if (process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                if (process.exitValue() == 0) {
                    return StringUtils.trim(FileUtils.readFileToString(output, StandardCharsets.UTF_8));
                }
                throw new IOException(String.format("%s failed (exit %d): %s",
                        Joiner.on(StringUtils.SPACE).join(command), process.exitValue(), StringUtils.trim(FileUtils.readFileToString(errors, StandardCharsets.UTF_8))));
            }
            process.destroyForcibly();
            throw new IOException(String.format("%s did not finish within %s", Joiner.on(StringUtils.SPACE).join(command), GIT_TIMEOUT));
        } finally {
            FileUtils.deleteQuietly(output);
            FileUtils.deleteQuietly(errors);
        }
    }
}
