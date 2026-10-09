package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.util.Fetch;

class DefaultLanguageProcessorsExtensionsTest {
    @TempDir
    Path tempDir;

    /** the review gate decides from the static list before a registry exists, so it must be the registry's own */
    @Test
    void theSupportedExtensionsAreTheRegistrysOwn() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            RunArgs args = new RunArgs();
            args.setGit(git.getRepository());
            try (Fetch fetch = new Fetch(args);
                    DefaultLanguageProcessors processors = new DefaultLanguageProcessors(new SlfLogFactory(), args, fetch)) {
                assertEquals(DefaultLanguageProcessors.supportedExtensions(), Sets.newHashSet(processors.extensions()));
            }
        }
    }
}
