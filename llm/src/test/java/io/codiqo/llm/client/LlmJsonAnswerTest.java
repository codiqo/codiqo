package io.codiqo.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import io.codiqo.llm.schema.LlmScoringResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class LlmJsonAnswerTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String FINDINGS = findings();

    @Test
    void aBareObjectIsRead() {
        assertEquals("t", read(FINDINGS).getBlocking().getFirst().getTitle());
    }
    @Test
    void anObjectAfterLeadingProseIsRead() {
        assertEquals("A.java", read("All 7 reviewers reported. Merged result:\n" + FINDINGS).getBlocking().getFirst().getFile());
    }
    @Test
    void bracesInsideTheProseAreSkipped() {
        assertEquals(3, read("The reviewer quoted `if (x) { return; }` before answering.\n" + FINDINGS + "\nDone.").getBlocking().getFirst().getLine());
    }
    /** Jackson 3 rejects trailing tokens, so the object must be cut at its own end, not at the text's last brace */
    @Test
    void bracesInTrailingProseAreSkipped() {
        assertEquals(3, read(FINDINGS + "\nNote: the fix is `if (x) { return; }`").getBlocking().getFirst().getLine());
    }
    @Test
    void anExampleObjectInTheProseDoesNotStandInForTheAnswer() {
        assertEquals(1, read("An empty review would be `{}`; this one is:\n" + FINDINGS).getBlocking().size());
    }
    @Test
    void aFencedObjectIsRead() {
        assertEquals(1, read("```json\n" + FINDINGS + "\n```").getBlocking().size());
    }
    @Test
    void anAnswerWithoutAnObjectFailsOnTheRealText() {
        assertThrows(JacksonException.class, () -> read("I could not finish the review in time."));
    }
    /**
     * An answer whose last string was never closed: the bug object inside it parses on its own, and must not be read as
     * the answer, an almost empty one, or the reviewer's repair turn is never asked for.
     */
    @Test
    void anObjectInsideABrokenAnswerDoesNotStandInForIt() {
        ObjectNode answer = (ObjectNode) JsonMapper.builder().build().readTree(FINDINGS);
        answer.put("note", "the reviewer stopped mid-sentence");
        /** cut before the closing quote and brace: the note's string is never closed */
        String broken = Strings.CS.removeEnd(answer.toString(), "\"}");
        assertThrows(JacksonException.class, () -> read(broken));
    }
    /** a stray brace right before the answer fails exactly where the answer starts, and the answer still reads */
    @Test
    void aStrayBraceRightBeforeTheAnswerDoesNotHideIt() {
        assertEquals(1, read("Merged {" + FINDINGS).getBlocking().size());
    }
    private static LlmScoringResponse.Bugs read(String answer) {
        return LlmJson.readAnswer(answer, LlmScoringResponse.Bugs.class);
    }
    /** a coordinator once collapsed every reviewer's labels into their category names; the rest must still read */
    @Test
    void aMalformedFieldTakenFromElsewhereDoesNotCostTheRestOfTheAnswer() {
        ObjectNode merged = JSON.objectNode().put("summary", "Adds a cancel endpoint").put("taskComplexity", 6);
        merged.putArray("blocking");
        merged.putArray("taskTypes").add("feature").add("test");
        merged.putArray("blockCategories").add("ROUTINE").add("MECHANICAL");
        String answer = "Writing the merged result.\n" + merged;

        LlmScoringResponse read = LlmJson.readAnswerIgnoring(answer, LlmScoringResponse.class, "blockCategories");

        assertEquals("Adds a cancel endpoint", read.getSummary());
        assertEquals(6, read.getTaskComplexity());
        assertEquals(List.of(LlmScoringResponse.TaskType.FEATURE, LlmScoringResponse.TaskType.TEST), read.getTaskTypes());
        assertTrue(CollectionUtils.isEmpty(read.getBlockCategories()), "the malformed labels are dropped, not half-read");
    }
    private static String findings() {
        ObjectNode toReturn = JSON.objectNode();
        ObjectNode task = toReturn.putArray("tasks").addObject().put("findings", 1);
        task.putArray("paths").add("a");
        toReturn.putArray("blocking").addObject().put("title", "t").put("file", "A.java").put("line", 3);
        toReturn.putArray("major");
        toReturn.putArray("minor");
        return toReturn.toString();
    }
}
