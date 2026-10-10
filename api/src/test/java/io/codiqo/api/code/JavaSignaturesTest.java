package io.codiqo.api.code;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaSignaturesTest {
    /** what the index prints and what a reviewer writes for the same member land on one form */
    @Test
    void theIndexAndAReviewerAgree() {
        assertEquals("handle(List,Optional)", JavaSignatures.comparable("handle(List<String>, Optional<Long>)", "Totals"));
        assertEquals("handle(List,Optional)", JavaSignatures.comparable("handle(java.util.List<String>, Optional<Long>)", "Totals"));
        assertEquals("put(Map,Optional)", JavaSignatures.comparable("put(Map<String, List<Integer>>, Optional<Map<K, V>>) <K, V>", "Totals"));
        assertEquals("Totals(Config,Clock)", JavaSignatures.comparable("<init>(Config,Clock)", "Totals"));
        assertEquals("Totals(Config,Clock)", JavaSignatures.comparable("Totals(Config, Clock)", "Totals"));
        assertEquals("join(String[])", JavaSignatures.comparable("join(@Nullable java.lang.String...)", "Totals"));
        assertEquals("entry(Entry)", JavaSignatures.comparable("entry(Map.Entry<K, V>)", "Totals"));
        assertEquals("replace(Migration)", JavaSignatures.comparable("replace(Watchdog#Migration)", "Watchdog"));
        assertEquals("replace(Migration)", JavaSignatures.comparable("replace(Migration)", "Watchdog"));
        assertEquals("name()", JavaSignatures.comparable("name()", "Totals"));
        assertEquals("name", JavaSignatures.comparable("name", "Totals"));
    }
    @Test
    void aTypeIsNamedSimplyAndErased() {
        assertEquals("Map", JavaSignatures.simpleTypeName("@Nullable java.util.Map<String, List<Integer>>"));
        assertEquals("String[]", JavaSignatures.simpleTypeName("String..."));
        assertEquals("int[][]", JavaSignatures.simpleTypeName("int[][]"));
    }
}
