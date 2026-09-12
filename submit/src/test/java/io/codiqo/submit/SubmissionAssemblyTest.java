package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.ClientInfoModel;

/**
 * What each assembly path is contractually required to leave on the submission. The convention-guidance cases
 * cover the unscored paths, whose fallbacks catch {@code IOException} only, so an over-budget instruction set
 * must not throw unchecked through them.
 */
class SubmissionAssemblyTest {
    private static final String OVER_BUDGET_CAP = "1";

    @TempDir
    Path tempDir;

    private Repository repository;
    private Git git;
    private RunArgs args;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();

        args = new RunArgs();
        args.setGit(repository);
        args.setAutoDiscoveryAgentInstructions(true);
    }
    @AfterEach
    void closeRepo() {
        if (git != null) {
            git.close();
        }
        if (repository != null) {
            repository.close();
        }
    }
    @Test
    void diffOnlyCarriesTheScoringConfigAndTheConventionFiles() throws Exception {
        writeConventions("Fail fast. No defensive programming.\n");

        SubmissionContext ctx = context();
        SubmissionAssembly.diffOnly(ctx);

        assertNotNull(ctx.getSubmissionModel().getScoringConfig(), "every scored path persists the effective config");
        assertTrue(ctx.getSubmissionModel().getAgentInstructions().contains("Fail fast"),
                "the repository's own conventions travel with the submission");
        assertNotNull(ctx.getSubmissionModel().getCommit(), "diff-only still carries commit metadata");
    }
    /** losing the instructions is acceptable on a path nothing scores; losing the commit is not */
    @Test
    void diffOnlySurvivesAnOverBudgetInstructionSet() throws Exception {
        writeConventions("far more than one character of guidance\n");
        args.setLlmConventionFilesMaxChars(Integer.parseInt(OVER_BUDGET_CAP));

        SubmissionContext ctx = context();
        assertDoesNotThrow(() -> SubmissionAssembly.diffOnly(ctx),
                "a misconfigured instruction budget must not turn a degraded submission into a lost commit");

        assertNull(ctx.getSubmissionModel().getAgentInstructions(), "the instructions are dropped, not truncated");
        assertNotNull(ctx.getSubmissionModel().getScoringConfig(), "everything else on the submission still stands");
    }
    @Test
    void excludedCarriesTheFilesAndNothingThatOnlyMattersToScoring() throws Exception {
        writeConventions("Fail fast.\n");

        SubmissionContext ctx = context();
        SubmissionAssembly.excluded(ctx);

        assertNotNull(ctx.getSubmissionModel().getFiles(), "an exclusion still reports its per-file changes");
        assertNull(ctx.getSubmissionModel().getScoringConfig(), "nothing scores an exclusion");
        assertNull(ctx.getSubmissionModel().getAgentInstructions(),
                "reading the instruction files can throw, and must never be able to fail an exclusion");
        assertNull(ctx.getSubmissionModel().getCommit(), "excluded() runs the file populator alone");
    }
    /**
     * no language server runs on this path, so there is no version to report — and resolving one would fetch
     * latest.txt over the network, which is a lost commit rather than a degraded one whenever Eclipse is down
     */
    @Test
    void diffOnlyReportsNoLanguageServerVersionAndNeverResolvesOne() throws Exception {
        SubmissionContext ctx = context();
        SubmissionAssembly.diffOnly(ctx);

        assertNull(ctx.getSubmissionModel().getScoringConfig().getJdtlsVersion(),
                "an unresolved language server must be reported as absent, not fetched to fill the field");
    }
    @Test
    void aZeroBudgetIsTheDocumentedKillSwitchRatherThanAFailure() throws Exception {
        writeConventions("Fail fast.\n");
        args.setLlmConventionFilesMaxChars(0);

        SubmissionContext ctx = context();
        SubmissionAssembly.diffOnly(ctx);

        assertNull(ctx.getSubmissionModel().getAgentInstructions(), "a zero budget turns instruction loading off");
        assertNotNull(ctx.getSubmissionModel().getScoringConfig());
    }
    private void writeConventions(String body) throws Exception {
        Files.writeString(tempDir.resolve("CLAUDE.md"), body, StandardCharsets.UTF_8);
    }
    private SubmissionContext context() {
        ClientInfoModel clientInfo = new ClientInfoModel();
        clientInfo.setName("test");

        return SubmissionContext.create(
                args, null, new StubAnalysis(), tempDir, StubAnalysis.LOGS, "group:artifact", "test", clientInfo);
    }
}
