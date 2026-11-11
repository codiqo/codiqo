package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisBuildFailureModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.submit.SubmissionAssembly;

/** a build-failed commit whose source index could not be read is scored diff-only, and recorded so, as on Maven */
class AnalysisEngineDegradedTest {
    private static final String BUILD_FAILURE_DETAIL = "Execution failed for task ':compileJava'.";

    @TempDir
    Path tempDir;

    private Git git;
    private Repository repository;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();
    }
    @AfterEach
    void closeRepo() {
        repository.close();
        git.close();
    }
    @Test
    void theDiffOnlyFallbackIsRecordedInTheBuildFailureAndKeepsTheProjectModel() throws Exception {
        commit("src/main/java/a/A.java", "class A {}");
        RevCommit broken = commit("src/main/java/a/A.java", "class A { void broken( }");

        AnalysisRequest request = new AnalysisRequest();
        request.setRootPath(":");
        request.setRootCode("com.example:app");
        request.setRootName("app");
        request.setBuildFailureDetail(BUILD_FAILURE_DETAIL);
        RunArgs args = new RunArgs();
        args.setGit(repository);
        args.setCommitId(broken.getName());
        args.getProjects().add(GradleSourceOnlyProjectSpec.forWorkTree(tempDir.toFile(), request));

        AnalysisSubmissionModel submission = AnalysisEngine.diffOnlySubmission(
                request, args, "build failed", new IOException("source index failed", new IOException("src/main/java is gone")), new SlfLogFactory())
                .getSubmissionModel();

        AnalysisBuildFailureModel buildFailure = submission.getBuildFailure();
        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, buildFailure.getCategory());
        assertTrue(buildFailure.getDetail().startsWith(BUILD_FAILURE_DETAIL), "the build's own failure stays first: " + buildFailure.getDetail());
        assertTrue(Strings.CS.contains(buildFailure.getDetail(), SubmissionAssembly.DIFF_ONLY_FALLBACK_MARKER), buildFailure.getDetail());
        assertTrue(Strings.CS.contains(buildFailure.getDetail(), "src/main/java is gone"), "the root cause names what failed: " + buildFailure.getDetail());

        assertNotNull(submission.getProject(), "the project model is sent with a diff-only submission too");
        assertEquals("app", submission.getProject().getModules().iterator().next().getName());
    }
    private RevCommit commit(String path, String content) throws Exception {
        Path file = tempDir.resolve(Paths.get(path));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("change " + path).setSign(false).call();
    }
}
