package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.core.logging.SlfLogFactory;

/**
 * Uncommitted changes as the {@code analyze-uncommitted-changes} goal analyses them: a commit of the working tree
 * ({@link WorkingTreeCommit}) read by the commit path, so its diff follows the rules every commit's does.
 */
class UncommittedChangesAnalysisTest {
    private static final String DEVELOPER = "dev@example.test";

    @TempDir
    Path workTree;

    @Test
    void anUntrackedFileIsLeftOutWhenTheRunLeavesUntrackedFilesOut() throws Exception {
        try (Git git = committed(Map.of("Foo.java", "class Foo {}\n"))) {
            write("Foo.java", "class Foo { int x; }\n");
            write("Scratch.java", "class Scratch {}\n");

            Map<String, FileAnalysis> files = analysed(git, false);

            assertEquals(List.of("Foo.java"), List.copyOf(files.keySet()));
        }
    }
    /** the developer staged it, so it is part of their change even when untracked files are not */
    @Test
    void aStagedNewFileIsInTheChangeWithoutTheUntrackedFiles() throws Exception {
        try (Git git = committed(Map.of("Foo.java", "class Foo {}\n"))) {
            write("Bar.java", "class Bar {}\n");
            git.add().addFilepattern("Bar.java").call();

            assertEquals(ChangeType.ADD, analysed(git, false).get("Bar.java").getChangeType());
        }
    }
    @Test
    void aStagedRenameIsARenameNotAWholeNewFile() throws Exception {
        StringBuilder body = new StringBuilder("class Old {\n");
        for (int line = 0; line < 20; line++) {
            body.append("    int field").append(line).append(";\n");
        }
        String before = body.append("}\n").toString();
        try (Git git = committed(Map.of("Old.java", before))) {
            Files.delete(workTree.resolve("Old.java"));
            write("New.java", Strings.CS.replace(before, "int field3;", "long field3;"));
            git.add().addFilepattern(".").setUpdate(true).call();
            git.add().addFilepattern("New.java").call();

            FileAnalysis renamed = analysed(git, false).get("New.java");

            assertEquals(ChangeType.RENAME, renamed.getChangeType());
            assertFalse(Strings.CS.contains(renamed.getDiffText(), "new file mode"), renamed.getDiffText());
            assertEquals(1, StringUtils.countMatches(renamed.getDiffText(), "\n+    "), renamed.getDiffText());
        }
    }
    /** a re-indent changes nothing in a language that ignores leading whitespace, as in a commit */
    @Test
    void aReindentedLineIsNotAChange() throws Exception {
        try (Git git = committed(Map.of("Bar.java", "class Bar {\n    int x;\n    int y;\n}\n"))) {
            write("Bar.java", "class Bar {\n        int x;\n        int y;\n}\n");

            String diff = analysed(git, false).get("Bar.java").getDiffText();

            assertFalse(Strings.CS.containsAny(diff, "\n-    ", "\n+    "), diff);
        }
    }
    /**
     * The server attributes an uncommitted analysis by its author's address, and refuses one without. An identity the
     * environment exports (GIT_AUTHOR_EMAIL, as a git hook runs with) wins over the repository's, as in git itself.
     */
    @Test
    void theChangesAreTheDevelopersByTheirGitIdentity() throws Exception {
        assumeTrue(Objects.isNull(System.getenv("GIT_AUTHOR_EMAIL")), "git takes the author from the environment here");
        try (Git git = committed(Map.of("Foo.java", "class Foo {}\n"))) {
            write("Foo.java", "class Foo { int x; }\n");

            CommitAnalysis analysis = analysis(git, false);

            assertEquals(DEVELOPER, analysis.getAuthorEmail());
            assertEquals(List.of(git.getRepository().resolve("HEAD").getName()), analysis.getParentIds());
        }
    }
    /** git stages a nested clone as a pointer to its commit, which is no object of this repository and has no lines */
    @Test
    void aNestedCloneIsLeftOut() throws Exception {
        try (Git git = committed(Map.of("Foo.java", "class Foo {}\n"))) {
            write("Foo.java", "class Foo { int x; }\n");
            try (Git nested = Git.init().setInitialBranch("main").setDirectory(workTree.resolve("tool").toFile()).call()) {
                identify(nested);
                Files.writeString(workTree.resolve("tool/Tool.java"), "class Tool {}\n", StandardCharsets.UTF_8);
                nested.add().addFilepattern("Tool.java").call();
                nested.commit().setMessage("tool").setSign(false).call();
            }

            assertEquals(List.of("Foo.java"), List.copyOf(analysed(git, true).keySet()));
        }
    }
    /** git refuses to add a nested repository with no commit yet, which would fail the whole run */
    @Test
    void aNestedRepositoryWithNoCommitIsLeftOut() throws Exception {
        try (Git git = committed(Map.of("Foo.java", "class Foo {}\n"))) {
            write("Foo.java", "class Foo { int x; }\n");
            Git.init().setInitialBranch("main").setDirectory(workTree.resolve("scratch").toFile()).call().close();
            write("scratch/Draft.java", "class Draft {}\n");

            assertEquals(List.of("Foo.java"), List.copyOf(analysed(git, true).keySet()));
        }
    }
    /** with no index of the developer's, the change starts from HEAD rather than deleting every tracked file */
    @Test
    void aMissingIndexStartsFromHead() throws Exception {
        try (Git git = committed(Map.of("Foo.java", "class Foo {}\n", "Bar.java", "class Bar {}\n"))) {
            write("Foo.java", "class Foo { int x; }\n");
            Files.delete(git.getRepository().getIndexFile().toPath());

            Map<String, FileAnalysis> files = analysed(git, false);

            assertEquals(List.of("Foo.java"), List.copyOf(files.keySet()));
            assertEquals(ChangeType.MODIFY, files.get("Foo.java").getChangeType());
        }
    }
    @Test
    void aRepositoryWithNoCommitYetIsAnalysedAgainstTheEmptyTree() throws Exception {
        try (Git git = Git.init().setInitialBranch("main").setDirectory(workTree.toFile()).call()) {
            identify(git);
            write("Fresh.java", "class Fresh {}\n");

            CommitAnalysis analysis = analysis(git, true);

            assertTrue(analysis.getParentIds().isEmpty());
            assertEquals(ChangeType.ADD, files(analysis).get("Fresh.java").getChangeType());
        }
    }
    private Git committed(Map<String, String> files) throws Exception {
        Git toReturn = Git.init().setInitialBranch("main").setDirectory(workTree.toFile()).call();
        identify(toReturn);
        for (Map.Entry<String, String> file : files.entrySet()) {
            write(file.getKey(), file.getValue());
        }
        toReturn.add().addFilepattern(".").call();
        toReturn.commit().setMessage("first").setSign(false).call();
        return toReturn;
    }
    private void write(String path, String content) throws Exception {
        Path file = workTree.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
    private static void identify(Git git) throws Exception {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", "dev");
        config.setString("user", null, "email", DEVELOPER);
        config.save();
    }
    private static Map<String, FileAnalysis> analysed(Git git, boolean includeUntracked) throws Exception {
        return files(analysis(git, includeUntracked));
    }
    private static CommitAnalysis analysis(Git git, boolean includeUntracked) throws Exception {
        RunArgs args = new RunArgs();
        args.setGit(git.getRepository());
        args.setIncludeUntracked(includeUntracked);
        args.setCommitId(WorkingTreeCommit.create(git.getRepository(), includeUntracked));
        return new JGitDeltaAnalyzer(new SlfLogFactory(), args).analyze();
    }
    private static Map<String, FileAnalysis> files(CommitAnalysis analysis) {
        Map<String, FileAnalysis> toReturn = Maps.newTreeMap();
        StreamSupport.stream(analysis.spliterator(), false).forEach(file -> toReturn.put(file.getNewPath(), file));
        return toReturn;
    }
}
