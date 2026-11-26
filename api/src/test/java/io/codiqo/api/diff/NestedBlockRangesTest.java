package io.codiqo.api.diff;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NestedBlockRangesTest {
    @Test
    void aUnitIsBilledTheAnchorsOfItsRangeAndTheLineAfterIt() {
        assertTrue(NestedBlockRanges.billsDeletion(30, 39, List.of(), 30));
        assertTrue(NestedBlockRanges.billsDeletion(30, 39, List.of(), 40), "a line deleted at the end anchors on the next line");
        assertFalse(NestedBlockRanges.billsDeletion(30, 39, List.of(), 29));
        assertFalse(NestedBlockRanges.billsDeletion(30, 39, List.of(), 41));
    }
    @Test
    void aUnitIsNotBilledWhatANestedUnitClaims() {
        List<int[]> nested = List.of(new int[] { 40, 50 });

        assertFalse(NestedBlockRanges.billsDeletion(30, 60, nested, 40));
        assertFalse(NestedBlockRanges.billsDeletion(30, 60, nested, 51), "the nested unit's end slack is its own");
        assertTrue(NestedBlockRanges.billsDeletion(30, 60, nested, 52));
        assertTrue(NestedBlockRanges.billsDeletion(40, 50, List.of(), 40));
    }
}
