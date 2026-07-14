package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.ProjectBuildingException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.maven.timemachine.TimeMachineConfig;

class TimeMachineSupportTest {
    private static final Log LOG = new SystemStreamLog();

    @TempDir
    Path tempDir;

    private Git git;
    private Repository repository;
    private RevCommit commit;

    @BeforeEach
    void initRepo() throws Exception {
        git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call();
        repository = new FileRepositoryBuilder().setGitDir(new File(tempDir.toFile(), ".git")).build();

        Files.writeString(tempDir.resolve("a.txt"), "1", StandardCharsets.UTF_8);
        git.add().addFilepattern("a.txt").call();
        commit = git.commit().setMessage("first").setAuthor("Test Author", "test@example.com").setCommitter("Test Author", "test@example.com").call();
    }
    @AfterEach
    void tearDown() {
        git.close();
        repository.close();

        System.clearProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP);
        System.clearProperty(TimeMachineConfig.PROP_TARGET_OFFSET);
    }

    @Test
    void pinsCommitTimestampDuringActionAndClearsAfter() throws Exception {
        RunArgs args = argsWithOffset(Duration.ZERO);

        String seen = TimeMachineSupport.withHostPinning(args, LOG, () -> System.getProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP));

        assertEquals(DateTimeFormatter.ISO_INSTANT.format(commit.getCommitterIdent().getWhenAsInstant()), seen);
        assertNull(System.getProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP));
    }
    @Test
    void positiveOffsetIsExposedDuringAction() throws Exception {
        RunArgs args = argsWithOffset(Duration.ofHours(4));

        String seen = TimeMachineSupport.withHostPinning(args, LOG, () -> System.getProperty(TimeMachineConfig.PROP_TARGET_OFFSET));

        assertEquals("PT4H", seen);
        assertNull(System.getProperty(TimeMachineConfig.PROP_TARGET_OFFSET));
    }
    @Test
    void zeroOffsetSetsNoOffsetProperty() throws Exception {
        RunArgs args = argsWithOffset(Duration.ZERO);

        assertNull(TimeMachineSupport.withHostPinning(args, LOG, () -> System.getProperty(TimeMachineConfig.PROP_TARGET_OFFSET)));
    }
    @Test
    void nullOffsetLeavesResolutionUnpinned() throws Exception {
        RunArgs args = argsWithOffset(null);

        assertNull(TimeMachineSupport.withHostPinning(args, LOG, () -> System.getProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP)));
    }
    @Test
    void retriesUnpinnedWhenPinnedModelBuildingFails() throws Exception {
        RunArgs args = argsWithOffset(Duration.ZERO);

        String seen = TimeMachineSupport.withHostPinning(args, LOG, () -> {
            if (StringUtils.isNotBlank(System.getProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP))) {
                throw new ProjectBuildingException("project", "pinned failure", (Throwable) null);
            }
            return "unpinned";
        });

        assertEquals("unpinned", seen);
        assertNull(System.getProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP));
    }
    @Test
    void clearsPropertiesWhenActionThrows() {
        RunArgs args = argsWithOffset(Duration.ofMinutes(15));

        assertThrows(IllegalStateException.class, () -> TimeMachineSupport.withHostPinning(args, LOG, () -> {
            throw new IllegalStateException("boom");
        }));
        assertNull(System.getProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP));
        assertNull(System.getProperty(TimeMachineConfig.PROP_TARGET_OFFSET));
    }
    private RunArgs argsWithOffset(Duration offset) {
        RunArgs args = new RunArgs();
        args.setGit(repository);
        args.setCommitId(commit.getName());
        args.setTimeMachineTargetOffset(offset);
        return args;
    }
}
