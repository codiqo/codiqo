package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.RunArgs;
import io.codiqo.util.RuntimeRequirementException;

class DefaultLanguageProcessorsRuntimeTest {
    @TempDir
    Path tempDir;

    /**
     * the check before the build is the one that halts nothing: a JDK the default language server cannot start on is
     * the machine's to fix, and nothing was reviewed yet
     */
    @Test
    void aJdkTheLanguageServerCannotStartOnIsARuntimeRequirement() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("jdk-21"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.5\"" + StringUtils.LF, StandardCharsets.UTF_8);
        RunArgs args = new RunArgs();
        args.setJavaHome(home.toFile());

        RuntimeRequirementException err = assertThrows(RuntimeRequirementException.class, () -> DefaultLanguageProcessors.requireRuntime(args));

        assertTrue(Strings.CS.contains(err.getMessage(), "needs Java 25"), err.getMessage());
    }
}
