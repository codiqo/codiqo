package io.codiqo.maven;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.logging.Log;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.ConventionGuidance;
import io.codiqo.maven.logging.MavenLogFactory;
import lombok.experimental.UtilityClass;

/**
 * The project's agent instruction files for the local review, read exactly as the scoring prompt reads them. A file
 * that cannot be read, or a file set over its size budget, fails the review rather than leaving it without guidance:
 * {@code ConventionGuidance} already skips a single unreadable file, so what reaches here is a broken repository or a
 * budget the project has to raise.
 */
@UtilityClass
public class LocalReviewGuidance {
    public String read(RunArgs args, Log log) throws IOException {
        return ConventionGuidance.read(args, new MavenLogFactory(log).getLogger(LocalReviewGuidance.class));
    }
    public String read(File directory, Log log) throws IOException {
        try (Repository repository = new FileRepositoryBuilder().findGitDir(directory).build()) {
            RunArgs args = new RunArgs();
            args.setGit(repository);
            return read(args, log);
        }
    }
}
