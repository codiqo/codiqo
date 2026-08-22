package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.core.diff.GitCommitAnalysis;
import io.codiqo.core.diff.GitFileAnalysis;
import io.codiqo.core.logging.SlfLogFactory;

/**
 * parent count is the most load-bearing input to analyzeCommit: it decides whether the delta is
 * taken against a parent tree, against the empty tree, or not taken at all. it cannot tell every
 * shape apart on its own — a shallow graft and a true root commit both report zero parents — so
 * every shape that has produced a scoring bug is pinned here: root commits, unrelated-history
 * roots, PR merges, octopus merges and grafts
 */
class JGitDeltaAnalyzerParentShapeTest {
    private static final PersonIdent IMPORTED_IDENTITY = new PersonIdent("Importer", "importer@example.com");

    @TempDir
    Path tempDir;

    private Repository repository;
    private Git git;
    private RunArgs args;
    private JGitDeltaAnalyzer analyzer;
    private String mainline;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();
        repository.getConfig().setString("user", null, "name", "Test Author");
        repository.getConfig().setString("user", null, "email", "test@example.com");
        repository.getConfig().save();

        args = new RunArgs();
        args.setGit(repository);

        analyzer = new JGitDeltaAnalyzer(new SlfLogFactory(), args);
        mainline = repository.getBranch();
    }
    @AfterEach
    void closeRepo() {
        if (Objects.nonNull(git)) {
            git.close();
        }
        if (Objects.nonNull(repository)) {
            repository.close();
        }
    }
    @Test
    void trueRootCommitIsBilledForItsWholeTreeIncludingNestedDirectories() throws Exception {
        stage("src/main/java/Root.java", "class Root {}\n");
        stage("src/test/java/RootTest.java", "class RootTest {}\n");
        RevCommit root = commit("README.md", "# project\n", "bootstrap");
        args.setCommitId(root.getName());

        assertEquals(
                Set.of("ADD README.md", "ADD src/main/java/Root.java", "ADD src/test/java/RootTest.java"),
                changes(analyzer.analyze()),
                "a root commit's whole tree is its delta, at every depth");
    }
    /**
     * The empty-tree baseline can only ever produce ADD entries, and that is what keeps the absent parent from being
     * dereferenced: nothing in a root commit's delta has content before the change. Rename detection is on, so this
     * is worth pinning rather than assuming — a rename needs a source in the old tree, and the old tree is empty.
     */
    @Test
    void nothingInARootCommitsDeltaClaimsContentBeforeTheChange() throws Exception {
        stage("src/main/java/Root.java", "class Root {}\n");
        stage("src/main/resources/app.properties", "name=root\n");
        RevCommit root = commit("README.md", "# project\n", "bootstrap");
        args.setCommitId(root.getName());

        CommitAnalysis analysis = analyzer.analyze();

        List<GitFileAnalysis> files = ((GitCommitAnalysis) analysis).getFiles().stream()
                .map(GitFileAnalysis.class::cast)
                .toList();

        assertEquals(3, files.size(), changes(analysis).toString());
        for (GitFileAnalysis file : files) {
            assertTrue(StringUtils.isEmpty(file.getContentBefore()),
                    file.getNewPath() + " reports content before the change, but a root commit has no parent to read it from");
            assertTrue(StringUtils.isNotEmpty(file.getContentAfter()), file.getNewPath() + " reports no content after the change");
        }
    }
    @Test
    void rootCommitWithoutSourceFilesStillReportsThoseFiles() throws Exception {
        stage(".gitignore", "target/\n");
        RevCommit root = commit("README.md", "# project\n", "Initial commit");
        args.setCommitId(root.getName());

        assertEquals(Set.of("ADD .gitignore", "ADD README.md"), changes(analyzer.analyze()),
                "the analyzer reports the files and lets the caller's language filter decide the skip —"
                        + " a file-less analysis would blame language matching for an unrelated cause");
    }
    @Test
    void unrelatedHistoryRootIsBilledForItsOwnTreeOnly() throws Exception {
        commit("Main.java", "class Main {}\n", "main line");
        commit("Second.java", "class Second {}\n", "main line grows");

        RevCommit imported = unrelatedRoot();
        args.setCommitId(imported.getName());

        CommitAnalysis analysis = analyzer.analyze();

        assertTrue(((GitCommitAnalysis) analysis).getParentIds().isEmpty(), "an imported history starts at a root commit");
        assertEquals(Set.of("ADD Imported.java"), changes(analysis),
                "a second root in a multi-root repository owns only its own tree — the main line's files"
                        + " belong to the main line's commits and must not be billed twice");
    }
    @Test
    void mergeOfUnrelatedHistoriesIsBilledAgainstItsFirstParentOnly() throws Exception {
        commit("Main.java", "class Main {}\n", "main line");
        RevCommit imported = unrelatedRoot();

        RevCommit merge = merge(imported, "Merge imported history");
        args.setCommitId(merge.getName());

        CommitAnalysis analysis = analyzer.analyze();

        assertEquals(2, ((GitCommitAnalysis) analysis).getParentIds().size());
        assertTrue(analysis.isMergeCommit());
        assertEquals(Set.of("ADD Imported.java"), changes(analysis),
                "the merge's delta is measured against parent[0] even when parent[1] is an unrelated root —"
                        + " falling back to the empty tree here would re-bill the whole main line");
    }
    @Test
    void twoParentMergeKeepsFirstParentDeltaAndCreditsTheSideBranchAuthor() throws Exception {
        commit("Main.java", "class Main {}\n", "main line");

        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commitAs("Feature.java", "class Feature {}\n", "feature work", "Dev B", "devb@example.com");
        commitAs("FeatureTwo.java", "class FeatureTwo {}\n", "more feature work", "Dev B", "devb@example.com");

        git.checkout().setName(mainline).call();
        RevCommit merge = merge(repository.resolve("feature"), "Merge pull request #1");
        args.setCommitId(merge.getName());

        CommitAnalysis analysis = analyzer.analyze();

        assertTrue(analysis.isMergeCommit());
        assertEquals("devb@example.com", analysis.getAuthorEmail(),
                "a sole-author PR merge is credited to the side-branch author, not to whoever clicked merge");
        assertEquals(Set.of("ADD Feature.java", "ADD FeatureTwo.java"), changes(analysis),
                "the merge node carries the side branch's net change once, measured against parent[0]");
    }
    @Test
    void octopusMergeKeepsFirstParentDeltaAndItsOwnAuthor() throws Exception {
        commit("Main.java", "class Main {}\n", "main line");
        RevCommit base = commit("Shared.java", "class Shared {}\n", "shared");

        git.branchCreate().setName("f1").call();
        git.checkout().setName("f1").call();
        RevCommit f1 = commit("F1.java", "class F1 {}\n", "f1 work");

        git.checkout().setName(mainline).call();
        git.branchCreate().setName("f2").call();
        git.checkout().setName("f2").call();
        RevCommit f2 = commit("F2.java", "class F2 {}\n", "f2 work");

        git.checkout().setName(mainline).call();
        merge(repository.resolve("f1"), "Merge f1");
        RevCommit combined = merge(repository.resolve("f2"), "Merge f2");

        RevCommit octopus = rawCommit(combined.getTree(), "octopus merge", base, f1, f2);
        args.setCommitId(octopus.getName());

        CommitAnalysis analysis = analyzer.analyze();

        assertEquals(3, ((GitCommitAnalysis) analysis).getParentIds().size());
        assertTrue(analysis.isMergeCommit());
        assertEquals(IMPORTED_IDENTITY.getEmailAddress(), analysis.getAuthorEmail(),
                "an octopus merge has no sole side-branch author, so its own author identity stands");
        assertEquals(Set.of("ADD F1.java", "ADD F2.java"), changes(analysis),
                "more than two parents still means parent[0] is the baseline, never the empty tree");
    }
    @Test
    void graftedMergeIsSkippedJustLikeAnyShallowBoundary() throws Exception {
        commit("Main.java", "class Main {}\n", "main line");

        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();
        commit("Feature.java", "class Feature {}\n", "feature work");

        git.checkout().setName(mainline).call();
        RevCommit merge = merge(repository.resolve("feature"), "Merge pull request #1");

        Files.writeString(tempDir.resolve(".git/shallow"), merge.getName() + StringUtils.LF, StandardCharsets.UTF_8);
        args.setCommitId(merge.getName());

        CommitAnalysis analysis = analyzer.analyze();

        assertTrue(((GitCommitAnalysis) analysis).getParentIds().isEmpty(),
                "a graft hides every parent, so even a merge reports as parentless");
        assertFalse(analysis.isMergeCommit(),
                "the graft costs the merge its own identity too — one more reason the count alone cannot be trusted");
        assertTrue(((GitCommitAnalysis) analysis).getFiles().isEmpty(),
                "a grafted commit's true delta is unknowable, so it stays file-less rather than being"
                        + " billed for every pre-existing file in the tree");
    }
    private RevCommit unrelatedRoot() throws Exception {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "class Imported {}\n".getBytes(StandardCharsets.UTF_8));

            TreeFormatter tree = new TreeFormatter();
            tree.append("Imported.java", FileMode.REGULAR_FILE, blob);
            ObjectId treeId = inserter.insert(tree);
            inserter.flush();

            return rawCommit(treeId, "imported history");
        }
    }
    private RevCommit rawCommit(ObjectId tree, String message, RevCommit... parents) throws Exception {
        CommitBuilder builder = new CommitBuilder();
        builder.setTreeId(tree);
        builder.setParentIds(parents);
        builder.setAuthor(IMPORTED_IDENTITY);
        builder.setCommitter(IMPORTED_IDENTITY);
        builder.setMessage(message);

        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId inserted = inserter.insert(builder);
            inserter.flush();
            return repository.parseCommit(inserted);
        }
    }
    private RevCommit merge(ObjectId target, String message) throws Exception {
        MergeResult result = git.merge().include(target)
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true).setMessage(message).call();
        return repository.parseCommit(result.getNewHead());
    }
    private RevCommit commit(String path, String content, String message) throws Exception {
        stage(path, content);
        return git.commit().setMessage(message).setAuthor("Test Author", "test@example.com").call();
    }
    private RevCommit commitAs(String path, String content, String message, String author, String email) throws Exception {
        stage(path, content);
        return git.commit().setMessage(message).setAuthor(author, email).call();
    }
    private void stage(String path, String content) throws Exception {
        Path target = tempDir.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
    }
    private static Set<String> changes(CommitAnalysis analysis) {
        return ((GitCommitAnalysis) analysis).getFiles().stream()
                .map(GitFileAnalysis.class::cast)
                .map(file -> file.getChangeType() + " " + file.getNewPath())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
