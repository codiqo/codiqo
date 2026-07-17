package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.client.model.CommitModel;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.core.diff.GitCommitAnalysis;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.submit.CommitIndexer;

/**
 * end-to-end mimic of the production history shape that produced the merge-commit PR scoring gap
 * (85 unscored "Merge pull request #N" nodes across 18 projects, reported on localization PR #7):
 * a `dev` mainline receiving direct pushes plus GitHub-style --no-ff PR merges — a single-commit PR
 * whose author also clicked merge, a multi-commit PR merged by a different developer, a pull-sync
 * merge ("Merge branch 'dev' of github...") whose side commits fell off the first-parent spine, and
 * a one-file PR by a third developer. asserts the full chain: first-parent indexing keeps the merge
 * nodes credited to the side-branch author, the analyze gate admits every sole-author merge, and
 * the delta analyzer scores exactly the PR's net change with correct attribution. developer
 * identities are anonymized stand-ins for the three production authors
 */
class MergeCommitPrHistoryMimicTest {
    private static final Date EPOCH = new Date(0);
    private static final String DEV_A_NAME = "Dev A";
    private static final String DEV_A = "dev.a@corp.example";
    private static final String DEV_B_NAME = "Dev B";
    private static final String DEV_B = "dev.b@corp.example";
    private static final String DEV_C_NAME = "Dev C";
    private static final String DEV_C = "dev.c@corp.example";

    @TempDir
    Path tempDir;

    private Git git;
    private Repository repository;

    private RevCommit pr1Work1;
    private RevCommit pr1Work2;
    private RevCommit pr7Work;
    private RevCommit syncRemoteWork;
    private RevCommit pr18Work;
    private String pr1MergeSha;
    private String pr7MergeSha;
    private String syncMergeSha;
    private String pr18MergeSha;
    private List<String> directPushShas;

