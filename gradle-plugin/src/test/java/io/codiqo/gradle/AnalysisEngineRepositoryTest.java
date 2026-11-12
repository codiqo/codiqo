package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.gradle.model.AnalysisRequest;

/** a Gradle submission names the repository it came from, as a Maven run of the same commit does */
class AnalysisEngineRepositoryTest {
    private static final String ORIGIN = "https://github.com/example/project.git";

    @TempDir
    Path tempDir;

    @Test
    void theAnalysedRepositorysRemotesAreItsUrls() throws Exception {
        try (Git git = Git.init().setInitialBranch("main").setDirectory(tempDir.toFile()).call()) {
            Files.writeString(tempDir.resolve("README.md"), "# project", StandardCharsets.UTF_8);
            git.add().addFilepattern("README.md").call();
            RevCommit commit = git.commit().setMessage("add readme").setAuthor("Author", "author@example.com").setSign(false).call();
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", ORIGIN);
            config.save();

            RunArgs args = new RunArgs();
            AnalysisEngine.applyRepository(new AnalysisRequest(), args, git.getRepository());

            assertEquals(Set.of(ORIGIN), args.getRemoteUrls());
            assertEquals(commit.getName(), args.getCommitId());
        }
    }
}
