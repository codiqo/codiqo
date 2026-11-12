package io.codiqo.submit.outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.llm.review.ReviewVerdict;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.submit.CodiqoServer;
import io.codiqo.submit.CommitBuildFailedException;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.ProjectHaltedException;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.review.BackgroundReview;
import io.codiqo.submit.review.LocalReviewRun;
import io.codiqo.util.DownloadFailedException;

/**
 * A commit's run through its interface, against Codiqo kept in memory and a build scripted per test: the outcome each
 * kind of commit and failure ends in, and what the run sent for it. The full analysis starts the language server, so it
 * is left to the plugins' own runs; the rest of the run is here.
 */
class CommitRunTest {
    private static final PersonIdent AUTHOR = new PersonIdent("Author", "author@example.com");
    private static final LogFactory LOGS = clazz -> new NoopLog();
    private static final String BUILD_DETAIL = "[ERROR] A.java:[1,10] cannot find symbol";
    private static final String PROVIDER_SUMMARY = "the LLM provider answered 429 40 times during the review";
    /** the halt request's limit on its reason (ProjectHalts) */
    private static final int HALT_REASON_LIMIT = 2000;
    /** the coordinator's answer as the review keeps it: one reviewer's area of the commit */
    private static final String COORDINATOR_ANSWER = "{\"tasks\":[{\"paths\":[\"src/main/java/a/A.java\"]}]}";

    @TempDir
    Path tempDir;

