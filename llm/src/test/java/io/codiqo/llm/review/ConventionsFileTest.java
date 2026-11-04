package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.llm.PromptFences;

class ConventionsFileTest {
    @TempDir
    Path directory;

    /** the file holds the fenced text the prompt used to carry, and its line count is the one the prompt states */
    @Test
    void theConventionsAreWrittenFencedWithTheirLineCount() throws Exception {
        ConventionsFile file = ConventionsFile.write("\n### AGENTS.md\n\nPrefer Optional over null returns.\n", directory).orElseThrow();

        List<String> lines = Files.readAllLines(file.getPath(), StandardCharsets.UTF_8);
        assertEquals(List.of(PromptFences.CONVENTIONS_BEGIN, "### AGENTS.md", StringUtils.EMPTY, "Prefer Optional over null returns.", PromptFences.CONVENTIONS_END), lines);
        assertEquals(lines.size(), file.getLines());
        assertEquals(directory.resolve(ConventionsFile.FILE_NAME).toAbsolutePath(), file.getPath());
        assertTrue(file.getPath().isAbsolute(), "the agents run in the analysis clone, so a relative path would point into it");
    }
    @Test
    void noConventionsWriteNoFile() throws Exception {
        assertEquals(Optional.empty(), ConventionsFile.write(" \n ", directory));
        assertFalse(Files.exists(directory.resolve(ConventionsFile.FILE_NAME)));
    }
    /** a file of several hundred kilobytes goes through whole: nothing is cut on the way to the agents */
    @Test
    void aLargeRuleSetIsWrittenWhole() throws Exception {
        String rules = StringUtils.repeat("- Every service method validates its arguments first.\n", 6_000);
        ConventionsFile file = ConventionsFile.write(rules, directory).orElseThrow();

        String written = Files.readString(file.getPath(), StandardCharsets.UTF_8);
        assertTrue(written.contains(rules.strip()));
        assertTrue(written.endsWith(PromptFences.CONVENTIONS_END + StringUtils.LF));
        assertEquals(6_002, file.getLines());
    }
}
