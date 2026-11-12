package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Deque;
import java.util.List;

import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Queues;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisExcludeCategory;

/**
 * The time-machine ladder answers a timeout with no further attempt, and says that the build timed out: a build that ran
 * out of time is the environment's failure, never the author's broken build that a review scores as degraded.
 */
class BuildBackoffTimeoutTest {
    @TempDir
    Path tempDir;

    private Git git;

    @AfterEach
    void closeRepo() {
        git.close();
    }
    @Test
    void aLastAttemptThatTimedOutSaysSo() throws Exception {
        LadderMojo mojo = new LadderMojo(List.of(
                new AbstractAnalyzeMojo.BuildOutcome.Skipped("unresolved dependency", AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, "could not resolve"),
                new AbstractAnalyzeMojo.BuildOutcome.Skipped("build failed", AnalysisExcludeCategory.BUILD_FAILURE, "build timed out", true)));

        AbstractAnalyzeMojo.BuildOutcome.Skipped skipped = (AbstractAnalyzeMojo.BuildOutcome.Skipped) new MavenCommitBuild(mojo, args()).buildWithBackoff(null);

        assertTrue(skipped.isTimedOut(), "the latest-snapshots attempt timed out");
        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, skipped.getCategory(), "the commit-faithful attempt is still the headline");
        assertEquals(0, mojo.outcomes.size(), "both attempts ran");
    }
    private RunArgs args() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        git.add().addFilepattern("pom.xml").call();
        RevCommit commit = git.commit().setMessage("add pom").setAuthor("Author", "author@example.com").setSign(false).call();

        RunArgs toReturn = new RunArgs();
        toReturn.setGit(git.getRepository());
        toReturn.setCommitId(commit.getName());
        return toReturn;
    }

    /** answers each attempt of the ladder with the next scripted outcome instead of forking a build */
    private static final class LadderMojo extends SubmitCommitAnalysisMojo {
        private final Deque<AbstractAnalyzeMojo.BuildOutcome> outcomes;

        private LadderMojo(List<AbstractAnalyzeMojo.BuildOutcome> outcomes) {
            this.outcomes = Queues.newArrayDeque(outcomes);
        }
        @Override
        protected InvocationRequest invocationRequest(RunArgs args, boolean timeMachineRequested, Duration targetOffset, BuildPhase phase) {
            return new DefaultInvocationRequest();
        }
        @Override
        protected BuildOutcome buildProject(RunArgs args, InvocationRequest request, ProjectBuildingRequest buildingRequest, BuildPhase phase, Duration timeout) {
            return outcomes.removeFirst();
        }
    }
}