    private final InMemoryCodiqoServer server = new InMemoryCodiqoServer();
    private final ScriptedBuild build = new ScriptedBuild();
    private final AtomicInteger reviewsStarted = new AtomicInteger();
    private Git git;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.resolve("repo").toFile()).call();
        commit("src/main/java/a/A.java", "class A {}");
    }
    @AfterEach
    void closeRepo() {
        git.close();
    }
    @Test
    void aCommitTheRulesExcludeIsSettledBeforeItIsBuilt() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A { int a; }"));
        args.setExcludeAuthorEmails(AUTHOR.getEmailAddress());

        CommitOutcome.Excluded excluded = assertInstanceOf(CommitOutcome.Excluded.class, run(args, ReviewStarter.LOCAL).admit().orElseThrow());

        assertEquals(AnalysisExcludeCategory.FILTERED_BY_RULES, excluded.getExclusion().getCategory());
        assertEquals(List.of(args.getCommitId()), List.copyOf(server.exclusions.keySet()));
        assertEquals(0, build.compiled);
    }
    /** a halted project is already halted: the run fails before anything is paid for, and halts nothing again */
    @Test
    void aHaltedProjectAdmitsNoCommit() throws Exception {
        server.halted = true;

        assertThrows(ProjectHaltedException.class, () -> run(args(commit("src/main/java/a/A.java", "class A { int a; }")), ReviewStarter.LOCAL).admit());
        assertEquals(List.of(), server.halts);
    }
    @Test
    void aCommitWithNothingAnalysableIsExcludedWithItsDiffAndNeverBuilt() throws Exception {
        RunArgs args = args(commit("README.md", "# A"));

        CommitOutcome.Excluded excluded = assertInstanceOf(CommitOutcome.Excluded.class, run(args, ReviewStarter.LOCAL).run());

        assertEquals(AnalysisExcludeCategory.NO_ANALYZABLE_DIFF, excluded.getExclusion().getCategory());
        assertEquals(List.of("README.md"), paths(excluded.getExclusion()));
        assertEquals(0, build.compiled);
    }
    /** a pipeline told not to tolerate a broken build fails on it, before any review, and halts nothing */
    @Test
    void aBrokenBuildTheRunMayNotTolerateIsDeferred() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A { void broken( }"));
        args.setSkipOnBuildFailure(false);
        build.compile = () -> authorsFailure();

        CommitOutcome.Deferred deferred = assertInstanceOf(CommitOutcome.Deferred.class, run(args, this::noReview).run());

        assertInstanceOf(CommitBuildFailedException.class, deferred.getCause());
        assertEquals(0, reviewsStarted.get());
        assertEquals(List.of(), server.halts);
        assertTrue(server.exclusions.isEmpty());
    }
    @Test
    void aBrokenBuildNobodyReviewedIsExcludedForItWithItsFiles() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A { void broken( }"));
        args.setReviewEnabled(false);
        build.compile = () -> authorsFailure();

        CommitOutcome.Excluded excluded = assertInstanceOf(CommitOutcome.Excluded.class, run(args, ReviewStarter.LOCAL).run());

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, excluded.getExclusion().getCategory());
        assertEquals(BUILD_DETAIL, excluded.getExclusion().getDetail());
        assertEquals(List.of("src/main/java/a/A.java"), paths(excluded.getExclusion()));
    }
    /** a build that cannot run on the environment costs no review, and is analysed again once the environment is fixed */
    @Test
    void anEnvironmentsFailureStartsNoReview() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A { int a; }"));
        build.compile = () -> new BuildVerdict.Failed("dependency resolution failed", AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, "could not resolve a:b:1", false);

        CommitOutcome.Excluded excluded = assertInstanceOf(CommitOutcome.Excluded.class, run(args, this::noReview).run());

        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, excluded.getExclusion().getCategory());
        assertEquals(0, reviewsStarted.get());
    }
    /** an operator's re-inclusion of a build failure survives a re-analysis only under that category */
    @Test
    void aReviewThatFailedOnTheBrokenCommitExcludesItForItsBuild() throws Exception {
        CommitOutcome outcome = brokenCommitReviewed(new ReviewVerdict.CommitFailure(new IOException("the coordinator started no reviewer")));

        CommitOutcome.Excluded excluded = assertInstanceOf(CommitOutcome.Excluded.class, outcome);
        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, excluded.getExclusion().getCategory());
        assertEquals(List.of(), server.halts);
    }
    /** the halt banner shows the review's own failure, with the provider's failed answers whole */
    @Test
    void aReviewTheProviderFailedHaltsTheProjectWithItsAnswers() throws Exception {
        CommitOutcome outcome = brokenCommitReviewed(
                new ReviewVerdict.ProviderFailure(new IOException("session ses_1 was cut off before it called codiqo_submit_answer, so it gave no answer"), PROVIDER_SUMMARY));

        assertInstanceOf(CommitOutcome.Halted.class, outcome);
        assertEquals("local review failed: session ses_1 was cut off before it called codiqo_submit_answer, so it gave no answer; " + PROVIDER_SUMMARY, haltReason());
        assertTrue(server.exclusions.isEmpty(), "a review the provider broke is no judgement of the commit");
    }
    /**
     * A review that failed on Codiqo's side halts with its own reason, the provider's failed answers in it when there
     * were some: halted for the exception it was thrown as, the banner named only its root cause.
     */
    @Test
    void aReviewThatFailedOnCodiqosSideHaltsTheProjectWithTheReviewsReason() throws Exception {
        CommitOutcome outcome = brokenCommitReviewed(ReviewVerdict.unexpected(new IOException("GET /api/session/ses_1/message failed with 500"), Optional.of(PROVIDER_SUMMARY)));

        assertInstanceOf(CommitOutcome.Halted.class, outcome);
        assertEquals("local review failed: GET /api/session/ses_1/message failed with 500; " + PROVIDER_SUMMARY, haltReason());
    }
    /** a broken commit its review judged is submitted as a degraded commit, with the review's trace beside it */
    @Test
    void aBrokenCommitItsReviewJudgedIsSubmittedAsDegraded() throws Exception {
        LocalReview review = new LocalReview("1a2b3c4d", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), COORDINATOR_ANSWER, new LlmScoringResponse(), 1, List.of(), List.of());

        CommitOutcome outcome = brokenCommitReviewed(new ReviewVerdict.Succeeded(new LocalReviewer.ReviewOutcome(review, Optional.empty(), Optional.empty())));

        assertInstanceOf(CommitOutcome.Submitted.class, outcome, server.halts.toString());
        assertEquals(1, server.submissions.size());
        AnalysisSubmissionModel submitted = server.submissions.iterator().next();
        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, submitted.getBuildFailure().getCategory());
        assertTrue(Objects.nonNull(submitted.getLocalReview()), "scored from the review's judgment");
        assertTrue(server.traces.iterator().next().isPresent(), "with the review's trace");
        assertTrue(server.exclusions.isEmpty());
    }
    /** a failure whose message fills the halt's reason on its own still leaves room for the provider's answers */
    @Test
    void aLongFailureOnCodiqosSideKeepsTheProvidersAnswersInTheHalt() throws Exception {
        String wordy = "opencode answered 500: " + "x".repeat(2100);

        brokenCommitReviewed(ReviewVerdict.unexpected(new IOException(wordy), Optional.of(PROVIDER_SUMMARY)));

        assertTrue(haltReason().length() <= HALT_REASON_LIMIT, "the server refuses a longer reason, and ProjectHalts would cut its end off");
        assertTrue(haltReason().endsWith("; " + PROVIDER_SUMMARY), haltReason());
    }
    /** a review that failed while the run was being stopped halts nothing: someone stopped the run */
    @Test
    void aReviewThatFailedAsTheRunWasStoppedHaltsNothing() throws Exception {
        CommitOutcome outcome = brokenCommitReviewed(new ReviewVerdict.ProviderFailure(new IOException("stream cut", new InterruptedException()), PROVIDER_SUMMARY));

        assertInstanceOf(CommitOutcome.Deferred.class, outcome);
        assertEquals(List.of(), server.halts);
    }
    @Test
    void aStoppedReviewHaltsNothing() throws Exception {
        CommitOutcome outcome = brokenCommitReviewed(new ReviewVerdict.Stopped(new InterruptedException()));

        assertInstanceOf(CommitOutcome.Deferred.class, outcome);
        assertEquals(List.of(), server.halts);
        assertTrue(server.exclusions.isEmpty());
    }
    /** a failure on Codiqo's side, such as a compiler that crashed, halts the project rather than excluding the commit */
    @Test
    void aFailureOnCodiqosSideHaltsTheProject() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A { int a; }"));
        build.compile = () -> {
            throw new IOException("An exception has occurred in the compiler");
        };

        CommitOutcome.Halted halted = assertInstanceOf(CommitOutcome.Halted.class, run(args, this::noReview).run());

        assertTrue(halted.isRecorded());
        assertEquals("IOException: An exception has occurred in the compiler", haltReason());
    }
    /** the download host heals on its own: halting on it would hold the project until an administrator resumed it */
    @Test
    void aToolThatCouldNotBeDownloadedHaltsNothing() throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A { int a; }"));
        build.compile = () -> {
            throw new DownloadFailedException("download.eclipse.org answered 503");
        };

        assertInstanceOf(CommitOutcome.Deferred.class, run(args, this::noReview).run());
        assertEquals(List.of(), server.halts);
    }
    /** a build the author broke, reviewed, and scored from the review's assessment: what the review's verdict does to it */
    private CommitOutcome brokenCommitReviewed(ReviewVerdict verdict) throws Exception {
        RunArgs args = args(commit("src/main/java/a/A.java", "class A { void broken( }"));
        build.compile = () -> authorsFailure();

        return run(args, (runArgs, codiqo, logFactory) -> {
            reviewsStarted.incrementAndGet();
            return LocalReviewRun.of(BackgroundReview.start(() -> verdict), List.of(), logFactory);
        }).run();
    }
    private LocalReviewRun noReview(RunArgs args, CodiqoServer codiqo, LogFactory logFactory) {
        reviewsStarted.incrementAndGet();
        throw new IllegalStateException("no review was to start");
    }
    private CommitRun run(RunArgs args, ReviewStarter reviews) {
        return new CommitRun(args, server, build, new AnalysedProject("com.example:app", "app", new ClientInfoModel()),
                new OutputSerializer(true, LOGS.getLogger(OutputSerializer.class)), reviews, LOGS);
    }
    private RunArgs args(RevCommit commit) {
        RunArgs toReturn = new RunArgs();
        toReturn.setGit(git.getRepository());
        toReturn.setCommitId(commit.getName());
        toReturn.setOutputDirectory(tempDir.resolve("output").toFile());
        toReturn.setReviewTraceDirectory(tempDir.resolve("output").toFile());
        return toReturn;
    }
    private String haltReason() {
        assertEquals(1, server.halts.size());
        Pair<String, String> halt = server.halts.iterator().next();
        return halt.getRight();
    }
    private RevCommit commit(String path, String content) throws Exception {
        Path file = git.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("change " + path).setAuthor(AUTHOR).setCommitter(AUTHOR).setSign(false).call();
    }
    private static BuildVerdict authorsFailure() {
        return new BuildVerdict.Failed("build failed", AnalysisExcludeCategory.BUILD_FAILURE, BUILD_DETAIL, false);
    }
    private static List<String> paths(AnalysisExcludeModel exclusion) {
        return exclusion.getFiles().stream().map(FileChangeModel::getPath).toList();
    }

    /**
     * Answers each step with what the test scripted. It gives no source-only project, as a build that cannot set one up
     * would not, so a build-failed commit is scored from its diff.
     */
    private static final class ScriptedBuild implements CommitBuild {
        private Callable<BuildVerdict> compile = BuildVerdict::passed;
        private int compiled;

        @Override
        public BuildVerdict compile() throws Exception {
            compiled++;
            return compile.call();
        }
        @Override
        public BuildVerdict test() {
            throw new UnsupportedOperationException("the full analysis starts the language server");
        }
        @Override
        public ClassGraphSpec projects() {
            throw new UnsupportedOperationException("the full analysis starts the language server");
        }
        @Override
        public ProjectSpec sourceOnlyProject() throws IOException {
            throw new IOException("this build sets up no source-only project");
        }
        @Override
        public void populateProjectModel(SubmissionContext ctx) {
        }
    }

    private static final class NoopLog implements Log {
        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public void log(Level level, String message, Object... formatArgs) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
