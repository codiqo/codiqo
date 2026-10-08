package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.apache.commons.io.IOUtils;

import lombok.experimental.UtilityClass;

/** compares a rendered prompt or report with the copy pinned under {@code src/test/resources/golden}, byte for byte */
@UtilityClass
public class GoldenText {
    public void assertMatches(String name, String actual) throws IOException {
        String resource = "/golden/" + name + ".txt";
        try (InputStream in = GoldenText.class.getResourceAsStream(resource)) {
            assertEquals(IOUtils.toString(Objects.requireNonNull(in, resource), StandardCharsets.UTF_8), actual, resource);
        }
    }
}
