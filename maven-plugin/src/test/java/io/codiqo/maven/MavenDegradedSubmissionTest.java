package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.HttpCodiqoServer;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.SourceHiding;
import io.codiqo.submit.outcome.AnalysedProject;
import io.codiqo.submit.outcome.BuildVerdict;
import io.codiqo.submit.outcome.CommitRun;
import io.codiqo.submit.outcome.ReviewStarter;

/**
 * A commit whose Maven build failed, scored from its source: the run's degraded submission over Maven's whole-work-tree
 * project parses the commit's code units and carries the driver-score statistics, so a degraded commit is priced by its
 * real code volume, and carries no coverage, duplication or quality aggregate it never measured.
 */
class MavenDegradedSubmissionTest {
    private static final String JAVA_V1 = """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;

            class Foo {
                List<String> build(int n) {
                    List<String> out = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        out.add("item-" + i);
                    }
                    return out;
                }
            }
            """;
    private static final String JAVA_V2 = """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;

            class Foo {
                List<String> build(int n) {
                    List<String> out = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        out.add("row-" + i + "-" + n);
                    }
                    return out;
                }
            }
            """;

    @TempDir
    Path tempDir;

    private Git git;
    private Repository repository;
    private SubmitCommitAnalysisMojo mojo;

    @BeforeEach
    void init() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();

        MavenProject project = new MavenProject();
        project.setGroupId("com.example");
        project.setArtifactId("demo");
        project.setName("demo");

        mojo = new SubmitCommitAnalysisMojo();
        mojo.setLog(new SystemStreamLog());
        mojo.project = project;
        FieldUtils.writeField(mojo, "runtimeInformation", new RuntimeInformation() {
            @Override
            public String getMavenVersion() {
                return "test";
            }
            @Override
            public boolean isMavenVersion(String versionRange) {
                return true;
            }
        }, true);
    }
    @AfterEach
    void close() {
        git.close();
        repository.close();
    }
    @Test
    void aBrokenBuildIsScoredFromItsSource() throws Exception {
        commitFile(JAVA_V1, "first");
        RevCommit second = commitFile(JAVA_V2, "second");
        RunArgs args = argsFor(second);

        AnalysisSubmissionModel submission = commitRun(args)
                .degradedSubmission(new BuildVerdict.Failed("[ERROR] COMPILATION ERROR", AnalysisExcludeCategory.BUILD_FAILURE, "cannot find symbol", false))
                .getSubmissionModel();

        assertEquals("[ERROR] COMPILATION ERROR", submission.getBuildFailure().getReason());
        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, submission.getBuildFailure().getCategory());
        assertEquals("cannot find symbol", submission.getBuildFailure().getDetail());
        assertEquals(second.getName(), submission.getCommit().getSha());
        assertNotNull(submission.getScoringConfig());

        assertTrue(CollectionUtils.isNotEmpty(submission.getFiles()));
        for (FileChangeModel file : submission.getFiles()) {
            assertTrue(StringUtils.isNotBlank(file.getDiff()), "diff must be captured for " + file.getPath());
            assertNull(file.getCoverage(), "degraded mode must not carry coverage");
        }
        assertTrue(submission.getFiles().stream().anyMatch(file -> CollectionUtils.isNotEmpty(file.getCodeUnits())), "the source-only index parses the code units");
        assertNotNull(submission.getProjectMetrics(), "driver-score statistics must be attached");
        assertTrue(submission.getProjectMetrics().getDriverScalers().getMethodScalerProd().getPopulation() > 0,
                "driver-scaler population must be non-empty so changed blocks actually score");

        assertNull(submission.getDuplication());
        assertNull(submission.getProjectQuality(), "degraded mode captures no PMD/CPD/coverage — no fake quality aggregate");
        assertNull(submission.getFullProjectCoverage(), "degraded mode captures no coverage — renders n/a, not 0%");
    }
    private CommitRun commitRun(RunArgs args) {
        MavenLogFactory logs = new MavenLogFactory(mojo.getLog());
        return new CommitRun(
                args,
                new HttpCodiqoServer("http://127.0.0.1:1", RunArgs.DEFAULT_RESOURCE_URL, () -> {
                    throw new IllegalStateException("no call to Codiqo is expected");
                }, 5, 5, "com.example:demo", SourceHiding.NONE, new MavenMessageReporter(mojo.getLog())),
                mojo.new MavenCommitBuild(args),
                new AnalysedProject("com.example:demo", "demo", new ClientInfoModel()),
                new OutputSerializer(true, logs.getLogger(OutputSerializer.class)),
                ReviewStarter.LOCAL,
                logs);
    }
    private RunArgs argsFor(RevCommit commit) {
        RunArgs toReturn = new RunArgs();
        toReturn.setGit(repository);
        toReturn.setCommitId(commit.getName());
        return toReturn;
    }
    private RevCommit commitFile(String content, String message) throws Exception {
        Files.writeString(tempDir.resolve("Foo.java"), content, StandardCharsets.UTF_8);
        git.add().addFilepattern("Foo.java").call();
        return git.commit().setMessage(message).setAuthor("Test Author", "test@example.com").call();
    }
}
