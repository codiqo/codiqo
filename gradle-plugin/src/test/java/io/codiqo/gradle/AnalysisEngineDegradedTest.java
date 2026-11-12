package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.model.AnalysisBuildFailureModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.submit.CommitBuildFailedException;
import io.codiqo.submit.SubmissionAssembly;
import io.codiqo.submit.auth.CredentialStore;
import io.codiqo.submit.outcome.BuildVerdict;
import io.codiqo.submit.outcome.CommitOutcome;

/** a build-failed commit is routed as on Maven */
class AnalysisEngineDegradedTest {
    private static final SlfLogFactory LOGS = new SlfLogFactory();
    private static final Log LOG = LOGS.getLogger(AnalysisEngineDegradedTest.class);
    /** a call that should never be made fails here rather than reaching Codiqo's own API */
    private static final String UNREACHABLE_API = "http://127.0.0.1:1";
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
    /**
     * A tracked source directory the index cannot open fails the source-only index with an I/O error, the case the
     * fallback is for: the commit is scored from its diff, that is recorded in the build failure, and the whole-tree
     * module that only the index needed is not sent.
     */
    @Test
    void anUnreadableSourceTreeFallsBackToTheDiffAndRecordsIt() throws Exception {
        commit("src/main/java/b/B.java", "class B {}");
        commit("src/main/java/a/A.java", "class A {}");
        RevCommit broken = commit("src/main/java/a/A.java", "class A { void broken( }");

        AnalysisRequest request = request();
        RunArgs args = args(broken);

        Path unreadable = tempDir.resolve(Paths.get("src", "main", "java", "b"));
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(unreadable), "a user who can read any directory cannot make the index fail");

            AnalysisSubmissionModel submission = AnalysisEngine.commitRun(request, AnalysisEngine.server(request, LOG), args, LOGS)
                    .degradedSubmission(new BuildVerdict.Failed("build failed", AnalysisExcludeCategory.BUILD_FAILURE, BUILD_FAILURE_DETAIL, false))
                    .getSubmissionModel();

            AnalysisBuildFailureModel buildFailure = submission.getBuildFailure();
            assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, buildFailure.getCategory());
            assertTrue(buildFailure.getDetail().startsWith(BUILD_FAILURE_DETAIL), "the build's own failure stays first: " + buildFailure.getDetail());
            assertTrue(Strings.CS.contains(buildFailure.getDetail(), SubmissionAssembly.DIFF_ONLY_FALLBACK_MARKER), buildFailure.getDetail());
            assertTrue(Strings.CS.contains(buildFailure.getDetail(), unreadable.toString()), "the root cause names what failed: " + buildFailure.getDetail());

            assertNotNull(submission.getProject(), "the project model is sent with a diff-only submission too");
            assertTrue(submission.getProject().getModules().isEmpty(), "without the module that stood for the whole tree");
            assertTrue(args.getProjects().isEmpty());
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"));
        }
    }
    /**
     * A run told not to tolerate a broken build fails before the review starts, as on Maven. The credential store is
     * empty, so a review started first fails while it resolves the login, instead of on the build.
     */
    @Test
    void aBrokenBuildThatMustFailTheRunFailsItBeforeAnyReviewStarts() throws Exception {
        commit("src/main/java/a/A.java", "class A {}");
        RevCommit broken = commit("src/main/java/a/A.java", "class A { void broken( }");

        AnalysisRequest request = request();
        request.setAuthUrl("https://auth.invalid");
        RunArgs args = args(broken);
        args.setSkipOnBuildFailure(false);
        args.setReviewEnabled(true);

        String home = System.getProperty(CredentialStore.HOME_PROPERTY);
        System.setProperty(CredentialStore.HOME_PROPERTY, Files.createDirectory(tempDir.resolve("codiqo-home")).toString());
        try {
            CommitOutcome.Deferred deferred = assertInstanceOf(CommitOutcome.Deferred.class, AnalysisEngine.commitRun(request, AnalysisEngine.server(request, LOG), args, LOGS).run());

            CommitBuildFailedException err = assertInstanceOf(CommitBuildFailedException.class, deferred.getCause());
            assertTrue(Strings.CS.contains(err.getMessage(), BUILD_FAILURE_DETAIL), err.getMessage());
        } finally {
            if (Objects.isNull(home)) {
                System.clearProperty(CredentialStore.HOME_PROPERTY);
            } else {
                System.setProperty(CredentialStore.HOME_PROPERTY, home);
            }
        }
    }
    /** nothing analysable on a broken tree is excluded as such, as Maven does before its build, and fails no run */
    @Test
    void aCommitWithNothingAnalysableOnABrokenTreeIsExcludedForThat() throws Exception {
        List<String> calls = Lists.newCopyOnWriteArrayList();
        List<String> bodies = Lists.newCopyOnWriteArrayList();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/analyses", exchange -> {
            calls.add(exchange.getRequestMethod() + StringUtils.SPACE + exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        commit("src/main/java/a/A.java", "class A { void broken( }");
        RevCommit docs = commit("README.md", "# A");

        AnalysisRequest request = request();
        request.setApiUrl(String.format("http://127.0.0.1:%d", server.getAddress().getPort()));
        request.setApiKey("test-api-key");
        request.setConnectTimeoutSeconds(5);
        request.setReadTimeoutSeconds(5);
        RunArgs args = args(docs);
        args.setSkipOnBuildFailure(false);
        try {
            assertInstanceOf(CommitOutcome.Excluded.class, AnalysisEngine.commitRun(request, AnalysisEngine.server(request, LOG), args, LOGS).run());
        } finally {
            server.stop(0);
        }

        assertEquals(List.of("PATCH /api/v1/analyses/commit/" + docs.getName() + "/exclude"), calls);
        assertTrue(Strings.CS.contains(bodies.get(0), AnalysisExcludeCategory.NO_ANALYZABLE_DIFF.getValue()), bodies.get(0));
        assertTrue(Strings.CS.contains(bodies.get(0), "README.md"), "with the diff the backend records: " + bodies.get(0));
    }
    private static AnalysisRequest request() {
        AnalysisRequest toReturn = new AnalysisRequest();
        toReturn.setRootPath(":");
        toReturn.setRootCode("com.example:app");
        toReturn.setRootName("app");
        toReturn.setApiUrl(UNREACHABLE_API);
        toReturn.setResourceUrl(RunArgs.DEFAULT_RESOURCE_URL);
        toReturn.setBuildFailureDetail(BUILD_FAILURE_DETAIL);
        return toReturn;
    }
    private RunArgs args(RevCommit commit) {
        RunArgs toReturn = new RunArgs();
        toReturn.setGit(repository);
        toReturn.setCommitId(commit.getName());
        toReturn.setReviewEnabled(false);
        return toReturn;
    }
    private RevCommit commit(String path, String content) throws Exception {
        Path file = tempDir.resolve(Paths.get(path));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("change " + path).setSign(false).call();
    }
}
