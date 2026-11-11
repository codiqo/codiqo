package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
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

/** What each assembly path is contractually required to leave on the submission. */
class SubmissionAssemblyTest {
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
    void diffOnlyCarriesTheScoringConfig() throws Exception {
        SubmissionContext ctx = context();
        SubmissionAssembly.diffOnly(ctx);

        assertNotNull(ctx.getSubmissionModel().getScoringConfig(), "every scored path persists the effective config");
        assertNotNull(ctx.getSubmissionModel().getCommit(), "diff-only still carries commit metadata");
    }
    @Test
    void excludedCarriesTheFilesAndNothingThatOnlyMattersToScoring() throws Exception {
        SubmissionContext ctx = context();
        SubmissionAssembly.excluded(ctx);

        assertNotNull(ctx.getSubmissionModel().getFiles(), "an exclusion still reports its per-file changes");
        assertNull(ctx.getSubmissionModel().getScoringConfig(), "nothing scores an exclusion");
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
    private SubmissionContext context() {
        ClientInfoModel clientInfo = new ClientInfoModel();
        clientInfo.setName("test");

        return SubmissionContext.create(
                args, null, new StubAnalysis(), tempDir, StubAnalysis.LOGS, "group:artifact", "test", clientInfo);
    }
}
