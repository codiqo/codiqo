package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * {@code [#-- ... --]} is not a Thymeleaf comment: TEXT mode prints it, so a note written that way reached the model as
 * part of the scoring prompt. A template comment is {@code /*[- ... -]*}{@code /}, which the parser drops.
 */
class TemplateCommentSyntaxTest {
    private static final String NOT_A_COMMENT = "[#--";

    @Test
    void noTemplateCarriesTextThatOnlyLooksLikeAComment() throws IOException {
        try (Stream<Path> templates = Files.walk(Paths.get("src", "main", "resources", "thymeleaf"))) {
            List<Path> offending = templates.filter(Files::isRegularFile).filter(TemplateCommentSyntaxTest::looksCommented).toList();

            assertEquals(List.of(), offending);
        }
    }
    private static boolean looksCommented(Path template) {
        try {
            return Strings.CS.contains(Files.readString(template, StandardCharsets.UTF_8), NOT_A_COMMENT);
        } catch (IOException err) {
            throw new IllegalStateException("template not readable: " + template, err);
        }
    }
}
