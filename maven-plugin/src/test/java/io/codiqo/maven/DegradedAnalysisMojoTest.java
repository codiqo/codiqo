package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
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
import io.codiqo.client.ApiException;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.util.JGit;

/**
 * Behavioral coverage for the build-failure degraded path: scoreOnBuildFailure=false must keep the
 * pre-feature exclude-with-diffs behavior, scoreOnBuildFailure=true must produce a source-only
 * degraded submission (falling back to a diff-only submission when the source index fails) carrying
 * the buildFailure block and invoke scoring, and the revert gate that the successful-build path
 * applies in doExecute must hold in degraded mode too.
 */
class DegradedAnalysisMojoTest {
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
    private RecordingMojo mojo;

    @BeforeEach
    void init() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();

        MavenProject project = new MavenProject();
        project.setGroupId("com.example");
        project.setArtifactId("demo");
        project.setName("demo");

        mojo = new RecordingMojo();
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
    void closeRepo() {
        git.close();
        repository.close();
    }
    @Test
    void flagOffBuildFailureExcludesWithCapturedDiffFiles() throws Exception {
        commitFile(JAVA_V1, "first");
        RevCommit second = commitFile(JAVA_V2, "second");

        mojo.doDegradedAnalysis(argsFor(second), "[ERROR] COMPILATION ERROR", AnalysisExcludeCategory.BUILD_FAILURE, "cannot find symbol");

        assertNull(mojo.scoredCtx, "flag off (default) must not run degraded scoring");
        assertEquals(1, mojo.exclusions.size());
        Exclusion exclusion = mojo.exclusions.iterator().next();
        assertEquals("[ERROR] COMPILATION ERROR", exclusion.reason());
        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, exclusion.category());
        assertEquals("cannot find symbol", exclusion.detail());
        assertTrue(CollectionUtils.isNotEmpty(exclusion.files()), "exclusion must carry the captured diff files");
    }
    @Test
    void flagOnBuildFailureScoresSourceOnlySubmission() throws Exception {
        commitFile(JAVA_V1, "first");
        RevCommit second = commitFile(JAVA_V2, "second");
        RunArgs args = argsFor(second);
        args.setScoreOnBuildFailure(true);

        mojo.doDegradedAnalysis(args, "[ERROR] COMPILATION ERROR", AnalysisExcludeCategory.BUILD_FAILURE, "cannot find symbol");

        assertTrue(mojo.exclusions.isEmpty(), "flag on with an analyzable diff must not exclude");
        assertNotNull(mojo.scoredCtx, "degraded scoring must run");

        AnalysisSubmissionModel submission = mojo.scoredCtx.getSubmissionModel();
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

        /**
         * a failed build still contains real developer work: the source-only index parses code units and
         * emits driver-score statistics so genuine code volume is scored, not the diff line count alone
         */
        boolean anyCodeUnits = submission.getFiles().stream().anyMatch(file -> CollectionUtils.isNotEmpty(file.getCodeUnits()));
        assertTrue(anyCodeUnits, "source-only degraded mode must parse code units for volume scoring");
        assertNotNull(submission.getProjectMetrics(), "driver-score statistics must be attached");
        assertNotNull(submission.getProjectMetrics().getDriverScalers());
        assertTrue(submission.getProjectMetrics().getDriverScalers().getMethodScalerProd().getPopulation() > 0,
                "driver-scaler population must be non-empty so changed blocks actually score");

        assertNull(submission.getDuplication());
        assertNull(submission.getProjectQuality(), "degraded mode captures no PMD/CPD/coverage — no fake quality aggregate");
        assertNull(submission.getFullProjectCoverage(), "degraded mode captures no coverage — renders n/a, not 0%");
    }
    @Test
    void flagOnSourceIndexFailureFallsBackToDiffOnly() throws Exception {
        commitFile(JAVA_V1, "first");
        RevCommit second = commitFile(JAVA_V2, "second");
        RunArgs args = argsFor(second);
        args.setScoreOnBuildFailure(true);
        mojo.failSourceOnlyIndex = true;

        mojo.doDegradedAnalysis(args, "[ERROR] COMPILATION ERROR", AnalysisExcludeCategory.BUILD_FAILURE, "cannot find symbol");

        assertTrue(mojo.exclusions.isEmpty(), "source-index failure with an analyzable diff must fall back, not exclude");
        assertNotNull(mojo.scoredCtx, "degraded scoring must still run on the diff-only fallback");

        AnalysisSubmissionModel submission = mojo.scoredCtx.getSubmissionModel();
        assertEquals("[ERROR] COMPILATION ERROR", submission.getBuildFailure().getReason());
        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, submission.getBuildFailure().getCategory());
        assertEquals(second.getName(), submission.getCommit().getSha());

        assertTrue(CollectionUtils.isNotEmpty(submission.getFiles()));
        for (FileChangeModel file : submission.getFiles()) {
            assertTrue(StringUtils.isNotBlank(file.getDiff()), "diff must be captured for " + file.getPath());
        }

        /**
         * the fallback is strictly diff-only: when the source index throws, no code units and no
         * driver-score statistics are emitted, so the buildFailure block is scored from the diff alone
         */
        boolean anyCodeUnits = submission.getFiles().stream().anyMatch(file -> CollectionUtils.isNotEmpty(file.getCodeUnits()));
        assertFalse(anyCodeUnits, "diff-only fallback must not carry code units");
        assertNull(submission.getProjectMetrics(), "diff-only fallback must not carry driver-score statistics");
    }
    @Test
    void flagOnRevertCommitExcludesRevertAndOriginal() throws Exception {
        commitFile(JAVA_V1, "first");
        RevCommit second = commitFile(JAVA_V2, "second");
        RevCommit revert = commitFile(JAVA_V1, "Revert \"second\"\n\nThis reverts commit " + second.getName() + ".");
        RunArgs args = argsFor(revert);
        args.setScoreOnBuildFailure(true);

        mojo.doDegradedAnalysis(args, "[ERROR] COMPILATION ERROR", AnalysisExcludeCategory.BUILD_FAILURE, null);

        assertNull(mojo.scoredCtx, "revert commits must not be scored even in degraded mode");
        assertEquals(2, mojo.exclusions.size());

        Iterator<Exclusion> it = mojo.exclusions.iterator();
        Exclusion revertExclusion = it.next();
        assertEquals(revert.getName(), revertExclusion.commitSha());
        assertEquals(AnalysisExcludeCategory.REVERT_COMMIT, revertExclusion.category());

        Exclusion originalExclusion = it.next();
        assertEquals(second.getName(), originalExclusion.commitSha());
        assertEquals(AnalysisExcludeCategory.REVERTED, originalExclusion.category());
        assertEquals("reverted by commit " + JGit.shortSha(revert.getName()), originalExclusion.reason());
    }
    @Test
    void excludeRevertedCommitsOffExcludesOnlyRevertItself() throws Exception {
        commitFile(JAVA_V1, "first");
        RevCommit second = commitFile(JAVA_V2, "second");
        RevCommit revert = commitFile(JAVA_V1, "Revert \"second\"\n\nThis reverts commit " + second.getName() + ".");
        RunArgs args = argsFor(revert);
        args.setScoreOnBuildFailure(true);
        args.setExcludeRevertedCommits(false);

        mojo.doDegradedAnalysis(args, "[ERROR] COMPILATION ERROR", AnalysisExcludeCategory.BUILD_FAILURE, null);

        assertEquals(1, mojo.exclusions.size());
        Exclusion exclusion = mojo.exclusions.iterator().next();
        assertEquals(revert.getName(), exclusion.commitSha());
        assertEquals(AnalysisExcludeCategory.REVERT_COMMIT, exclusion.category());
    }
    @Test
    void originalCommitUnknownToBackendIsTolerated() throws Exception {
        commitFile(JAVA_V1, "first");
        RevCommit second = commitFile(JAVA_V2, "second");
        RevCommit revert = commitFile(JAVA_V1, "Revert \"second\"\n\nThis reverts commit " + second.getName() + ".");
        RunArgs args = argsFor(revert);
        args.setScoreOnBuildFailure(true);
        mojo.notFoundSha = second.getName();

        mojo.doDegradedAnalysis(args, "[ERROR] COMPILATION ERROR", AnalysisExcludeCategory.BUILD_FAILURE, null);

        assertEquals(1, mojo.exclusions.size());
        assertEquals(AnalysisExcludeCategory.REVERT_COMMIT, mojo.exclusions.iterator().next().category());
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

    private static final class RecordingMojo extends AbstractAnalyzeMojo {
        private SubmissionContext scoredCtx;
        private String notFoundSha;
        private boolean failSourceOnlyIndex;
        private final List<Exclusion> exclusions = new ArrayList<>();

        @Override
        protected void doLlmScoring(SubmissionContext ctx) {
            scoredCtx = ctx;
        }
        @Override
        protected SubmissionContext buildSourceOnlyDegradedSubmission(RunArgs args, String reason, AnalysisExcludeCategory category, String detail) throws Exception {
            if (failSourceOnlyIndex) {
                throw new IOException("simulated source index failure");
            }
            return super.buildSourceOnlyDegradedSubmission(args, reason, category, detail);
        }
        @Override
        protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category, String detail, List<FileChangeModel> files) throws ApiException {
            if (commitSha.equals(notFoundSha)) {
                throw new ApiException(HttpURLConnection.HTTP_NOT_FOUND, "analysis not found: " + commitSha);
            }
            exclusions.add(new Exclusion(commitSha, reason, category, detail, files));
        }
    }
    private record Exclusion(String commitSha, String reason, AnalysisExcludeCategory category, String detail, List<FileChangeModel> files) {
    }
}
