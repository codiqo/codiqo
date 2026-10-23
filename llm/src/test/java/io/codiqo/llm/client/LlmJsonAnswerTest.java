package io.codiqo.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


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
    /**
     * A reviewer once wrote one label's field name twice ({@code {"file":"file":...}}) late in its answer: the label
     * after the fault parses on its own and was read as the whole answer, which then failed for a missing field. The
     * answer must fail on its own fault instead.
     */
    @Test
    void aLabelAfterTheFaultOfABrokenAnswerDoesNotStandInForIt() {
        ObjectNode answer = JSON.objectNode();
        answer.putArray("blocking").addObject().put("title", "Refund is credited twice").put("file", "A.java").put("line", 3)
                .put("description", "A retried refund request reaches the ledger twice because the idempotency key is built after the first write.");
        answer.putArray("major");
        answer.putArray("minor");
        answer.putArray("unitLabels").addObject().put("file", "A.java").put("signature", "refund(String id)").put("category", "COMPLEX").put("reason", "orders the ledger writes");
        String label = "{\"file\":\"C.java\",\"signature\":\"c()\",\"category\":\"ROUTINE\",\"reason\":\"adds a check\"}";
        String broken = "Merged:\n" + Strings.CS.removeEnd(answer.toString(), "]}") + ",{\"file\":\"file\":\"B.java\"}," + label + "],\"observations\":[]}";

        JacksonException err = assertThrows(JacksonException.class, () -> read(broken));

        assertTrue(Strings.CS.contains(err.getOriginalMessage(), "expecting comma"), err.getOriginalMessage());
    }
    /** a reviewer quoted the reviewed file's JSON before its answer: the larger object is the answer */
    @Test
    void aJsonObjectQuotedBeforeTheAnswerDoesNotStandInForIt() {
        assertEquals(1, read("The change leaves `{\"mcpServers\": {}}`, which is valid. Review:\n" + FINDINGS).getBlocking().size());
    }
    /** a brace in the prose that is never closed is not an answer, and covers nothing after it */
    @Test
    void anUnclosedBraceInTheProseDoesNotHideTheAnswer() {
        assertEquals(1, read("The handler opens `try {` and never closes it.\n" + FINDINGS).getBlocking().size());
    }
    /** an example in the prose that opens like an object and is never closed covers only what the parser read of it */
    @Test
    void anUnclosedObjectLikeExampleInTheProseDoesNotHideTheAnswer() {
        assertEquals(1, read("Each finding looks like {\"title\": followed by its text.\n" + FINDINGS).getBlocking().size());
    }
    private static LlmScoringResponse.Bugs read(String answer) {
        return LlmJson.readAnswer(answer, LlmScoringResponse.Bugs.class);
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
