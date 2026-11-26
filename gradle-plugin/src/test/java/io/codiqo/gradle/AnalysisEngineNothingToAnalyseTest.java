package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.gradle.model.AnalysisRequest;

/** the daemon skips the build of a commit with nothing to analyse by the rule Maven applies before its build */
class AnalysisEngineNothingToAnalyseTest {
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
    @Test
    void aDocumentationOnlyCommitHasNothingToAnalyse() throws Exception {
        commit("src/main/java/a/A.java", "class A {}");
        RevCommit docs = commit("README.md", "# A");

        assertEquals(AnalysisExcludeCategory.NO_ANALYZABLE_DIFF, AnalysisEngine.nothingToAnalyse(request(docs.getName())).orElseThrow().getCategory());
    }
    @Test
    void aCommitChangingSourceOrABuildScriptIsBuilt() throws Exception {
        RevCommit source = commit("src/main/java/a/A.java", "class A {}");
        RevCommit script = commit("build.gradle.kts", "plugins { java }");

        assertTrue(AnalysisEngine.nothingToAnalyse(request(source.getName())).isEmpty());
        assertTrue(AnalysisEngine.nothingToAnalyse(request(script.getName())).isEmpty());
    }
    @Test
    void withoutACommitIdTheCheckedOutHeadIsRead() throws Exception {
        commit("src/main/java/a/A.java", "class A {}");
        commit("docs/guide.md", "# guide");

        assertTrue(AnalysisEngine.nothingToAnalyse(request(null)).isPresent(), "the worker reads HEAD when no commit is named, so does the check");
    }
    private AnalysisRequest request(String commitId) {
        AnalysisRequest toReturn = new AnalysisRequest();
        toReturn.setRootDir(tempDir.toString());
        toReturn.setCommitId(commitId);
        return toReturn;
    }
    private RevCommit commit(String path, String content) throws Exception {
        Path file = tempDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("change " + path).setAuthor("Author", "author@example.com").setSign(false).call();
    }
}
