package io.codiqo.maven.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import org.apache.commons.io.IOUtils;

import lombok.experimental.UtilityClass;

/**
 * Compares a rendered report with the copy pinned under {@code src/test/resources}, byte for byte. A mismatch also
 * writes what was rendered to {@code target/golden-actual}, so the two can be diffed.
 */
@UtilityClass
class GoldenOutput {
    void assertMatches(String name, String actual) throws IOException {
        Path written = Paths.get("target", "golden-actual", name);
        Files.createDirectories(written.getParent());

        try (InputStream in = GoldenOutput.class.getResourceAsStream("golden/" + name)) {
            String expected = Objects.isNull(in) ? null : IOUtils.toString(in, StandardCharsets.UTF_8);
            if (Objects.equals(expected, actual)) {
                return;
            }
            Files.writeString(written, actual, StandardCharsets.UTF_8);
            assertEquals(expected, actual, "rendered output differs from golden/" + name + ", see " + written.toAbsolutePath());
        }
    }
}
