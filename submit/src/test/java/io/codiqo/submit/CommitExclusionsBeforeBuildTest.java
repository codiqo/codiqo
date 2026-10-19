package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.submit.CommitExclusions.Exclusion;

/** a commit with nothing to analyse is excluded from git alone, before anything builds it */
class CommitExclusionsBeforeBuildTest {
    private static final List<String> EXTENSIONS = List.of("java");

    @TempDir
    Path tempDir;

    private Git git;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
    }
    @AfterEach
    void closeRepo() {
        git.close();
    }
    /** a repository's first commit: no pom.xml to build, so it was excluded as a build failure */
    @Test
    void aFirstCommitHoldingOnlyAGitignoreHasNothingToAnalyse() throws Exception {
        RevCommit root = commit(".gitignore", "target/\n");

        Exclusion excluded = CommitExclusions.beforeBuild(git.getRepository(), root.getName(), EXTENSIONS).orElseThrow();

        assertEquals(AnalysisExcludeCategory.NO_ANALYZABLE_DIFF, excluded.getCategory());
        assertTrue(Strings.CS.contains(excluded.getReason(), ".gitignore"), excluded.getReason());
    }
    @Test
    void aDocumentationOnlyCommitIsExcludedBeforeItsBuild() throws Exception {
        commit("src/main/java/a/A.java", "class A {}");
        RevCommit docs = commit("README.md", "# A");

        assertEquals(AnalysisExcludeCategory.NO_ANALYZABLE_DIFF,
                CommitExclusions.beforeBuild(git.getRepository(), docs.getName(), EXTENSIONS).orElseThrow().getCategory());
    }
    @Test
    void aCommitChangingSourceOrABuildFileIsBuilt() throws Exception {
        RevCommit source = commit("src/main/java/a/A.java", "class A {}");
        RevCommit pom = commit("pom.xml", "<project/>");

        assertTrue(CommitExclusions.beforeBuild(git.getRepository(), source.getName(), EXTENSIONS).isEmpty(), "a source change is analysed");
        assertTrue(CommitExclusions.beforeBuild(git.getRepository(), pom.getName(), EXTENSIONS).isEmpty(), "a pom.xml is a config file the analysis reads");
    }
    /**
     * A shallow-clone boundary lists no changed file, as a commit with nothing analysable would: only the delta can tell
     * it is a missing parent, which the after-build gate reports as incomplete history.
     */
    @Test
    void aShallowBoundaryIsLeftToTheDelta() throws Exception {
        commit("README.md", "# A");
        RevCommit boundary = commit("NOTES.md", "notes");
        Files.writeString(tempDir.resolve(".git/shallow"), boundary.getName() + "\n", StandardCharsets.UTF_8);

        try (Git shallow = Git.open(tempDir.toFile())) {
            Optional<Exclusion> excluded = CommitExclusions.beforeBuild(shallow.getRepository(), boundary.getName(), EXTENSIONS);

            assertTrue(excluded.isEmpty(), "decided after the build, as incomplete history");
        }
    }
    private RevCommit commit(String path, String content) throws Exception {
        Path file = tempDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("add " + path).setSign(false).call();
    }
}