    @BeforeEach
    void buildLocalizationShapedHistory() throws Exception {
        git = Git.init().setInitialBranch("dev").setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();

        RevCommit base = commitAs("pom.xml", "<project/>", "initial localization service setup", DEV_A_NAME, DEV_A);
        commitAs("src/main/resources/application.yaml", "locale: en-US", "base config", DEV_A_NAME, DEV_A);
        RevCommit direct1 = commitAs("pom.xml", "<project><!-- no debezium --></project>",
                "Remove Debezium and Zookeeper dependencies and related configurations", DEV_B_NAME, DEV_B);

        /**
         * PR #1 mimic: a multi-commit PR authored entirely by Dev B, merged by Dev A — the
         * cross-author case the pre-fix design misattributed to whoever clicked merge
         */
        git.branchCreate().setName("feature/MN-6914-localization-service").call();
        git.checkout().setName("feature/MN-6914-localization-service").call();
        pr1Work1 = commitAs("src/main/java/io/localization/LocalizationService.java", "class LocalizationService {}",
                "MN-6914 Localization service skeleton", DEV_B_NAME, DEV_B);
        pr1Work2 = commitAs("src/main/java/io/localization/TranslationRepository.java", "class TranslationRepository {}",
                "MN-6914 Repository layer", DEV_B_NAME, DEV_B);
        git.checkout().setName("dev").call();
        RevCommit direct2 = commitAs("pom.xml", "<project><!-- deps aligned --></project>",
                "MN-6917 Fix build. Dependencies.", DEV_A_NAME, DEV_A);
        pr1MergeSha = mergeAs("feature/MN-6914-localization-service",
                "Merge pull request #1 from patrianna/feature/MN-6914-localization-service\n\nMN-6914 Localization service",
                DEV_A_NAME, DEV_A);

        /**
         * PR #7 mimic — the reported production bug: a single-commit PR whose author also clicked
         * merge, previously excluded as merge_commit with the work commit never indexed
         */
        git.branchCreate().setName("MN-6917-snapshot-translations-gcs-bucket-job").call();
        git.checkout().setName("MN-6917-snapshot-translations-gcs-bucket-job").call();
        Files.createDirectories(tempDir.resolve("src/main/java/io/localization"));
        pr7Work = commitTwoFilesAs(
                "src/main/java/io/localization/SnapshotPublisher.java", "class SnapshotPublisher {}",
                "src/main/resources/application.yaml", "locale: en-US\ngcs-bucket: snapshots",
                "MN-6917 Publishing snapshot translations to gcs bucket", DEV_A_NAME, DEV_A);
        git.checkout().setName("dev").call();
        RevCommit direct3 = commitAs("pom.xml", "<project><!-- protobuf 4.35.1 --></project>",
                "chore: bump Protobuf to version 4.35.1 in pom.xml", DEV_B_NAME, DEV_B);
        pr7MergeSha = mergeAs("MN-6917-snapshot-translations-gcs-bucket-job",
                "Merge pull request #7 from patrianna/MN-6917-snapshot-translations-gcs-bucket-job\n\nMN-6917 Publishing snapshot translations to gcs bucket",
                DEV_A_NAME, DEV_A);

        /**
         * pull-sync merge mimic: the developer's local dev line and the remote dev line diverged;
         * in production the remote-side commit fell off the first-parent spine and survived only
         * behind the sync merge's second parent
         */
        git.branchCreate().setName("remote-dev").call();
        git.checkout().setName("remote-dev").call();
        syncRemoteWork = commitAs("src/main/java/io/localization/SnapshotStorageLayout.java", "class SnapshotStorageLayout {}",
                "Fixed SnapshotStorageLayout", DEV_A_NAME, DEV_A);
        git.checkout().setName("dev").call();
        commitAs("src/main/resources/application.yaml", "locale: en-GB\ngcs-bucket: snapshots",
                "Change default local to en-GB", DEV_A_NAME, DEV_A);
        syncMergeSha = mergeAs("remote-dev",
                "Merge branch 'dev' of github.com:patrianna/localization into dev", DEV_A_NAME, DEV_A);

        /**
         * PR #18 mimic: a one-file PR by a third developer, merged by Dev A
         */
        git.branchCreate().setName("add-codeowners").call();
        git.checkout().setName("add-codeowners").call();
        pr18Work = commitAs("CODEOWNERS", "* @platform-team", "Add CODEOWNERS", DEV_C_NAME, DEV_C);
        git.checkout().setName("dev").call();
        pr18MergeSha = mergeAs("add-codeowners",
                "Merge pull request #18 from patrianna/add-codeowners\n\nAdd CODEOWNERS", DEV_A_NAME, DEV_A);

        directPushShas = List.of(base.getName(), direct1.getName(), direct2.getName(), direct3.getName());
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
    void firstParentIndexKeepsAllMergeNodesAndDropsEveryPrWorkCommit() throws Exception {
        List<CommitModel> spine = CommitIndexer.extractCommits(repository, new RunArgs(), "HEAD", EPOCH, "dev");
        List<String> shas = spine.stream().map(CommitModel::getSha).toList();

        assertTrue(shas.containsAll(directPushShas), "every direct push to dev stays on the spine");
        assertTrue(shas.containsAll(List.of(pr1MergeSha, pr7MergeSha, syncMergeSha, pr18MergeSha)),
                "every PR merge node stays on the spine — in production these were the ONLY record of the PRs");

        assertFalse(shas.contains(pr1Work1.getName()), "PR work commits are off the first-parent spine");
        assertFalse(shas.contains(pr1Work2.getName()));
        assertFalse(shas.contains(pr7Work.getName()),
                "the production complaint: PR #7's work commit was never indexed — only its merge node carries the credit");
        assertFalse(shas.contains(syncRemoteWork.getName()),
                "the remote-side commit of the pull-sync merge falls off the spine, exactly as observed in production");
        assertFalse(shas.contains(pr18Work.getName()));
    }
    @Test
    void indexCreditsEachMergeNodeToTheSideBranchAuthor() throws Exception {
        List<CommitModel> spine = CommitIndexer.extractCommits(repository, new RunArgs(), "HEAD", EPOCH, "dev");

        assertEquals(DEV_B, mergeNode(spine, pr1MergeSha).getAuthorEmail(),
                "the multi-commit PR is credited to its author, not to Dev A who clicked merge");
        assertEquals(DEV_A, mergeNode(spine, pr7MergeSha).getAuthorEmail());
        assertEquals(DEV_A, mergeNode(spine, syncMergeSha).getAuthorEmail());
        assertEquals(DEV_C, mergeNode(spine, pr18MergeSha).getAuthorEmail());

        for (String mergeSha : List.of(pr1MergeSha, pr7MergeSha, syncMergeSha, pr18MergeSha)) {
            assertTrue(Boolean.TRUE.equals(mergeNode(spine, mergeSha).getIsMerge()));
        }
    }
    @Test
    void analyzeGateAdmitsEverySoleAuthorPrMerge() throws Exception {
        for (String mergeSha : List.of(pr1MergeSha, pr7MergeSha, syncMergeSha, pr18MergeSha)) {
            assertTrue(AnalyzeCommitMojo.mergeSkipReason(runArgs(mergeSha, true)).isEmpty(),
                    "sole-author merge must be analyzable, was excluded in production: " + mergeSha);
        }
    }
    @Test
    void deltaAnalysisOfThePrSevenMimicScoresExactlyThePrNetChange() throws Exception {
        GitCommitAnalysis analysis = analyzeMerge(pr7MergeSha);

        assertEquals(DEV_A, analysis.getAuthorEmail());
        assertEquals(DEV_A_NAME, analysis.getAuthor());
        assertTrue(analysis.isMergeCommit());
        assertEquals(2, analysis.getParentIds().size());

        Set<String> changedPaths = analysis.getFiles().stream()
                .map(FileAnalysis::getNewPath).collect(Collectors.toSet());
        assertEquals(Set.of(
                "src/main/java/io/localization/SnapshotPublisher.java",
                "src/main/resources/application.yaml"), changedPaths,
                "the merge's parent[0] delta is exactly the PR's net change — nothing from the mainline leaks in");

        FileAnalysis added = analysis.getFiles().stream()
                .filter(f -> f.getNewPath().endsWith("SnapshotPublisher.java")).findFirst().orElseThrow();
        assertEquals(DiffEntry.ChangeType.ADD, added.getChangeType());
    }
    @Test
    void deltaAnalysisCreditsTheCrossAuthorPrToTheSideBranchAuthor() throws Exception {
        GitCommitAnalysis analysis = analyzeMerge(pr1MergeSha);

        assertEquals(DEV_B, analysis.getAuthorEmail(), "Dev A clicked merge; the credit belongs to Dev B");
        assertEquals(DEV_B_NAME, analysis.getAuthor());
        assertEquals(Set.of(
                "src/main/java/io/localization/LocalizationService.java",
                "src/main/java/io/localization/TranslationRepository.java"),
                analysis.getFiles().stream().map(FileAnalysis::getNewPath).collect(Collectors.toSet()));
    }
    @Test
    void deltaAnalysisOfThePullSyncMergeRecoversTheRemoteSideWork() throws Exception {
        GitCommitAnalysis analysis = analyzeMerge(syncMergeSha);

        assertEquals(DEV_A, analysis.getAuthorEmail());
        assertEquals(Set.of("src/main/java/io/localization/SnapshotStorageLayout.java"),
                analysis.getFiles().stream().map(FileAnalysis::getNewPath).collect(Collectors.toSet()),
                "the remote-side commit dropped from the spine is recovered through the sync merge's delta");
    }
    private GitCommitAnalysis analyzeMerge(String mergeSha) throws Exception {
        RunArgs args = new RunArgs();
        args.setGit(repository);
        args.setCommitId(mergeSha);

        JGitDeltaAnalyzer analyzer = new JGitDeltaAnalyzer(new SlfLogFactory(), args);
        try (RevWalk walk = new RevWalk(repository)) {
            return (GitCommitAnalysis) analyzer.analyzeCommit(walk.parseCommit(repository.resolve(mergeSha)));
        }
    }
    private RunArgs runArgs(String commitSha, boolean firstParentOnly) {
        RunArgs toReturn = new RunArgs();
        toReturn.setGit(repository);
        toReturn.setCommitId(commitSha);
        toReturn.setFirstParentOnly(firstParentOnly);
        return toReturn;
    }
    private static CommitModel mergeNode(List<CommitModel> spine, String sha) {
        return spine.stream().filter(c -> sha.equals(c.getSha())).findFirst().orElseThrow();
    }
    private String mergeAs(String branch, String message, String authorName, String authorEmail) throws Exception {
        repository.getConfig().setString("user", null, "name", authorName);
        repository.getConfig().setString("user", null, "email", authorEmail);
        repository.getConfig().save();

        // GitHub's "Create a merge commit" button always merges with --no-ff
        return git.merge().include(repository.resolve(branch))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true).setMessage(message).call()
                .getNewHead().getName();
    }
    private RevCommit commitAs(String path, String content, String message, String authorName, String authorEmail)
            throws Exception {
        Path target = tempDir.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).setAuthor(authorName, authorEmail).call();
    }
    private RevCommit commitTwoFilesAs(String path1, String content1, String path2, String content2,
            String message, String authorName, String authorEmail) throws Exception {
        Files.writeString(tempDir.resolve(path1), content1, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(path2), content2, StandardCharsets.UTF_8);
        git.add().addFilepattern(path1).addFilepattern(path2).call();
        return git.commit().setMessage(message).setAuthor(authorName, authorEmail).call();
    }
}
