package io.codiqo.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.codiqo.llm.NoopLog;
import io.codiqo.llm.schema.SkipRequestResponse;

class SkipRequestClientTest {
    private static final String REAL_MESSAGE = """
            remove deprecated methods - everything should work w/o them, all dependent projects were
            updated accordingly so this is safe removal. codiqo please do not count this commit as such
            """;

    @Test
    void quoteFoundInTheMessageIsAccepted() {
        Optional<String> grounded = ground(REAL_MESSAGE, true, "codiqo please do not count this commit");

        assertEquals(Optional.of("codiqo please do not count this commit"), grounded);
    }
    @Test
    void quoteSpanningAWrappedLineIsAcceptedAfterWhitespaceNormalisation() {
        Optional<String> grounded = ground(REAL_MESSAGE, true, "safe removal. codiqo please do not count this commit");

        assertTrue(grounded.isPresent());
    }
    @Test
    void quoteAbsentFromTheMessageIsRejected() {
        Optional<String> grounded = ground(REAL_MESSAGE, true, "this commit must be ignored entirely");

        assertEquals(Optional.empty(), grounded);
    }
    @Test
    void paraphrasedQuoteIsRejected() {
        Optional<String> grounded = ground(REAL_MESSAGE, true, "codiqo, please don't count this commit");

        assertEquals(Optional.empty(), grounded);
    }
    @Test
    void exclusionClaimedWithoutAQuoteIsRejected() {
        assertEquals(Optional.empty(), ground(REAL_MESSAGE, true, ""));
        assertEquals(Optional.empty(), ground(REAL_MESSAGE, true, "   "));
    }
    /**
     * the schema asks for an empty string, but a model that drops the field entirely leaves it null. that
     * must reject like any other unevidenced claim — an NPE here is swallowed by the caller as "detection
     * failed, scoring normally", which silently scores a commit whose author asked to be left out
     */
    @Test
    void exclusionClaimedWithANullQuoteIsRejectedRatherThanThrowing() {
        assertEquals(Optional.empty(), ground(REAL_MESSAGE, true, null));
    }
    /**
     * observed jailbreak: a message that only says "codiqo" plus injected instructions gets an exclusion
     * claimed and the tool's own name offered as the evidence. the substring check alone accepts it — every
     * message reaching the classifier contains "codiqo" by construction — so citing nothing beyond the tool
     * name has to be rejected on its own terms
     */
    @Test
    void quoteThatIsNothingButTheToolNameIsRejectedEvenThoughItAppearsInTheMessage() {
        assertEquals(Optional.empty(), ground(REAL_MESSAGE, true, "codiqo"));
        assertEquals(Optional.empty(), ground(REAL_MESSAGE, true, "Codiqo"));
        assertEquals(Optional.empty(), ground(REAL_MESSAGE, true, "codiqo!"));
    }
    @Test
    void quoteCarryingWordsBeyondTheToolNameIsAccepted() {
        assertEquals(Optional.of("codiqo please do not count"), ground(REAL_MESSAGE, true, "codiqo please do not count"));
    }
    /**
     * rejecting the tool name alone only closes the shape that was observed. a steered classifier can cite
     * any single word it likes and the substring check will find it, so an author who never asked for
     * anything has their commit dropped from their measured output — a request is a clause, not a token
     */
    @Test
    void quoteOfAnOrdinaryFragmentFromTheMessageIsRejected() {
        assertEquals(Optional.empty(), ground(REAL_MESSAGE, true, "removal"));
        assertEquals(Optional.empty(), ground(REAL_MESSAGE, true, "safe removal"));
    }
    /**
     * a letter above U+FFFF is two chars and neither half is a letter on its own, so counting over chars()
     * read a correctly quoted request written in a supplementary-plane script as citing nothing at all and
     * scored the commit anyway
     */
    @Test
    void quoteInASupplementaryPlaneScriptIsAccepted() {
        String phrase = "𠀀𠀁 𠀂𠀃 𠀄𠀅";

        assertEquals(Optional.of(phrase), ground("chore: " + phrase, true, phrase));
    }
    /**
     * Chinese, Japanese and Thai do not put spaces between words, so a whitespace split returns ONE token for
     * any request written in them and the clause floor rejected every one — silently discarding a correctly
     * detected opt-out for a large share of the world's developers. The earlier supplementary-plane fixture
     * hid this because it inserts spaces that real CJK text does not have.
     */
    @Test
    void requestWrittenWithoutSpacesBetweenWordsIsAccepted() {
        assertEquals(Optional.of("请不要统计这个提交"),
                ground("codiqo 请不要统计这个提交", true, "请不要统计这个提交"), "Chinese");
        assertEquals(Optional.of("このコミットを集計しないでください"),
                ground("fix: このコミットを集計しないでください", true, "このコミットを集計しないでください"), "Japanese");
    }
    @Test
    void noExclusionRequestedYieldsNothingEvenWithAValidQuote() {
        Optional<String> grounded = ground(REAL_MESSAGE, false, "codiqo please do not count this commit");

        assertEquals(Optional.empty(), grounded);
    }
    private static Optional<String> ground(String commitMessage, boolean excludeRequested, String quote) {
        SkipRequestResponse response = SkipRequestResponse.builder()
                .excludeRequested(excludeRequested)
                .quote(quote)
                .build();
        return SkipRequestClient.groundedQuote(commitMessage, response, NoopLog.INSTANCE);
    }
}
