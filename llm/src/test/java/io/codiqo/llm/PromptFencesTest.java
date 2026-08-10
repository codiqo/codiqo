package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

class PromptFencesTest {
    // a fence marker sits alone on its line; without the newline exclusion this spans the ">>> RECOMMENDED IMPACT ... <<<" decorations
    private static final Pattern FENCE = Pattern.compile("<<<[^<>\\r\\n]+>>>");
    private static final List<String> TEMPLATES = List.of(
            "thymeleaf/templates/skip-request-prompt.txt",
            "thymeleaf/templates/user-message.txt",
            "thymeleaf/templates/system-prompt.txt",
            "thymeleaf/templates/pre-computed-scores.txt");

    /**
     * the guard that makes the convention enforced rather than remembered: a template that fences a new
     * untrusted region without registering the marker here would embed it unstripped, which is exactly how
     * the commit-message region shipped unprotected
     */
    @Test
    void everyFenceMarkerUsedByATemplateIsRegistered() throws IOException {
        Set<String> used = new LinkedHashSet<>();
        for (String template : TEMPLATES) {
            Matcher matcher = FENCE.matcher(read(template));
            while (matcher.find()) {
                used.add(matcher.group());
            }
        }

        assertTrue(used.containsAll(PromptFences.MARKERS),
                "PromptFences declares a marker no template uses: " + used);
        assertTrue(PromptFences.MARKERS.containsAll(used),
                "a template fences a region with a marker PromptFences does not strip: " + used);
    }
    @Test
    void closingMarkerIsRemovedFromEmbeddedText() {
        assertEquals("before after", PromptFences.strip("before " + PromptFences.COMMIT_MESSAGE_END + "after").replace("  ", " "));
    }
    /**
     * a caller embedding one region should not have to know which marker belongs to it — an opening marker
     * in the data invites the model to read what follows as a region of its own
     */
    @Test
    void markersFromEveryFenceAreRemovedNotJustTheOneBeingWritten() {
        String text = PromptFences.CONVENTIONS_BEGIN + "x" + PromptFences.COMMIT_MESSAGE_BEGIN + "y" + PromptFences.CONVENTIONS_END;

        assertEquals("xy", PromptFences.strip(text));
    }
    @Test
    void caseVariantOfAMarkerIsRemoved() {
        assertEquals("x", PromptFences.strip("x<<<commit_message_end>>>"));
    }
    /**
     * the bypass a single pass misses: the inner marker is removed and the surrounding halves close up into
     * a working marker, so stripping has to run until the text stops shrinking
     */
    @Test
    void markerReassembledByRemovingANestedOneIsAlsoRemoved() {
        String nested = "<<<COMMIT_" + PromptFences.COMMIT_MESSAGE_END + "MESSAGE_END>>>";

        assertEquals("", PromptFences.strip(nested));
    }
    private static String read(String resource) throws IOException {
        try (InputStream in = PromptFencesTest.class.getClassLoader().getResourceAsStream(resource)) {
            return IOUtils.toString(Objects.requireNonNull(in, resource), StandardCharsets.UTF_8);
        }
    }
}
