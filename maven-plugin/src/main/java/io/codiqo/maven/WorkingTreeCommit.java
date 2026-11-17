package io.codiqo.maven;

import static java.util.function.Predicate.not;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.experimental.UtilityClass;

/**
 * A commit object of the working tree as it stands, so uncommitted changes are analysed and reviewed the way a commit
 * is: the analysis diffs it against HEAD as it diffs any commit (renames, re-indents, line filters), and the review
 * reads it with {@code git show <sha>}. Made through a throwaway copy of the developer's index, so a file they staged
 * and never committed is in it, and their index, files, branches and stash stay as they were; the object is
 * referenced by nothing, so git collects it in time. Untracked files are included when the analysis reads them, and
 * {@code .gitignore} is respected either way. It is authored by the developer's git identity, which the server
 * attributes an uncommitted analysis by, and a repository with no commit yet gets a root commit.
 */
@UtilityClass
public class WorkingTreeCommit {
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(1);
    private static final String MESSAGE = "codiqo: uncommitted changes";
    /** how {@code git ls-files --others} lists an untracked directory it did not enter: a nested repository */
    private static final String DIRECTORY_SUFFIX = "/";
    private static final int GIT_SUCCESS = 0;
    /**
     * The identity the object is made with when git has none of its own, the author and the committer apart: the object
     * needs both. The server cannot attribute a submission without the developer's own, so the goal refuses to submit
     * one ({@link #hasIdentity}), while a run that submits nothing is unaffected.
     */
    private static final Map<String, String> NO_AUTHOR = Map.of("GIT_AUTHOR_NAME", "codiqo", "GIT_AUTHOR_EMAIL", "codiqo@localhost");
    private static final Map<String, String> NO_COMMITTER = Map.of("GIT_COMMITTER_NAME", "codiqo", "GIT_COMMITTER_EMAIL", "codiqo@localhost");

    public String create(Repository repository, boolean includeUntracked) throws IOException, InterruptedException {
        Path workTree = repository.getWorkTree().toPath();
        Path scratch = Files.createTempDirectory("codiqo-working-tree");
        try {
            Path index = scratch.resolve("index");
            Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
            Optional<ObjectId> head = Optional.ofNullable(repository.resolve(Constants.HEAD));
            /**
             * Without an index of the developer's (a checkout made with --no-checkout, an index removed to recover from
             * corruption), the tree starts from HEAD: an empty index would make every tracked file a deletion.
             */
            if (repository.getIndexFile().isFile()) {
                Files.copy(repository.getIndexFile().toPath(), index);
            } else if (head.isPresent()) {
                git(workTree, environment, "read-tree", head.get().getName());
            }
            git(workTree, environment, "add", "--update");
            if (includeUntracked) {
                addUntracked(workTree, environment, scratch.resolve("untracked"));
            }
            String tree = StringUtils.trim(git(workTree, environment, "write-tree"));

            List<String> commitTree = Lists.newArrayList("commit-tree", tree, "-m", MESSAGE);
            head.ifPresent(parent -> commitTree.addAll(List.of("-p", parent.getName())));

            /** every source git takes an identity from (its configuration, EMAIL, GIT_AUTHOR_*), as a commit of theirs would */
            Map<String, String> committing = Maps.newHashMap(environment);
            if (query(workTree, "var", "GIT_AUTHOR_IDENT").isEmpty()) {
                committing.putAll(NO_AUTHOR);
            }
            if (query(workTree, "var", "GIT_COMMITTER_IDENT").isEmpty()) {
                committing.putAll(NO_COMMITTER);
            }

            return StringUtils.trim(git(workTree, committing, commitTree.toArray(String[]::new)));
        } finally {
            FileUtils.deleteQuietly(scratch.toFile());
        }
    }
    /** whether git has an author identity of the developer's, which the server can attribute their changes by */
    public boolean hasIdentity(Repository repository) throws IOException, InterruptedException {
        return query(repository.getWorkTree().toPath(), "var", "GIT_AUTHOR_IDENT").isPresent();
    }
    /**
     * The untracked files {@code .gitignore} leaves in, without a nested repository: git would add one as a pointer to
     * its commit, which has no lines to analyse, and refuses to add one with no commit yet, which would fail the run.
     */
    private void addUntracked(Path workTree, Map<String, String> environment, Path pathspecs) throws IOException, InterruptedException {
        List<String> files = Splitter.on('\0').omitEmptyStrings().splitToStream(git(workTree, environment, "ls-files", "-z", "--others", "--exclude-standard"))
                .filter(not(WorkingTreeCommit::isNestedRepository))
                .toList();
        if (CollectionUtils.isNotEmpty(files)) {
            Files.writeString(pathspecs, Joiner.on('\0').join(files), StandardCharsets.UTF_8);
            git(workTree, environment, "add", "--pathspec-from-file=" + pathspecs, "--pathspec-file-nul");
        }
    }
    /** the command's output as git wrote it: a file list keeps the spaces its names may begin or end with */
    private String git(Path workTree, Map<String, String> environment, String... arguments) throws IOException, InterruptedException {
        GitAnswer answer = run(workTree, environment, arguments);
        if (answer.isSuccess()) {
            return answer.getText();
        }
        throw new IOException(String.format("git %s failed (exit %d): %s", Joiner.on(StringUtils.SPACE).join(arguments), answer.getExitCode(), StringUtils.trim(answer.getText())));
    }
    /** the command's output, empty when git answers with an error: no identity configured */
    private Optional<String> query(Path workTree, String... arguments) throws IOException, InterruptedException {
        return Optional.of(run(workTree, Map.of(), arguments)).filter(GitAnswer::isSuccess).map(GitAnswer::getText).map(StringUtils::trim).filter(StringUtils::isNotEmpty);
    }
    private GitAnswer run(Path workTree, Map<String, String> environment, String... arguments) throws IOException, InterruptedException {
        List<String> command = Lists.newArrayList("git");
        command.addAll(List.of(arguments));
        File output = Files.createTempFile("codiqo-git", ".out").toFile();
        File errors = Files.createTempFile("codiqo-git", ".err").toFile();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workTree.toFile()).redirectOutput(output).redirectError(errors);
            builder.environment().putAll(environment);
            Process process = builder.start();
            if (process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                File text = process.exitValue() == GIT_SUCCESS ? output : errors;
                return new GitAnswer(process.exitValue(), FileUtils.readFileToString(text, StandardCharsets.UTF_8));
            }
            process.destroyForcibly();
            throw new IOException(String.format("%s did not finish within %s", Joiner.on(StringUtils.SPACE).join(command), GIT_TIMEOUT));
        } finally {
            FileUtils.deleteQuietly(output);
            FileUtils.deleteQuietly(errors);
        }
    }
    private static boolean isNestedRepository(String path) {
        return Strings.CS.endsWith(path, DIRECTORY_SUFFIX);
    }
    /** git's exit code, with what it wrote: its output when it succeeded, its error text when it failed */
    private static final class GitAnswer extends ImmutablePair<Integer, String> {
        private GitAnswer(int exitCode, String text) {
            super(exitCode, text);
        }
        public int getExitCode() {
            return getLeft();
        }
        public String getText() {
            return getRight();
        }
        public boolean isSuccess() {
            return getExitCode() == GIT_SUCCESS;
        }
    }
}
