package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

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
import io.codiqo.submit.CommitBuildFailedException;
import io.codiqo.submit.SubmissionAssembly;
import io.codiqo.submit.auth.CredentialStore;

/** a build-failed commit is routed as on Maven */
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
    /**
     * A run told not to tolerate a broken build fails before the review starts, as on Maven. The credential store is
     * empty, so a review started first fails while it resolves the login, instead of on the build.
     */
    @Test
    void aBrokenBuildThatMustFailTheRunFailsItBeforeAnyReviewStarts() throws Exception {
        commit("src/main/java/a/A.java", "class A {}");
        RevCommit broken = commit("src/main/java/a/A.java", "class A { void broken( }");

        AnalysisRequest request = new AnalysisRequest();
        request.setAuthUrl("https://auth.invalid");
        request.setBuildFailureDetail(BUILD_FAILURE_DETAIL);
        RunArgs args = new RunArgs();
        args.setGit(repository);
        args.setCommitId(broken.getName());
        args.setSkipOnBuildFailure(false);
        args.setReviewEnabled(true);

        String home = System.getProperty(CredentialStore.HOME_PROPERTY);
        System.setProperty(CredentialStore.HOME_PROPERTY, Files.createDirectory(tempDir.resolve("codiqo-home")).toString());
        try {
            SlfLogFactory logs = new SlfLogFactory();
            CommitBuildFailedException err = assertThrows(CommitBuildFailedException.class,
                    () -> AnalysisEngine.analyse(request, args, logs, logs.getLogger(AnalysisEngineDegradedTest.class)));

            assertTrue(Strings.CS.contains(err.getMessage(), BUILD_FAILURE_DETAIL), err.getMessage());
        } finally {
            if (Objects.isNull(home)) {
                System.clearProperty(CredentialStore.HOME_PROPERTY);
            } else {
                System.setProperty(CredentialStore.HOME_PROPERTY, home);
            }
        }
    }
    private RevCommit commit(String path, String content) throws Exception {
        Path file = tempDir.resolve(Paths.get(path));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("change " + path).setSign(false).call();
    }
}
